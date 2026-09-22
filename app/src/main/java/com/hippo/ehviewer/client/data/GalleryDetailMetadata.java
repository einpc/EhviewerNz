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

package com.hippo.ehviewer.client.data;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.unifile.UniFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Persists the part of a {@link GalleryDetail} that the gallery detail page needs into the
 * gallery's own download folder, so the detail page can still be shown after the online gallery
 * has been removed or blocked.
 *
 * <p>Only downloaded galleries have a download folder, so the file is implicitly written for
 * downloaded galleries only.
 */
public final class GalleryDetailMetadata {

    private static final String TAG = GalleryDetailMetadata.class.getSimpleName();

    public static final String FILE_NAME = ".ehviewer_meta.json";

    private static final String TEMP_FILE_NAME = FILE_NAME + ".tmp";

    private static final int VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_SAVED_AT = "savedAt";
    private static final String KEY_DETAIL = "detail";

    private GalleryDetailMetadata() {
    }

    /** A restored detail together with the time it was saved. */
    public static final class Result {

        public final GalleryDetail detail;
        public final long savedAt;

        Result(GalleryDetail detail, long savedAt) {
            this.detail = detail;
            this.savedAt = savedAt;
        }
    }

    /**
     * Writes the metadata only when it differs from the one already stored. The online gallery is
     * always newer than the saved copy, so a difference means the local copy has to be refreshed;
     * an unchanged gallery keeps the previously saved file and its save time.
     */
    public static void writeIfChanged(@NonNull GalleryDetail detail, @Nullable UniFile dir) {
        if (detail.gid <= 0L || dir == null || !dir.isDirectory()) {
            return;
        }
        Result saved = read(dir, detail.gid);
        if (saved != null && sameDetail(saved.detail, detail)) {
            return;
        }
        write(detail, dir);
    }

