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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One custom group produced by {@link CustomGroupOrganizer}. */
public class CustomGroupItem {

    /** Stable identity of the group; also used as the key in {@link CustomGroupStore}. */
    @NonNull
    public final String key;

    @Nullable
    public String displayName;

    public int count;

    @NonNull
    public final List<Long> gids = new ArrayList<>();

    public CustomGroupItem(@NonNull String key) {
        this.key = key;
    }

    public CustomGroupItem(@NonNull String key, @Nullable String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public void addGid(long gid) {
        gids.add(gid);
        count = gids.size();
    }

    /**
     * Reorders the members according to {@code order}. Members that the order does not mention keep
     * their previous position and follow the ordered ones, so a newly downloaded gallery is simply
     * appended.
     */
    public void sortGidsBy(@Nullable List<Long> order) {
        if (order == null || order.isEmpty()) {
            return;
        }
        Set<Long> members = new HashSet<>(gids);
        List<Long> sorted = new ArrayList<>(gids.size());
        Set<Long> taken = new HashSet<>(gids.size());
        for (Long gid : order) {
            if (gid != null && members.contains(gid) && taken.add(gid)) {
                sorted.add(gid);
            }
        }
        for (Long gid : gids) {
            if (taken.add(gid)) {
                sorted.add(gid);
            }
        }
        gids.clear();
        gids.addAll(sorted);
        count = gids.size();
    }

    @NonNull
    public List<Long> getGids() {
        return Collections.unmodifiableList(gids);
    }
}
