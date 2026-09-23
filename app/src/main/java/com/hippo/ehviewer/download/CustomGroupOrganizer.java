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

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.util.TagTranslationUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies downloaded galleries into custom groups according to {@link CustomGroupConfig}.
 *
 * <p>Classification of a single gallery follows this chain:
 * <ol>
 *     <li>An empty tag set puts the gallery into the fixed "unidentified" group, unless a dimension
 *     the user added still matches it, for example by its uploader.</li>
 *     <li>When the parody dimension is enabled: exactly one parody tag enters its value group,
 *     two or more enter the fixed "doujin" group.</li>
 *     <li>Same for the artist dimension. When both hit, both results are kept.</li>
 *     <li>When neither produced a group, the remaining built-in dimensions are checked in order and
 *     every hit is kept. When nothing hits, the gallery enters the fixed "unknown" group.</li>
 * </ol>
 *
 * <p>On top of that chain, every dimension the user added by hand is always evaluated, so a gallery
 * also enters each of those groups whose tag it carries.
 *
 * <p>Galleries in manual mode ({@link CustomGroupStore#isManual(long)}) skip the chain and only use
 * their recorded manual memberships.
 *
 * <p>{@link #organize} may read the database and the local download folder, so it must be called
 * from a background thread.
 */
public final class CustomGroupOrganizer {

    /** Fixed group entered by a gallery with two or more parody or artist tags. */
    public static final String KEY_DOUJIN = "__doujin__";
    /** Fixed group entered when no enabled dimension produced a group. */
    public static final String KEY_UNKNOWN = "__unknown__";
    /** Fixed group entered when the gallery has no tag at all. */
    public static final String KEY_UNIDENTIFIED = "__unidentified__";
    /**
     * Fixed group entered by a manual gallery that was moved out of every group. Without it such a
     * gallery would not show up anywhere in the grouped browse even though it is still downloaded.
     */
    public static final String KEY_UNGROUPED = "__ungrouped__";

    private static final List<String> FIXED_ORDER =
            Arrays.asList(KEY_DOUJIN, KEY_UNKNOWN, KEY_UNIDENTIFIED, KEY_UNGROUPED);

    /** The categories the drawer folds the custom groups into. */
    public static final int CATEGORY_PARODY = 0;
    public static final int CATEGORY_ARTIST = 1;
    public static final int CATEGORY_UPLOADER = 2;
    public static final int CATEGORY_TAG = 3;
    public static final int CATEGORY_OTHER = 4;

    /** The fixed display order of the categories. */
    public static final int[] CATEGORY_ORDER = {
            CATEGORY_PARODY, CATEGORY_ARTIST, CATEGORY_UPLOADER, CATEGORY_TAG, CATEGORY_OTHER};

    /**
     * The category a group key belongs to. The value groups of the built-in dimensions get their own
     * category, an uploader group belongs to the uploader category and everything else, including the
     * fixed groups, is collected in the last one.
     */
    public static int categoryOf(@Nullable String key) {
        if (key == null || FIXED_ORDER.contains(key)) {
            return CATEGORY_OTHER;
        }
        int separator = key.indexOf(':');
        if (separator > 0) {
            String namespace = key.substring(0, separator);
            if (CustomGroupConfig.PARODY_NAMESPACE.equalsIgnoreCase(namespace)) {
                return CATEGORY_PARODY;
            }
            if (CustomGroupConfig.ARTIST_NAMESPACE.equalsIgnoreCase(namespace)) {
                return CATEGORY_ARTIST;
            }
            if (CustomGroupConfig.UPLOADER_NAMESPACE.equalsIgnoreCase(namespace)) {
                return CATEGORY_UPLOADER;
            }
        }
        return CATEGORY_TAG;
    }

    private CustomGroupOrganizer() {
    }

    /**
     * Builds the ordered group list. Only non empty groups are returned. The persisted order of the
     * store is applied first, then the remaining groups follow in insertion order and the fixed
     * groups that were never reordered end the list in the order doujin, unknown, unidentified.
     */
    @NonNull
    public static List<CustomGroupItem> organize(@Nullable List<DownloadInfo> downloads,
                                                 @NonNull CustomGroupConfig config,
                                                 @NonNull CustomGroupStore store,
                                                 @Nullable Context context) {
        LinkedHashMap<String, CustomGroupItem> groups = new LinkedHashMap<>();
        if (downloads != null) {
            EhTagDatabase tagDatabase = context == null ? null : EhTagDatabase.getInstance(context);
            for (DownloadInfo info : downloads) {
                if (info == null) {
                    continue;
                }
                if (store.isManual(info.gid)) {
                    List<String> manualKeys = store.getManualGroups(info.gid);
                    if (manualKeys.isEmpty()) {
                        addGid(groups, KEY_UNGROUPED, info.gid);
                    } else {
                        for (String key : manualKeys) {
                            addGid(groups, key, info.gid);
                        }
                    }
                    continue;
                }

                ArrayList<String> tags = DownloadTags.resolve(info);
                List<String> keys;
                if (tags.isEmpty()) {
                    // A gallery without any tag still enters the groups the user added by hand, for
                    // example an uploader group. Only when nothing matches it is unidentified.
                    keys = new ArrayList<>();
                    appendUserDimensions(keys, tags, info.getUploader(), config);
                    if (keys.isEmpty()) {
                        addGid(groups, KEY_UNIDENTIFIED, info.gid);
                        continue;
                    }
                } else {
                    keys = classify(tags, info.getUploader(), config);
                    if (keys.isEmpty()) {
                        keys = new ArrayList<>();
                        keys.add(KEY_UNKNOWN);
                    }
                }
                for (String key : keys) {
                    addGid(groups, key, info.gid);
                }
            }

            for (CustomGroupItem item : groups.values()) {
                // A group keeps its own member order once the user reordered it by hand; without a
                // record the order follows the download list.
                item.sortGidsBy(store.getMemberOrder(item.key));
                item.displayName = resolveDisplayName(item.key, config, store, tagDatabase, context);
            }
        }
        return order(groups, store);
    }

    /** Runs the automatic classification chain for a single gallery. */
    @NonNull
    public static List<String> classify(@NonNull List<String> tags, @Nullable String uploader,
                                        @NonNull CustomGroupConfig config) {
        List<String> keys = new ArrayList<>();

        // The built-in chain comes first: the parody and artist dimensions decide, and only when both
        // miss are the remaining built-in dimensions evaluated.
        List<String> primaryKeys = new ArrayList<>();
        if (config.isValueEnabled(CustomGroupConfig.PARODY_NAMESPACE)) {
            appendValueResult(primaryKeys, tags, CustomGroupConfig.PARODY_NAMESPACE);
        }
        if (config.isValueEnabled(CustomGroupConfig.ARTIST_NAMESPACE)) {
            appendValueResult(primaryKeys, tags, CustomGroupConfig.ARTIST_NAMESPACE);
        }
        if (primaryKeys.isEmpty()) {
            for (CustomGroupDimension dimension : config.getDimensions()) {
                if (dimension == null || !dimension.enabled) {
                    continue;
                }
                if (dimension.isBuiltin()) {
                    appendDimensionResult(primaryKeys, tags, uploader, dimension);
                }
            }
        }
        keys.addAll(primaryKeys);

        appendUserDimensions(keys, tags, uploader, config);
        return dedupe(keys);
    }

    /**
     * A dimension the user added always takes part, so a gallery also enters every group whose tag it
     * carries, on top of the built-in result.
     */
    private static void appendUserDimensions(@NonNull List<String> keys, @NonNull List<String> tags,
                                             @Nullable String uploader,
                                             @NonNull CustomGroupConfig config) {
        for (CustomGroupDimension dimension : config.getDimensions()) {
            if (dimension == null || !dimension.enabled || dimension.isBuiltin()) {
                continue;
            }
            appendDimensionResult(keys, tags, uploader, dimension);
        }
    }

    private static void appendDimensionResult(@NonNull List<String> keys, @NonNull List<String> tags,
                                              @Nullable String uploader,
                                              @NonNull CustomGroupDimension dimension) {
        if (dimension.isValue()) {
            appendValueResult(keys, tags, dimension.namespace);
        } else if (dimension.isMatch() && matchesDimension(tags, uploader, dimension.tag)) {
            keys.add(matchKey(dimension.tag));
        }
    }

    /**
     * Whether the gallery hits a match dimension. An uploader tag is compared against the uploader of
     * the gallery instead of its tag set.
     */
    private static boolean matchesDimension(@NonNull List<String> tags, @Nullable String uploader,
                                            @Nullable String target) {
        if (CustomGroupConfig.uploaderOf(target) != null) {
            return CustomGroupConfig.matchesUploader(target, uploader);
        }
        return DownloadTags.containsTag(tags, target);
    }

    private static void appendValueResult(@NonNull List<String> keys, @NonNull List<String> tags,
                                          @Nullable String namespace) {
        List<String> values = DownloadTags.valuesOf(tags, namespace);
        if (values.isEmpty()) {
            return;
        }
        if (values.size() == 1) {
            keys.add(valueKey(namespace, values.get(0)));
        } else {
            keys.add(KEY_DOUJIN);
        }
    }

    /** The group key of a value dimension. */
    @NonNull
    public static String valueKey(@Nullable String namespace, @NonNull String value) {
        String ns = namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
        return ns + ":" + value.toLowerCase(Locale.ROOT);
    }

    /** The group key of a match dimension. */
    @NonNull
    public static String matchKey(@Nullable String tag) {
        return tag == null ? "" : tag.toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static List<String> dedupe(@NonNull List<String> keys) {
        List<String> result = new ArrayList<>(keys.size());
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            if (key != null && seen.add(key)) {
                result.add(key);
            }
        }
        return result;
    }

    @Nullable
    private static String resolveDisplayName(@NonNull String key, @NonNull CustomGroupConfig config,
                                             @NonNull CustomGroupStore store,
                                             @Nullable EhTagDatabase tagDatabase,
                                             @Nullable Context context) {
        String override = store.getNameOverride(key);
        if (override != null && !override.isEmpty()) {
            return override;
        }

        if (KEY_DOUJIN.equals(key)) {
            return getString(context, R.string.custom_group_doujin);
        }
        if (KEY_UNKNOWN.equals(key)) {
            return getString(context, R.string.custom_group_unknown);
        }
        if (KEY_UNIDENTIFIED.equals(key)) {
            return getString(context, R.string.custom_group_unidentified);
        }
        if (KEY_UNGROUPED.equals(key)) {
            return getString(context, R.string.custom_group_ungrouped);
        }

        CustomGroupDimension matchDimension = findMatchDimension(config, key);
        if (matchDimension != null) {
            if (matchDimension.name != null && !matchDimension.name.isEmpty()) {
                return matchDimension.name;
            }
            String uploader = CustomGroupConfig.uploaderOf(matchDimension.tag);
            if (uploader != null) {
                return getString(context, R.string.custom_group_uploader_group_name, uploader);
            }
            int builtin = builtinMatchNameRes(matchDimension.tag);
            if (builtin != 0) {
                return getString(context, builtin);
            }
            // A user dimension without a name still prefers the translated tag over the raw one.
            String translated = TagTranslationUtil.getTagCNBody(matchDimension.tag, tagDatabase);
            return translated != null ? translated : matchDimension.tag;
        }

        int separator = key.indexOf(':');
        if (separator > 0 && separator < key.length() - 1) {
            String value = key.substring(separator + 1);
            String translated = TagTranslationUtil.getTagCNBody(key, tagDatabase);
            return translated != null ? translated : value;
        }
        return key;
    }

    @Nullable
    private static CustomGroupDimension findMatchDimension(@NonNull CustomGroupConfig config,
                                                           @NonNull String key) {
        for (CustomGroupDimension dimension : config.getDimensions()) {
            if (dimension != null && dimension.isMatch() && key.equals(matchKey(dimension.tag))) {
                return dimension;
            }
        }
        return null;
    }

    private static int builtinMatchNameRes(@Nullable String tag) {
        if (tag == null) {
            return 0;
        }
        if (tag.equalsIgnoreCase(CustomGroupConfig.AI_GENERATED_TAG)) {
            return R.string.custom_group_ai_generated;
        }
        if (tag.equalsIgnoreCase(CustomGroupConfig.ANIMATED_TAG)) {
            return R.string.custom_group_animated;
        }
        return 0;
    }

    @Nullable
    private static String getString(@Nullable Context context, int resId, Object... formatArgs) {
        return context == null ? null : context.getString(resId, formatArgs);
    }

    @NonNull
    private static List<CustomGroupItem> order(@NonNull Map<String, CustomGroupItem> groups,
                                               @NonNull CustomGroupStore store) {
        List<CustomGroupItem> result = new ArrayList<>(groups.size());
        Set<String> emitted = new HashSet<>();

        for (String key : store.getGroupOrder()) {
            CustomGroupItem item = groups.get(key);
            if (item != null && emitted.add(key)) {
                result.add(item);
            }
        }
        for (Map.Entry<String, CustomGroupItem> entry : groups.entrySet()) {
            if (FIXED_ORDER.contains(entry.getKey())) {
                continue;
            }
            if (emitted.add(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        for (String fixed : FIXED_ORDER) {
            CustomGroupItem item = groups.get(fixed);
            // A fixed group that was already placed by the persisted order must not be added twice.
            if (item != null && emitted.add(fixed)) {
                result.add(item);
            }
        }
        return result;
    }

    private static void addGid(@NonNull Map<String, CustomGroupItem> groups,
                               @Nullable String key, long gid) {
        if (key == null || key.isEmpty()) {
            return;
        }
        CustomGroupItem item = groups.get(key);
        if (item == null) {
            item = new CustomGroupItem(key);
            groups.put(key, item);
        }
        item.addGid(gid);
    }
}
