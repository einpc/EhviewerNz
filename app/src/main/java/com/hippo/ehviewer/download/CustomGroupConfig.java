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
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.hippo.ehviewer.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered list of {@link CustomGroupDimension} used to classify downloads into custom groups.
 *
 * <p>The configuration is persisted as JSON in {@link Settings} and is independent from the existing
 * download labels, so no database migration is needed.
 */
public class CustomGroupConfig {

    /** Settings key holding the serialized dimensions. */
    public static final String KEY = "custom_group_dimensions";

    public static final String PARODY_NAMESPACE = "parody";
    public static final String ARTIST_NAMESPACE = "artist";
    public static final String AI_GENERATED_TAG = "other:ai generated";
    public static final String ANIMATED_TAG = "other:animated";
    /**
     * The pseudo namespace used to group downloads by uploader. An uploader is not a tag, but the
     * grouping treats it as one, so {@code uploader:<name>} can be used wherever a tag is expected.
     */
    public static final String UPLOADER_NAMESPACE = "uploader";

    private final List<CustomGroupDimension> dimensions = new ArrayList<>();

    /** Loads the configuration, falling back to the built-in defaults when nothing is stored yet. */
    @NonNull
    public static CustomGroupConfig load(@Nullable Context context) {
        CustomGroupConfig config = new CustomGroupConfig();
        String json = Settings.getString(KEY, null);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray array = JSON.parseArray(json);
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        CustomGroupDimension dimension =
                                CustomGroupDimension.fromJson(array.getJSONObject(i));
                        if (dimension != null) {
                            config.dimensions.add(dimension);
                        }
                    }
                }
            } catch (Exception ignore) {
                config.dimensions.clear();
            }
        }
        if (config.dimensions.isEmpty()) {
            config.dimensions.addAll(defaultDimensions());
        }
        return config;
    }

    /** The built-in dimensions: parody, artist, AI generated and animated, all enabled. */
    @NonNull
    public static List<CustomGroupDimension> defaultDimensions() {
        List<CustomGroupDimension> defaults = new ArrayList<>();
        defaults.add(CustomGroupDimension.value(PARODY_NAMESPACE, true));
        defaults.add(CustomGroupDimension.value(ARTIST_NAMESPACE, true));
        defaults.add(CustomGroupDimension.match(AI_GENERATED_TAG, null, true));
        defaults.add(CustomGroupDimension.match(ANIMATED_TAG, null, true));
        return defaults;
    }

    /** Persists the current dimensions. */
    public void save() {
        JSONArray array = new JSONArray();
        for (CustomGroupDimension dimension : dimensions) {
            array.add(dimension.toJson());
        }
        Settings.putString(KEY, array.toJSONString());
    }

    @NonNull
    public List<CustomGroupDimension> getDimensions() {
        return dimensions;
    }

    public int size() {
        return dimensions.size();
    }

    @Nullable
    public CustomGroupDimension getDimension(int index) {
        return index >= 0 && index < dimensions.size() ? dimensions.get(index) : null;
    }

    /** Whether the dimension is enabled. */
    public static boolean isEnabled(@Nullable CustomGroupDimension dimension) {
        return dimension != null && dimension.enabled;
    }

    /** Whether the {@link CustomGroupDimension.Kind#VALUE} dimension of the namespace is enabled. */
    public boolean isValueEnabled(@NonNull String namespace) {
        for (CustomGroupDimension dimension : dimensions) {
            if (dimension.isValue() && namespace.equalsIgnoreCase(dimension.namespace)) {
                return dimension.enabled;
            }
        }
        return false;
    }

    /** Whether the {@link CustomGroupDimension.Kind#MATCH} dimension of the tag is enabled. */
    public boolean isMatchEnabled(@NonNull String tag) {
        for (CustomGroupDimension dimension : dimensions) {
            if (dimension.isMatch() && tag.equalsIgnoreCase(dimension.tag)) {
                return dimension.enabled;
            }
        }
        return false;
    }

    public void setEnabled(int index, boolean enabled) {
        CustomGroupDimension dimension = getDimension(index);
        if (dimension != null) {
            dimension.enabled = enabled;
        }
    }

    /** Moves the dimension at {@code from} to {@code to}. Returns true when it moved. */
    public boolean reorder(int from, int to) {
        if (from < 0 || from >= dimensions.size() || to < 0 || to >= dimensions.size() || from == to) {
            return false;
        }
        CustomGroupDimension dimension = dimensions.remove(from);
        dimensions.add(to, dimension);
        return true;
    }

    /** Replaces the whole dimension list with copies of the given dimensions. */
    public void setDimensions(@Nullable List<CustomGroupDimension> newDimensions) {
        dimensions.clear();
        if (newDimensions != null) {
            for (CustomGroupDimension dimension : newDimensions) {
                if (dimension != null) {
                    dimensions.add(dimension.copy());
                }
            }
        }
    }

    /** Whether a dimension with the same namespace or tag is already configured. */
    public boolean containsDimension(@Nullable CustomGroupDimension dimension) {
        if (dimension == null) {
            return false;
        }
        for (CustomGroupDimension existing : dimensions) {
            if (dimension.isValue() && existing.isValue()
                    && dimension.namespace != null
                    && dimension.namespace.equalsIgnoreCase(existing.namespace)) {
                return true;
            }
            if (dimension.isMatch() && existing.isMatch()
                    && dimension.tag != null && dimension.tag.equalsIgnoreCase(existing.tag)) {
                return true;
            }
        }
        return false;
    }

    /** Appends a user dimension. Returns false when it is a duplicate. */
    public boolean addDimension(@Nullable CustomGroupDimension dimension) {
        if (dimension == null || containsDimension(dimension)) {
            return false;
        }
        dimensions.add(dimension);
        return true;
    }

    /** Removes a user dimension. Built-in dimensions are kept. Returns true when it was removed. */
    public boolean removeDimension(int index) {
        CustomGroupDimension dimension = getDimension(index);
        if (dimension == null || dimension.isBuiltin()) {
            return false;
        }
        dimensions.remove(index);
        return true;
    }

    /** Restores the built-in default dimensions. */
    public void resetToDefault() {
        setDimensions(defaultDimensions());
    }

    /** Whether the gallery tag hits the {@link CustomGroupDimension.Kind#MATCH} dimension. */
    public static boolean matchesTag(@Nullable CustomGroupDimension dimension, @Nullable String tag) {
        if (dimension == null || !dimension.isMatch()) {
            return false;
        }
        return DownloadTags.matchesTag(tag, dimension.tag);
    }

    /** The group tag of an uploader, i.e. {@code uploader:<name>}. */
    @NonNull
    public static String uploaderTag(@Nullable String uploader) {
        return UPLOADER_NAMESPACE + ":" + (uploader == null ? "" : uploader.trim());
    }

    /**
     * The uploader of an {@link #uploaderTag(String)}, or null when the tag does not use the
     * uploader namespace.
     */
    @Nullable
    public static String uploaderOf(@Nullable String tag) {
        if (tag == null) {
            return null;
        }
        int separator = tag.indexOf(':');
        if (separator <= 0
                || !UPLOADER_NAMESPACE.equalsIgnoreCase(tag.substring(0, separator).trim())) {
            return null;
        }
        String value = tag.substring(separator + 1).trim();
        return value.isEmpty() ? null : value;
    }

    /** Whether an uploader tag points at the given uploader. The comparison ignores case. */
    public static boolean matchesUploader(@Nullable String tag, @Nullable String uploader) {
        String target = uploaderOf(tag);
        return target != null && uploader != null && target.equalsIgnoreCase(uploader.trim());
    }
}
