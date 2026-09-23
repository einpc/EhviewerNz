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

package com.hippo.ehviewer.ui.scene.download;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter;
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.download.CustomGroupConfig;
import com.hippo.ehviewer.download.CustomGroupDimension;
import com.hippo.ehviewer.download.CustomGroupOrganizer;
import com.hippo.ehviewer.download.CustomGroupStore;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.util.TagTranslationUtil;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lists every {@link CustomGroupDimension} of the {@link CustomGroupConfig} and lets the user
 * enable or disable a dimension and change the classification priority by reordering the list.
 *
 * <p>Every change is persisted immediately through {@link CustomGroupConfig#save()}. The download
 * scene reloads the configuration when it resumes, so no cross scene callback is required.
 */
public class CustomGroupConfigScene extends ToolbarScene {

    @Nullable
    private CustomGroupConfig mConfig;
    @Nullable
    private EasyRecyclerView mRecyclerView;
    @Nullable
    private DimensionAdapter mAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mConfig = CustomGroupConfig.load(getEHContext());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mConfig = null;
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_custom_group_config, container, false);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);

        mRecyclerView = (EasyRecyclerView) ViewUtils.$$(view, R.id.recycler_view);
        mAdapter = new DimensionAdapter();
        mAdapter.setHasStableIds(true);
        RecyclerViewDragDropManager dragDropManager = new RecyclerViewDragDropManager();
        dragDropManager.setDraggingItemShadowDrawable(
                (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));
        mRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        mRecyclerView.setAdapter(dragDropManager.createWrappedAdapter(mAdapter));
        mRecyclerView.setItemAnimator(new DraggableItemAnimator());
        dragDropManager.attachRecyclerView(mRecyclerView);

        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.custom_group_settings);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        mAdapter = null;
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_custom_group_config;
    }

    @Override
    public void onMenuCreated(Menu menu) {
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_reset_custom_group) {
            confirmResetToDefault();
            return true;
        }
        if (item.getItemId() == R.id.action_add_custom_group_dimension) {
            showAddDimensionDialog();
            return true;
        }
        return false;
    }

    /**
     * The reset throws away every tag dimension the user added, so it asks twice: the first dialog
     * explains what is lost and the second one asks for the final confirmation.
     */
    private void confirmResetToDefault() {
        Context context = getEHContext();
        if (context == null || mConfig == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.custom_group_reset_default)
                .setMessage(R.string.custom_group_reset_default_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.custom_group_reset_default_continue,
                        (dialog, which) -> confirmResetToDefaultAgain())
                .show();
    }

    private void confirmResetToDefaultAgain() {
        Context context = getEHContext();
        if (context == null || mConfig == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.custom_group_reset_default_again_title)
                .setMessage(R.string.custom_group_reset_default_again_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.custom_group_reset_default_confirm,
                        (dialog, which) -> resetToDefault())
                .show();
    }

    private void resetToDefault() {
        if (mConfig == null) {
            return;
        }
        Set<String> previousKeys = matchGroupKeys();
        mConfig.resetToDefault();
        mConfig.save();
        purgeDroppedMatchGroups(previousKeys);
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        Toast.makeText(getEHContext(), R.string.custom_group_reset_default, Toast.LENGTH_SHORT).show();
    }

    /**
     * Asks for a tag and an optional group name and appends a {@link CustomGroupDimension.Kind#MATCH}
     * dimension. The dialog stays open while the input is empty or already configured, so the user can
     * fix it in place.
     */
    private void showAddDimensionDialog() {
        if (mConfig == null) {
            return;
        }
        final Context context = getEHContext();
        LayoutInflater inflater = getLayoutInflater2();
        if (context == null || inflater == null) {
            return;
        }

        View view = inflater.inflate(R.layout.dialog_custom_group_dimension, null);
        final EditText tagInput = (EditText) ViewUtils.$$(view, R.id.tag);
        final EditText nameInput = (EditText) ViewUtils.$$(view, R.id.name);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.custom_group_add_dimension)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();

        Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setOnClickListener(v -> {
                String tag = tagInput.getText().toString().trim();
                if (tag.isEmpty()) {
                    Toast.makeText(context, R.string.custom_group_add_dimension_empty,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = nameInput.getText().toString().trim();
                CustomGroupDimension dimension = CustomGroupDimension.match(tag,
                        name.isEmpty() ? null : name, true);
                if (!mConfig.addDimension(dimension)) {
                    Toast.makeText(context, R.string.custom_group_add_dimension_duplicate,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                mConfig.save();
                if (mAdapter != null) {
                    mAdapter.notifyDataSetChanged();
                }
                Toast.makeText(context, R.string.custom_group_add_dimension_done,
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        }
    }

    private void removeDimension(int position) {
        if (mConfig == null) {
            return;
        }
        CustomGroupDimension dimension = mConfig.getDimension(position);
        if (dimension == null) {
            return;
        }
        String label = dimensionName(dimension);
        Set<String> previousKeys = matchGroupKeys();
        if (!mConfig.removeDimension(position)) {
            return;
        }
        mConfig.save();
        purgeDroppedMatchGroups(previousKeys);
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        Toast.makeText(getEHContext(), getString(R.string.custom_group_dimension_removed, label),
                Toast.LENGTH_SHORT).show();
    }

    /** The group keys of every configured match dimension. */
    @NonNull
    private Set<String> matchGroupKeys() {
        Set<String> keys = new HashSet<>();
        if (mConfig != null) {
            for (CustomGroupDimension dimension : mConfig.getDimensions()) {
                if (dimension.isMatch() && dimension.tag != null && !dimension.tag.isEmpty()) {
                    keys.add(CustomGroupOrganizer.matchKey(dimension.tag));
                }
            }
        }
        return keys;
    }

    /**
     * Drops the manual memberships of the groups that are no longer configured. Without it a group
     * whose dimension was removed would keep showing up, because a manual membership alone is enough
     * to create the group.
     */
    private void purgeDroppedMatchGroups(@NonNull Set<String> previousKeys) {
        previousKeys.removeAll(matchGroupKeys());
        Context context = getEHContext();
        if (previousKeys.isEmpty() || context == null) {
            return;
        }
        CustomGroupStore store = CustomGroupStore.load(context);
        for (String key : previousKeys) {
            store.purgeGroup(key);
        }
        store.save();
    }

    private void setDimensionEnabled(int position, boolean enabled) {
        if (mConfig == null) {
            return;
        }
        mConfig.setEnabled(position, enabled);
        mConfig.save();
    }

    @NonNull
    private String dimensionName(@NonNull CustomGroupDimension dimension) {
        if (dimension.isValue()) {
            String namespace = dimension.namespace == null ? "" : dimension.namespace;
            int resId = namespaceNameRes(namespace);
            if (resId != 0) {
                return getString(R.string.custom_group_dimension_value, getString(resId), namespace);
            }
            return namespace;
        }
        if (dimension.name != null && !dimension.name.isEmpty()) {
            return dimension.name;
        }
        String uploader = CustomGroupConfig.uploaderOf(dimension.tag);
        if (uploader != null) {
            return getString(R.string.custom_group_uploader_group_name, uploader);
        }
        int resId = builtinMatchNameRes(dimension.tag);
        if (resId != 0) {
            return getString(resId);
        }
        // A tag dimension without a name shows the same translated name as its group in the drawer.
        Context context = getEHContext();
        String translated = dimension.tag == null || context == null
                ? null
                : TagTranslationUtil.getTagCNBody(dimension.tag,
                EhTagDatabase.getInstance(context));
        return translated != null ? translated : (dimension.tag == null ? "" : dimension.tag);
    }

    private static int namespaceNameRes(@NonNull String namespace) {
        if (CustomGroupConfig.PARODY_NAMESPACE.equalsIgnoreCase(namespace)) {
            return R.string.custom_group_namespace_parody;
        }
        if (CustomGroupConfig.ARTIST_NAMESPACE.equalsIgnoreCase(namespace)) {
            return R.string.custom_group_namespace_artist;
        }
        return 0;
    }

    private static int builtinMatchNameRes(@Nullable String tag) {
        if (tag == null) {
            return 0;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.equals(CustomGroupConfig.AI_GENERATED_TAG)) {
            return R.string.custom_group_ai_generated;
        }
        if (lower.equals(CustomGroupConfig.ANIMATED_TAG)) {
            return R.string.custom_group_animated;
        }
        return 0;
    }

    private class DimensionHolder extends AbstractDraggableItemViewHolder
            implements View.OnClickListener {

        final CheckBox enabled;
        final TextView name;
        final ImageView remove;
        final ImageView dragHandler;

        DimensionHolder(View itemView) {
            super(itemView);
            enabled = (CheckBox) ViewUtils.$$(itemView, R.id.enabled);
            name = (TextView) ViewUtils.$$(itemView, R.id.name);
            remove = (ImageView) ViewUtils.$$(itemView, R.id.remove);
            dragHandler = (ImageView) ViewUtils.$$(itemView, R.id.drag_handler);

            enabled.setOnClickListener(this);
            remove.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION || mConfig == null) {
                return;
            }
            if (v == enabled) {
                setDimensionEnabled(position, enabled.isChecked());
            } else if (v == remove) {
                removeDimension(position);
            }
        }
    }

    private class DimensionAdapter extends RecyclerView.Adapter<DimensionHolder>
            implements DraggableItemAdapter<DimensionHolder> {

        private final LayoutInflater mInflater;

        DimensionAdapter() {
            mInflater = getLayoutInflater2();
            AssertUtils.assertNotNull(mInflater);
        }

        @NonNull
        @Override
        public DimensionHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DimensionHolder(
                    mInflater.inflate(R.layout.item_custom_group_dimension, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull DimensionHolder holder, int position) {
            if (mConfig == null) {
                return;
            }
            CustomGroupDimension dimension = mConfig.getDimension(position);
            if (dimension == null) {
                return;
            }
            String label = dimensionName(dimension);
            holder.name.setText(label);
            holder.enabled.setChecked(dimension.enabled);
            holder.enabled.setContentDescription(
                    getString(R.string.custom_group_dimension_enabled, label));
            // Built-in dimensions can only be disabled, so they get no remove action.
            holder.remove.setVisibility(dimension.isBuiltin() ? View.GONE : View.VISIBLE);
        }

        @Override
        public long getItemId(int position) {
            if (mConfig == null || position < 0 || position >= mConfig.size()) {
                return RecyclerView.NO_ID;
            }
            CustomGroupDimension dimension = mConfig.getDimension(position);
            if (dimension == null) {
                return RecyclerView.NO_ID;
            }
            String key = dimension.isValue() ? dimension.namespace : dimension.tag;
            return key == null ? RecyclerView.NO_ID : key.hashCode();
        }

        @Override
        public int getItemCount() {
            return mConfig == null ? 0 : mConfig.size();
        }

        @Override
        public boolean onCheckCanStartDrag(@NonNull DimensionHolder holder, int position,
                                           int x, int y) {
            // Reordering starts from the drag handle, so a swipe on the row cannot move a dimension
            // by accident.
            return ViewUtils.isViewUnder(holder.dragHandler, x, y, 0);
        }

        @Override
        public ItemDraggableRange onGetItemDraggableRange(@NonNull DimensionHolder holder,
                                                          int position) {
            return null;
        }

        @Override
        public void onMoveItem(int fromPosition, int toPosition) {
            if (mConfig == null) {
                return;
            }
            // Only the data is moved here: the drag wrapper notifies the move itself.
            mConfig.reorder(fromPosition, toPosition);
        }

        @Override
        public boolean onCheckCanDrop(int draggingPosition, int dropPosition) {
            return true;
        }

        @Override
        public void onItemDragStarted(int position) {
        }

        @Override
        public void onItemDragFinished(int fromPosition, int toPosition, boolean result) {
            if (result && mConfig != null) {
                // The list order is the classification priority, so it has to survive the scene.
                mConfig.save();
            }
        }
    }
}