    /**
     * Writes the metadata of the gallery into its download folder. Does nothing when the gallery
     * has no download folder yet, and never throws.
     */
    public static void write(@NonNull GalleryDetail detail, @Nullable UniFile dir) {
        if (detail.gid <= 0L || dir == null || !dir.isDirectory()) {
            return;
        }

        OutputStream os = null;
        try {
            JSONObject root = new JSONObject();
            root.put(KEY_VERSION, VERSION);
            root.put(KEY_SAVED_AT, System.currentTimeMillis());
            root.put(KEY_DETAIL, buildDetail(detail));

            UniFile temp = dir.findFile(TEMP_FILE_NAME);
            if (temp == null) {
                temp = dir.createFile(TEMP_FILE_NAME);
            }
            if (temp == null) {
                return;
            }
            os = temp.openOutputStream();
            os.write(root.toJSONString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            IOUtils.closeQuietly(os);
            os = null;

            // Replace atomically so a half written file is never read back
            UniFile target = dir.findFile(FILE_NAME);
            if (target != null) {
                target.delete();
            }
            if (!temp.renameTo(FILE_NAME)) {
                temp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save gallery detail metadata of " + detail.gid, e);
        } finally {
            IOUtils.closeQuietly(os);
        }
    }

    /**
     * Reads the metadata saved for the gallery. Returns null when there is no usable metadata.
     *
     * <p>Must not be called on the main thread: resolving the download folder may need to list the
     * download directory, which is slow on SAF.
     */
    @Nullable
    public static Result read(@NonNull GalleryInfo info) {
        if (info.gid <= 0L) {
            return null;
        }
        return read(SpiderDen.getExistingGalleryDownloadDir(info), info.gid);
    }

    @Nullable
    private static Result read(@Nullable UniFile dir, long gid) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        UniFile file = dir.findFile(FILE_NAME);
        if (file == null || !file.isFile()) {
            return null;
        }

        InputStream is = null;
        try {
            is = file.openInputStream();
            JSONObject root = JSONObject.parseObject(IOUtils.readString(is, "UTF-8"));
            if (root == null || root.getIntValue(KEY_VERSION) > VERSION) {
                return null;
            }
            JSONObject detail = root.getJSONObject(KEY_DETAIL);
            if (detail == null || detail.getLongValue("gid") != gid) {
                return null;
            }
            return new Result(parseDetail(detail), root.getLongValue(KEY_SAVED_AT));
        } catch (Exception e) {
            Log.w(TAG, "Failed to read gallery detail metadata of " + gid, e);
            return null;
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    /**
     * Compares the fields that are stored in the file. The online gallery is the live source of
     * truth, so any difference means the saved copy is outdated.
     */
    private static boolean sameDetail(@NonNull GalleryDetail a, @NonNull GalleryDetail b) {
        return a.gid == b.gid
                && TextUtils.equals(a.token, b.token)
                && TextUtils.equals(a.title, b.title)
                && TextUtils.equals(a.titleJpn, b.titleJpn)
                && TextUtils.equals(a.thumb, b.thumb)
                && a.category == b.category
                && TextUtils.equals(a.posted, b.posted)
                && TextUtils.equals(a.uploader, b.uploader)
                && a.rating == b.rating
                && a.rated == b.rated
                && TextUtils.equals(a.simpleLanguage, b.simpleLanguage)
                && Arrays.equals(a.simpleTags, b.simpleTags)
                && a.pages == b.pages
                && Objects.equals(a.firstGid, b.firstGid)
                && TextUtils.equals(a.language, b.language)
                && TextUtils.equals(a.size, b.size)
                && a.favoriteCount == b.favoriteCount
                && a.ratingCount == b.ratingCount
                && a.isFavorited == b.isFavorited
                && a.torrentCount == b.torrentCount
                && TextUtils.equals(a.parent, b.parent)
                && a.SpiderInfoPages == b.SpiderInfoPages
                && sameTags(a.tags, b.tags);
    }

    private static boolean sameTags(@Nullable GalleryTagGroup[] a, @Nullable GalleryTagGroup[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            GalleryTagGroup groupA = a[i];
            GalleryTagGroup groupB = b[i];
            if (groupA == null || groupB == null) {
                if (groupA != groupB) {
                    return false;
                }
                continue;
            }
            if (!TextUtils.equals(groupA.groupName, groupB.groupName)
                    || groupA.size() != groupB.size()) {
                return false;
            }
            for (int j = 0; j < groupA.size(); j++) {
                if (!TextUtils.equals(groupA.getTagAt(j), groupB.getTagAt(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    @NonNull
    private static JSONObject buildDetail(@NonNull GalleryDetail detail) {
        JSONObject object = new JSONObject();
        object.put("gid", detail.gid);
        object.put("token", detail.token);
        object.put("title", detail.title);
        object.put("titleJpn", detail.titleJpn);
        object.put("thumb", detail.thumb);
        object.put("category", detail.category);
        object.put("posted", detail.posted);
        object.put("uploader", detail.uploader);
        object.put("rating", detail.rating);
        object.put("rated", detail.rated);
        object.put("simpleLanguage", detail.simpleLanguage);
        if (detail.simpleTags != null) {
            JSONArray simpleTags = new JSONArray();
            for (String tag : detail.simpleTags) {
                simpleTags.add(tag);
            }
            object.put("simpleTags", simpleTags);
        }
        object.put("pages", detail.pages);
        object.put("firstGid", detail.firstGid);
        object.put("language", detail.language);
        object.put("size", detail.size);
        object.put("favoriteCount", detail.favoriteCount);
        object.put("ratingCount", detail.ratingCount);
        object.put("isFavorited", detail.isFavorited);
        object.put("torrentCount", detail.torrentCount);
        object.put("parent", detail.parent);
        object.put("SpiderInfoPages", detail.SpiderInfoPages);

        JSONArray tags = new JSONArray();
        if (detail.tags != null) {
            for (GalleryTagGroup group : detail.tags) {
                if (group == null) {
                    continue;
                }
                JSONObject tagGroup = new JSONObject();
                tagGroup.put("groupName", group.groupName);
                JSONArray tagList = new JSONArray();
                for (int i = 0; i < group.size(); i++) {
                    tagList.add(group.getTagAt(i));
                }
                tagGroup.put("tagList", tagList);
                tags.add(tagGroup);
            }
        }
        object.put("tags", tags);
        return object;
    }

    @NonNull
    private static GalleryDetail parseDetail(@NonNull JSONObject object) {
        GalleryDetail detail = new GalleryDetail();
        detail.gid = object.getLongValue("gid");
        detail.token = object.getString("token");
        detail.title = object.getString("title");
        detail.titleJpn = object.getString("titleJpn");
        detail.thumb = object.getString("thumb");
        detail.category = object.getIntValue("category");
        detail.posted = object.getString("posted");
        detail.uploader = object.getString("uploader");
        detail.rating = object.getFloatValue("rating");
        detail.rated = object.getBooleanValue("rated");
        detail.simpleLanguage = object.getString("simpleLanguage");
        JSONArray simpleTags = object.getJSONArray("simpleTags");
        if (simpleTags != null) {
            try {
                List<String> tags = simpleTags.toJavaList(String.class);
                detail.simpleTags = tags.toArray(new String[0]);
            } catch (ClassCastException ignore) {
            }
        }
        detail.pages = object.getIntValue("pages");
        detail.firstGid = object.getLong("firstGid");
        detail.language = object.getString("language");
        detail.size = object.getString("size");
        detail.favoriteCount = object.getIntValue("favoriteCount");
        detail.ratingCount = object.getIntValue("ratingCount");
        detail.isFavorited = object.getBooleanValue("isFavorited");
        detail.torrentCount = object.getIntValue("torrentCount");
        detail.parent = object.getString("parent");
        detail.SpiderInfoPages = object.getIntValue("SpiderInfoPages");

        JSONArray tags = object.getJSONArray("tags");
        if (tags != null) {
            List<GalleryTagGroup> groups = new ArrayList<>(tags.size());
            for (int i = 0; i < tags.size(); i++) {
                JSONObject tagGroup = tags.getJSONObject(i);
                if (tagGroup == null) {
                    continue;
                }
                GalleryTagGroup group = new GalleryTagGroup();
                group.groupName = tagGroup.getString("groupName");
                JSONArray tagList = tagGroup.getJSONArray("tagList");
                if (tagList != null) {
                    for (int j = 0; j < tagList.size(); j++) {
                        String tag = tagList.getString(j);
                        if (tag != null) {
                            group.addTag(tag);
                        }
                    }
                }
                groups.add(group);
            }
            detail.tags = groups.toArray(new GalleryTagGroup[0]);
        }

        // Comments need the network, but the detail page requires a non null list
        detail.comments = new GalleryCommentList(new GalleryComment[0], false);
        return detail;
    }
}
