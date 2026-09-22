/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.download;

import android.content.Context;
import android.os.AsyncTask;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryDetailMetadata;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

/**
 * Fetches the detail of every gallery in the download list, in list order, and stores it in the
 * gallery's download folder. Locally imported galleries are skipped, and so are galleries that are
 * no longer available online.
 */
public final class GalleryDetailMetadataBatchTask
        extends AsyncTask<Void, int[], GalleryDetailMetadataBatchTask.Result> {

    public interface Listener {

        void onProgress(int current, int total);

        void onFinished(int updated, int skipped);
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static final int BURST_SIZE = 10;
    private static final long BURST_PAUSE_MS = 2000L;

    public static final class Result {

        public final int updated;
        public final int skipped;

        Result(int updated, int skipped) {
            this.updated = updated;
            this.skipped = skipped;
        }
    }

    private final Context application;
    private final OkHttpClient okHttpClient;
    @Nullable
    private final Listener listener;
    private final List<DownloadInfo> pending = new ArrayList<>();
    private boolean ownsRunningFlag;

    public GalleryDetailMetadataBatchTask(@NonNull Context context, @Nullable Listener listener) {
        application = context.getApplicationContext();
        this.listener = listener;
        okHttpClient = EhApplication.getOkHttpClient(application);
        DownloadManager manager = EhApplication.getDownloadManager(application);
        for (DownloadInfo info : new ArrayList<>(manager.getAllDownloadInfoList())) {
            if (info == null || info.gid <= 0L || info.token == null || info.token.isEmpty()
                    || DownloadManager.isImportedGallery(info)) {
                continue;
            }
            pending.add(info);
        }
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public int getTotal() {
        return pending.size();
    }

    @Override
    protected void onPreExecute() {
        ownsRunningFlag = RUNNING.compareAndSet(false, true);
        if (!ownsRunningFlag) {
            cancel(false);
        }
    }

    @Override
    protected Result doInBackground(Void... ignored) {
        int updated = 0;
        int skipped = 0;
        int processed = 0;
        int burst = 0;
        for (DownloadInfo info : pending) {
            if (isCancelled()) {
                break;
            }
            // Resolving the folder may list the download directory, so it must happen here
            UniFile dir = SpiderDen.getExistingGalleryDownloadDir(info);
            if (dir == null || !dir.isDirectory()) {
                skipped++;
            } else {
                try {
                    GalleryDetail detail = EhEngine.getGalleryDetail(null, okHttpClient,
                            EhUrl.getGalleryDetailUrl(info.gid, info.token));
                    GalleryDetailMetadata.writeIfChanged(detail, dir);
                    updated++;
                } catch (Throwable e) {
                    ExceptionUtils.throwIfFatal(e);
                    // Removed, blocked or unreachable, keep whatever is already stored
                    skipped++;
                }
            }
            processed++;
            publishProgress(new int[]{processed, pending.size()});
            if (++burst >= BURST_SIZE && processed < pending.size()) {
                burst = 0;
                try {
                    Thread.sleep(BURST_PAUSE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new Result(updated, skipped);
    }

    @Override
    protected void onProgressUpdate(int[]... values) {
        if (listener != null && values.length != 0) {
            listener.onProgress(values[0][0], values[0][1]);
        }
    }

    @Override
    protected void onPostExecute(Result result) {
        if (ownsRunningFlag) {
            RUNNING.set(false);
        }
        if (listener != null) {
            listener.onFinished(result.updated, result.skipped);
        }
        Toast.makeText(application, application.getString(
                        R.string.download_update_local_metadata_done, result.updated, result.skipped),
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onCancelled() {
        if (ownsRunningFlag) {
            RUNNING.set(false);
        }
    }
}
