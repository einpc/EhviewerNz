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

import androidx.annotation.NonNull;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryDetailMetadata;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.util.IoThreadPoolExecutor;

/**
 * Fetches the gallery detail once and stores it next to the downloaded images, so the detail page
 * can still be opened after the online gallery has been removed or blocked. Failures are ignored,
 * the previously saved metadata stays in place.
 */
public final class GalleryDetailMetadataTask {

    private final GalleryInfo gallery;
    private final EhClient client;

    public GalleryDetailMetadataTask(@NonNull Context context, @NonNull GalleryInfo gallery) {
        this.gallery = gallery;
        client = EhApplication.getEhClient(context);
    }

    public void start() {
        if (gallery.gid <= 0L || gallery.token == null || gallery.token.isEmpty()) {
            return;
        }
        client.execute(new EhRequest()
                .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
                .setArgs(EhUrl.getGalleryDetailUrl(gallery.gid, gallery.token))
                .setCallback(new EhClient.Callback<GalleryDetail>() {
                    @Override
                    public void onSuccess(GalleryDetail result) {
                        save(result);
                    }

                    @Override
                    public void onFailure(Exception e) {
                    }

                    @Override
                    public void onCancel() {
                    }
                }));
    }

    private void save(@NonNull GalleryDetail detail) {
        // Callbacks run on the main thread, resolving the download folder and writing must not
        IoThreadPoolExecutor.Companion.getInstance().execute(() ->
                GalleryDetailMetadata.writeIfChanged(
                        detail, SpiderDen.getExistingGalleryDownloadDir(gallery)));
    }
}
