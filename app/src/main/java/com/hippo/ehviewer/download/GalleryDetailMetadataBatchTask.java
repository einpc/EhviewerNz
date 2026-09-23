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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

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
 *
 * <p>Runs in the background and reports its progress through a notification, so the user can keep
 * reading galleries while it works.
 */
public final class GalleryDetailMetadataBatchTask
        extends AsyncTask<Void, int[], GalleryDetailMetadataBatchTask.Result> {

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static final int NOTIFICATION_ID = 0x4d455441;
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
    private final List<DownloadInfo> pending = new ArrayList<>();
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private boolean ownsRunningFlag;

    public GalleryDetailMetadataBatchTask(@NonNull Context context) {
        application = context.getApplicationContext();
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

    @Override
    protected void onPreExecute() {
        ownsRunningFlag = RUNNING.compareAndSet(false, true);
        if (!ownsRunningFlag) {
            cancel(false);
            return;
        }
        showProgressNotification(0);
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
                    // The stored tags changed, so the cached tag set of the gallery is stale.
                    DownloadTags.invalidate(info);
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
        if (values.length != 0) {
            showProgressNotification(values[0][0]);
        }
    }

    @Override
    protected void onPostExecute(Result result) {
        if (ownsRunningFlag) {
            RUNNING.set(false);
        }
        String resultText = application.getString(
                R.string.download_update_local_metadata_done, result.updated, result.skipped);
        showCompletedNotification(resultText);
        Toast.makeText(application, resultText, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onCancelled() {
        if (ownsRunningFlag) {
            RUNNING.set(false);
        }
        cancelNotification();
    }

    private void showProgressNotification(int current) {
        ensureNotificationBuilder();
        if (notificationBuilder == null || notificationManager == null) {
            return;
        }
        int total = pending.size();
        notificationBuilder
                .setContentText(application.getString(
                        R.string.download_update_local_metadata_progress, current, total))
                .setContentInfo(current + "/" + total)
                .setProgress(total, current, total == 0);
        notifySafely();
    }

    private void showCompletedNotification(@NonNull String resultText) {
        ensureNotificationBuilder();
        if (notificationBuilder == null || notificationManager == null) {
            return;
        }
        notificationBuilder
                .setContentText(resultText)
                .setContentInfo(null)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS);
        notifySafely();
    }

    private void ensureNotificationBuilder() {
        if (notificationBuilder != null) {
            return;
        }
        notificationManager = (NotificationManager) application.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
        String channelId = application.getPackageName() + ".gallery_detail_metadata";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId,
                    application.getString(R.string.download_update_local_metadata),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(application.getString(
                    R.string.download_update_local_metadata_message));
            channel.enableVibration(false);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
        notificationBuilder = new NotificationCompat.Builder(application, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(application.getString(
                        R.string.download_update_local_metadata))
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setColor(ContextCompat.getColor(application, R.color.colorPrimary));
    }

    private void notifySafely() {
        try {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        } catch (RuntimeException ignored) {
            // Notification permission may be denied; the update should continue regardless.
        }
    }

    private void cancelNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }
}
