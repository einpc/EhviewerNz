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

import com.alibaba.fastjson.JSONObject;

/**
 * One dimension used to classify downloaded galleries into custom groups.
 *
 * <p>A {@link Kind#VALUE} dimension creates one group per distinct tag value of its namespace
 * (for example every {@code parody} value becomes its own group). A {@link Kind#MATCH} dimension
 * creates a single group that every gallery carrying its {@link #tag} enters.
 */
public class CustomGroupDimension {

    /** How a dimension maps galleries to groups. */
    public enum Kind {
        /** One group per distinct tag value of {@link #namespace}. */
        VALUE,
        /** One group entered by any gallery that carries {@link #tag}. */
        MATCH
    }

    private static final String KEY_KIND = "kind";
    private static final String KEY_NAMESPACE = "namespace";
    private static final String KEY_TAG = "tag";
    private static final String KEY_NAME = "name";
    private static final String KEY_ENABLED = "enabled";

    @Nullable
    public Kind kind;

    /** The tag namespace, only meaningful for {@link Kind#VALUE}. */
    @Nullable
    public String namespace;

    /** The matched tag, only meaningful for {@link Kind#MATCH}. */
    @Nullable
    public String tag;

    /** Display name override for {@link Kind#MATCH}; a null value means "use the built-in name". */
    @Nullable
    public String name;

    public boolean enabled = true;

    public CustomGroupDimension() {
    }

    public CustomGroupDimension(@NonNull Kind kind, @Nullable String namespace,
                                @Nullable String tag, @Nullable String name, boolean enabled) {
        this.kind = kind;
        this.namespace = namespace;
        this.tag = tag;
        this.name = name;
        this.enabled = enabled;
    }

    @NonNull
    public static CustomGroupDimension value(@NonNull String namespace, boolean enabled) {
        return new CustomGroupDimension(Kind.VALUE, namespace, null, null, enabled);
    }

    @NonNull
    public static CustomGroupDimension match(@NonNull String tag, @Nullable String name, boolean enabled) {
        return new CustomGroupDimension(Kind.MATCH, null, tag, name, enabled);
    }

    public boolean isValue() {
        return kind == Kind.VALUE;
    }

    public boolean isMatch() {
        return kind == Kind.MATCH;
    }

    /**
     * Whether the dimension is one of the built-in ones. Built-in dimensions can be disabled but not
     * removed, so the configuration always keeps a working baseline. The flag is derived from the
     * namespace or tag instead of being stored, which also keeps configurations written before the
     * custom dimensions existed working as expected.
     */
    public boolean isBuiltin() {
        if (isValue()) {
            return CustomGroupConfig.PARODY_NAMESPACE.equalsIgnoreCase(namespace)
                    || CustomGroupConfig.ARTIST_NAMESPACE.equalsIgnoreCase(namespace);
        }
        if (isMatch()) {
            return CustomGroupConfig.AI_GENERATED_TAG.equalsIgnoreCase(tag)
                    || CustomGroupConfig.ANIMATED_TAG.equalsIgnoreCase(tag);
        }
        return false;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put(KEY_KIND, kind == null ? Kind.VALUE.name() : kind.name());
        object.put(KEY_NAMESPACE, namespace);
        object.put(KEY_TAG, tag);
        object.put(KEY_NAME, name);
        object.put(KEY_ENABLED, enabled);
        return object;
    }

    @Nullable
    public static CustomGroupDimension fromJson(@Nullable JSONObject object) {
        if (object == null) {
            return null;
        }
        String kindName = object.getString(KEY_KIND);
        Kind kind;
        if (Kind.MATCH.name().equalsIgnoreCase(kindName)) {
            kind = Kind.MATCH;
        } else if (Kind.VALUE.name().equalsIgnoreCase(kindName)) {
            kind = Kind.VALUE;
        } else {
            return null;
        }
        String namespace = object.getString(KEY_NAMESPACE);
        String tag = object.getString(KEY_TAG);
        if (kind == Kind.VALUE && (namespace == null || namespace.isEmpty())) {
            return null;
        }
        if (kind == Kind.MATCH && (tag == null || tag.isEmpty())) {
            return null;
        }
        return new CustomGroupDimension(kind, namespace, tag, object.getString(KEY_NAME),
                object.getBooleanValue(KEY_ENABLED));
    }

    @NonNull
    public CustomGroupDimension copy() {
        return new CustomGroupDimension(kind, namespace, tag, name, enabled);
    }
}
