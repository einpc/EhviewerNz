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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryDetailMetadata;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.GalleryTags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the tag set of a downloaded gallery from all available sources.
 *
 * <p>The sources are merged by priority and the result is cached back into
 * {@link DownloadInfo#tgList} so that later calls are cheap:
 * <ol>
 *     <li>the cached {@link DownloadInfo#tgList}</li>
 *     <li>{@link DownloadInfo#simpleTags}</li>
 *     <li>the tags stored in the database ({@link EhDB#queryGalleryTags(long)})</li>
 *     <li>the {@code .ehviewer_meta.json} saved next to the downloaded images, only for finished
 *     downloads that have no other tags</li>
 * </ol>
 *
 * <p>An empty result is cached as well. Reading the database or the local metadata is far more
 * expensive than the lookup itself, and the download list can hold thousands of galleries, so a
 * gallery without tags must not be re-read on every recomputation. {@link #invalidate} drops the
 * cache when the stored tags actually changed.
 *
 * <p>Reading the database or the local metadata may touch slow storage, so {@link #resolve} must be
 * called from a background thread.
 */
public final class DownloadTags {

    private DownloadTags() {
    }

    /**
     * Returns the tag set of the gallery, or an empty list when no tag is available. The returned
     * list is also stored in {@link DownloadInfo#tgList} as a cache.
     */
    @NonNull
    public static ArrayList<String> resolve(@Nullable DownloadInfo info) {
        if (info == null) {
            return new ArrayList<>();
        }

        if (info.tgList != null) {
            return info.tgList;
        }

        ArrayList<String> tagList = new ArrayList<>();
        if (info.simpleTags != null) {
            for (String tag : info.simpleTags) {
                if (tag != null && !tag.isEmpty()) {
                    tagList.add(tag);
                }
            }
        }

        if (tagList.isEmpty()) {
            ArrayList<String> dbTags = queryStoredTags(info.gid);
            if (dbTags != null && !dbTags.isEmpty()) {
                tagList.addAll(dbTags);
            }
        }

        // Only a finished download has images and therefore a folder with saved metadata.
        if (tagList.isEmpty() && info.state == DownloadInfo.STATE_FINISH) {
            addLocalMetadataTags(info, tagList);
        }

        info.tgList = tagList;
        return tagList;
    }

    /** Drops the cached tag set, so the next {@link #resolve} reads the sources again. */
    public static void invalidate(@Nullable DownloadInfo info) {
        if (info != null) {
            info.tgList = null;
        }
    }

    /** Whether any tag of the collection matches the target. */
    public static boolean containsTag(@Nullable Collection<String> tags, @Nullable String target) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (matchesTag(tag, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a single tag matches the target. The comparison is case insensitive and supports both
     * an exact {@code namespace:value} match and a contains match on the value only.
     */
    public static boolean matchesTag(@Nullable String tag, @Nullable String target) {
        if (tag == null || target == null) {
            return false;
        }

        String normalizedTag = tag.trim().toLowerCase(Locale.ROOT);
        String normalizedTarget = target.trim().toLowerCase(Locale.ROOT);
        if (normalizedTag.isEmpty() || normalizedTarget.isEmpty()) {
            return false;
        }

        int tagIndex = normalizedTag.indexOf(':');
        String tagNamespace = tagIndex >= 0 ? normalizedTag.substring(0, tagIndex) : null;
        String tagName = tagIndex >= 0 ? normalizedTag.substring(tagIndex + 1) : normalizedTag;

        int targetIndex = normalizedTarget.indexOf(':');
        String targetNamespace = targetIndex >= 0 ? normalizedTarget.substring(0, targetIndex) : null;
        String targetName = targetIndex >= 0 ? normalizedTarget.substring(targetIndex + 1) : normalizedTarget;

        if (targetNamespace != null && (tagNamespace == null || !tagNamespace.equals(targetNamespace))) {
            return false;
        }

        if (targetName.isEmpty()) {
            return false;
        }

        if (tagName.equals(targetName)) {
            return true;
        }

        // A search hint is "keyword", so allow a contains match on the tag name.
        return tagName.contains(targetName);
    }

    /**
     * Returns the tag values of the namespace, without the {@code namespace:} prefix. Duplicates are
     * removed and the original spelling is preserved.
     */
    @NonNull
    public static List<String> valuesOf(@Nullable Collection<String> tags, @Nullable String namespace) {
        List<String> values = new ArrayList<>();
        if (tags == null || namespace == null) {
            return values;
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            int separator = tag.indexOf(':');
            if (separator <= 0 || separator == tag.length() - 1) {
                continue;
            }
            if (!namespace.equalsIgnoreCase(tag.substring(0, separator).trim())) {
                continue;
            }
            String value = tag.substring(separator + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (String existing : values) {
                if (existing.equalsIgnoreCase(value)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                values.add(value);
            }
        }
        return values;
    }

    @Nullable
    private static ArrayList<String> queryStoredTags(long gid) {
        GalleryTags tags = EhDB.queryGalleryTags(gid);
        if (tags == null) {
            return null;
        }

        ArrayList<String> tagList = new ArrayList<>();
        tagList.addAll(parseTagColumn("artist", tags.artist));
        tagList.addAll(parseTagColumn("rows", tags.rows));
        tagList.addAll(parseTagColumn("cosplayer", tags.cosplayer));
        tagList.addAll(parseTagColumn("character", tags.character));
        tagList.addAll(parseTagColumn("female", tags.female));
        tagList.addAll(parseTagColumn("group", tags.group));
        tagList.addAll(parseTagColumn("language", tags.language));
        tagList.addAll(parseTagColumn("male", tags.male));
        tagList.addAll(parseTagColumn("misc", tags.misc));
        tagList.addAll(parseTagColumn("mixed", tags.mixed));
        tagList.addAll(parseTagColumn("other", tags.other));
        tagList.addAll(parseTagColumn("parody", tags.parody));
        tagList.addAll(parseTagColumn("reclass", tags.reclass));
        return tagList;
    }

    @NonNull
    private static ArrayList<String> parseTagColumn(@Nullable String namespace, @Nullable String content) {
        if (namespace == null || content == null) {
            return new ArrayList<>();
        }
        ArrayList<String> list = new ArrayList<>();
        for (String raw : content.split(",")) {
            String value = raw == null ? null : raw.trim();
            if (value == null || value.isEmpty()) {
                continue;
            }
            list.add(namespace + ":" + value);
        }
        return list;
    }

    /**
     * Falls back to the tags saved next to the downloaded images. The download entry itself does not
     * keep its tags, so without this a downloaded gallery could only be found by its title.
     *
     * <p>The saved detail is parsed from the gallery page, which fills the tag groups but not
     * {@code simpleTags}, so the groups are used whenever there is no simple tag set.
     */
    private static void addLocalMetadataTags(@NonNull DownloadInfo info, @NonNull ArrayList<String> tagList) {
        GalleryDetailMetadata.Result result = GalleryDetailMetadata.read(info);
        if (result == null || result.detail == null) {
            return;
        }

        GalleryDetail detail = result.detail;
        if (detail.simpleTags != null && detail.simpleTags.length > 0) {
            for (String tag : detail.simpleTags) {
                if (tag != null && !tag.isEmpty()) {
                    tagList.add(tag);
                }
            }
            return;
        }

        if (detail.tags == null) {
            return;
        }
        for (GalleryTagGroup group : detail.tags) {
            if (group == null || group.groupName == null || group.groupName.isEmpty()) {
                continue;
            }
            for (int i = 0, size = group.size(); i < size; i++) {
                String tag = group.getTagAt(i);
                if (tag != null && !tag.isEmpty()) {
                    tagList.add(group.groupName + ":" + tag);
                }
            }
        }
    }
}
