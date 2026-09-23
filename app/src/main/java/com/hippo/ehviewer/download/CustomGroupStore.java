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
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User owned state of the custom groups, persisted as JSON in {@link Settings}.
 *
 * <p>It stores the group order, manual display name overrides, manually assigned memberships and
 * the set of galleries that are in "manual mode" and therefore skip automatic classification. This
 * is fully independent from the existing download labels, so no database migration is needed.
 */
public class CustomGroupStore {

    /** Settings key holding the serialized state. */
    public static final String KEY = "custom_group_store";

    private static final String FIELD_ORDER = "order";
    private static final String FIELD_NAMES = "names";
    private static final String FIELD_MANUAL = "manual";
    private static final String FIELD_MANUAL_GIDS = "manualGids";
    private static final String FIELD_MEMBER_ORDER = "memberOrder";
    private static final String FIELD_EXPANDED_CATEGORIES = "expandedCategories";

    private final List<String> groupOrder = new ArrayList<>();
    private final Map<String, String> nameOverrides = new LinkedHashMap<>();
    private final Map<Long, List<String>> manualGroups = new LinkedHashMap<>();
    private final Set<Long> manualGids = new LinkedHashSet<>();
    /** Member order of a group, recorded when the user reorders the galleries inside it. */
    private final Map<String, List<Long>> memberOrder = new LinkedHashMap<>();
    /**
     * Categories the user expanded in the drawer. Every category starts collapsed, so the set only
     * holds the ones that were opened by hand.
     */
    private final Set<Integer> expandedCategories = new LinkedHashSet<>();

    /** Loads the store, returning an empty one when nothing has been stored yet. */
    @NonNull
    public static CustomGroupStore load(@Nullable Context context) {
        CustomGroupStore store = new CustomGroupStore();
        String json = Settings.getString(KEY, null);
        if (json == null || json.isEmpty()) {
            return store;
        }
        try {
            JSONObject root = JSON.parseObject(json);
            if (root == null) {
                return store;
            }

            JSONArray order = root.getJSONArray(FIELD_ORDER);
            if (order != null) {
                for (int i = 0; i < order.size(); i++) {
                    String key = order.getString(i);
                    if (key != null && !key.isEmpty()) {
                        store.groupOrder.add(key);
                    }
                }
            }

            JSONObject names = root.getJSONObject(FIELD_NAMES);
            if (names != null) {
                for (String key : names.keySet()) {
                    String value = names.getString(key);
                    if (key != null && !key.isEmpty() && value != null) {
                        store.nameOverrides.put(key, value);
                    }
                }
            }

            JSONObject manual = root.getJSONObject(FIELD_MANUAL);
            if (manual != null) {
                for (String key : manual.keySet()) {
                    long gid = parseGid(key);
                    if (gid < 0L) {
                        continue;
                    }
                    List<String> keys = new ArrayList<>();
                    JSONArray array = manual.getJSONArray(key);
                    if (array != null) {
                        for (int i = 0; i < array.size(); i++) {
                            String groupKey = array.getString(i);
                            if (groupKey != null && !groupKey.isEmpty()) {
                                keys.add(groupKey);
                            }
                        }
                    }
                    store.manualGroups.put(gid, keys);
                }
            }

            JSONArray manualGids = root.getJSONArray(FIELD_MANUAL_GIDS);
            if (manualGids != null) {
                for (int i = 0; i < manualGids.size(); i++) {
                    Long gid = manualGids.getLong(i);
                    if (gid != null && gid >= 0L) {
                        store.manualGids.add(gid);
                    }
                }
            }

            JSONObject members = root.getJSONObject(FIELD_MEMBER_ORDER);
            if (members != null) {
                for (String key : members.keySet()) {
                    JSONArray array = members.getJSONArray(key);
                    if (key == null || key.isEmpty() || array == null) {
                        continue;
                    }
                    List<Long> gids = new ArrayList<>(array.size());
                    for (int i = 0; i < array.size(); i++) {
                        Long gid = array.getLong(i);
                        if (gid != null && gid >= 0L) {
                            gids.add(gid);
                        }
                    }
                    if (!gids.isEmpty()) {
                        store.memberOrder.put(key, gids);
                    }
                }
            }
            // A gallery with manual memberships is implicitly in manual mode.
            store.manualGids.addAll(store.manualGroups.keySet());

            JSONArray expanded = root.getJSONArray(FIELD_EXPANDED_CATEGORIES);
            if (expanded != null) {
                for (int i = 0; i < expanded.size(); i++) {
                    Integer category = expanded.getInteger(i);
                    if (category != null) {
                        store.expandedCategories.add(category);
                    }
                }
            }
        } catch (Exception ignore) {
            return new CustomGroupStore();
        }
        return store;
    }

    /** Persists the current state. */
    public void save() {
        JSONObject root = new JSONObject();

        JSONArray order = new JSONArray();
        order.addAll(groupOrder);
        root.put(FIELD_ORDER, order);

        JSONObject names = new JSONObject();
        for (Map.Entry<String, String> entry : nameOverrides.entrySet()) {
            names.put(entry.getKey(), entry.getValue());
        }
        root.put(FIELD_NAMES, names);

        JSONObject manual = new JSONObject();
        for (Map.Entry<Long, List<String>> entry : manualGroups.entrySet()) {
            JSONArray array = new JSONArray();
            array.addAll(entry.getValue());
            manual.put(String.valueOf(entry.getKey()), array);
        }
        root.put(FIELD_MANUAL, manual);

        JSONArray manualGids = new JSONArray();
        manualGids.addAll(this.manualGids);
        root.put(FIELD_MANUAL_GIDS, manualGids);

        JSONObject members = new JSONObject();
        for (Map.Entry<String, List<Long>> entry : memberOrder.entrySet()) {
            JSONArray array = new JSONArray();
            array.addAll(entry.getValue());
            members.put(entry.getKey(), array);
        }
        root.put(FIELD_MEMBER_ORDER, members);

        JSONArray expanded = new JSONArray();
        expanded.addAll(expandedCategories);
        root.put(FIELD_EXPANDED_CATEGORIES, expanded);

        Settings.putString(KEY, root.toJSONString());
    }

    /** Whether the category is expanded in the drawer. Every category starts collapsed. */
    public boolean isCategoryExpanded(int category) {
        return expandedCategories.contains(category);
    }

    /** Records the expand state of a category. Returns true when it changed. */
    public boolean setCategoryExpanded(int category, boolean expanded) {
        return expanded ? expandedCategories.add(category) : expandedCategories.remove(category);
    }

    @NonNull
    public List<String> getGroupOrder() {
        return new ArrayList<>(groupOrder);
    }

    public void setGroupOrder(@Nullable List<String> keys) {
        groupOrder.clear();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.isEmpty() && !groupOrder.contains(key)) {
                    groupOrder.add(key);
                }
            }
        }
    }

    /** Appends the key to the order when it is not recorded yet. */
    public void recordGroupOrder(@Nullable String key) {
        if (key != null && !key.isEmpty() && !groupOrder.contains(key)) {
            groupOrder.add(key);
        }
    }

    @Nullable
    public String getNameOverride(@Nullable String key) {
        return key == null ? null : nameOverrides.get(key);
    }

    public void setNameOverride(@Nullable String key, @Nullable String name) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (name == null || name.isEmpty()) {
            nameOverrides.remove(key);
        } else {
            nameOverrides.put(key, name);
        }
    }

    public void clearNameOverride(@Nullable String key) {
        if (key != null) {
            nameOverrides.remove(key);
        }
    }

    /** Whether the gallery skips automatic classification and only uses manual memberships. */
    public boolean isManual(long gid) {
        return manualGids.contains(gid);
    }

    /** Marks the gallery as manual. Existing manual memberships are kept. */
    public void setManual(long gid) {
        manualGids.add(gid);
    }

    /** Removes the gallery from manual mode and drops all its manual memberships. */
    public void clearManual(long gid) {
        manualGids.remove(gid);
        manualGroups.remove(gid);
    }

    @NonNull
    public Set<Long> getManualGids() {
        return new LinkedHashSet<>(manualGids);
    }

    @NonNull
    public List<String> getManualGroups(long gid) {
        List<String> keys = manualGroups.get(gid);
        return keys == null ? new ArrayList<>() : new ArrayList<>(keys);
    }

    /** Adds the gallery to the group and marks it as manual. */
    public void addToGroup(long gid, @Nullable String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        List<String> keys = manualGroups.get(gid);
        if (keys == null) {
            keys = new ArrayList<>();
            manualGroups.put(gid, keys);
        }
        if (!keys.contains(key)) {
            keys.add(key);
        }
        manualGids.add(gid);
    }

    /** Removes the gallery from the group. The gallery stays in manual mode. */
    public void removeFromGroup(long gid, @Nullable String key) {
        if (key == null) {
            return;
        }
        List<String> keys = manualGroups.get(gid);
        if (keys == null) {
            return;
        }
        keys.remove(key);
        if (keys.isEmpty()) {
            manualGroups.remove(gid);
        }
    }

    /**
     * Drops a group completely: its manual memberships, its recorded order and its name override. It
     * is used when the dimension that produced the group is removed, otherwise the recorded
     * memberships would keep the group alive.
     */
    public void purgeGroup(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Long, List<String>>> iterator = manualGroups.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, List<String>> entry = iterator.next();
            entry.getValue().remove(key);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        groupOrder.remove(key);
        nameOverrides.remove(key);
        memberOrder.remove(key);
    }

    /** The recorded member order of the group, or an empty list when it was never reordered. */
    @NonNull
    public List<Long> getMemberOrder(@Nullable String key) {
        List<Long> order = key == null ? null : memberOrder.get(key);
        return order == null ? new ArrayList<>() : new ArrayList<>(order);
    }

    /** Records the member order of the group. An empty list clears the record. */
    public void setMemberOrder(@Nullable String key, @Nullable List<Long> gids) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (gids == null || gids.isEmpty()) {
            memberOrder.remove(key);
            return;
        }
        memberOrder.put(key, new ArrayList<>(gids));
    }

    /** Every group key that has a manual membership. */
    @NonNull
    public Set<String> getManualGroupKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (List<String> values : manualGroups.values()) {
            keys.addAll(values);
        }
        return Collections.unmodifiableSet(keys);
    }

    private static long parseGid(@Nullable String value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
