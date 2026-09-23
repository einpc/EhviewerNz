package com.hippo.ehviewer.ui.scene.download;

import android.content.Context;
import android.graphics.drawable.NinePatchDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.CustomGroupItem;
import com.hippo.ehviewer.download.CustomGroupOrganizer;
import com.hippo.ehviewer.download.CustomGroupStore;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.scene.Announcer;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ObjectUtils;

import java.util.ArrayList;
import java.util.List;

public class DownloadLabelDraw {
    private final LayoutInflater inflater;
    private final DownloadsScene scene;
    private final ViewGroup container;
    private final Context context;

    private View view;
    private Toolbar toolbar;
    private RecyclerView listView;
    private DownloadLabelAdapter adapter;
    private RecyclerViewDragDropManager dragDropManager;
    /** Every custom group key of the last build, used to keep the order when a category is folded. */
    private final List<String> mCustomGroupKeys = new ArrayList<>();

    public DownloadLabelDraw(LayoutInflater inflater, @Nullable ViewGroup container,DownloadsScene scene){
        this.inflater = inflater;
        this.container = container;
        this.scene = scene;
        this.context = scene.getEHContext();
    }

    public View createView(){
        view = inflater.inflate(R.layout.download_draw, container, false);
        assert context != null;
        AssertUtils.assertNotNull(context);

        toolbar = view.findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.drawer_download);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            switch (id) {
                case R.id.action_switch_download_mode:
                    scene.toggleDownloadMode();
                    return true;
                case R.id.action_settings:
                    scene.startScene(new Announcer(DownloadLabelsScene.class));
                    return true;
                case R.id.action_custom_group_settings:
                    scene.startScene(new Announcer(CustomGroupConfigScene.class));
                    return true;
                case R.id.action_default_download_label:
                    DownloadManager dm = scene.getMDownloadManager();
                    if (null == dm) {
                        return true;
                    }

                    List<DownloadLabel> list = dm.getLabelList();
                    final String[] items = new String[list.size() + 2];
                    items[0] = scene.getString(R.string.let_me_select);
                    items[1] = scene.getString(R.string.default_download_label_name);
                    for (int i = 0, n = list.size(); i < n; i++) {
                        items[i + 2] = list.get(i).getLabel();
                    }
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.default_download_label)
                            .setItems(items, (dialog, which) -> {
                                if (which == 0) {
                                    Settings.putHasDefaultDownloadLabel(false);
                                } else {
                                    Settings.putHasDefaultDownloadLabel(true);
                                    String label;
                                    if (which == 1) {
                                        label = null;
                                    } else {
                                        label = items[which];
                                    }
                                    Settings.putDefaultDownloadLabel(label);
                                }
                            }).show();
                    return true;
            }
            return false;
        });

        listView = view.findViewById(R.id.list_view);
        listView.setLayoutManager(new LinearLayoutManager(context));
        dragDropManager = new RecyclerViewDragDropManager();
        dragDropManager.setDraggingItemShadowDrawable(
                (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));
        adapter = new DownloadLabelAdapter(inflater, new AdapterCallback());
        // The drag wrapper refuses an adapter without stable ids.
        adapter.setHasStableIds(true);
        listView.setAdapter(dragDropManager.createWrappedAdapter(adapter));
        listView.setItemAnimator(new DraggableItemAnimator());
        dragDropManager.attachRecyclerView(listView);

        updateDownloadLabels();
        return view;
    }

    public void updateDownloadLabels(){
        if (listView == null || adapter == null) {
            return;
        }
        final boolean customGroupMode = scene.isCustomGroupMode();
        updateToolbar(customGroupMode);
        List<DownloadLabelItem> items = buildItems(customGroupMode);
        adapter.setItems(items, customGroupMode, mCustomGroupKeys);
    }

    private void updateToolbar(boolean customGroupMode) {
        if (toolbar == null) {
            return;
        }
        toolbar.setTitle(customGroupMode ? R.string.custom_group_title : R.string.download_labels);
        android.view.Menu menu = toolbar.getMenu();
        android.view.MenuItem defaultItem = menu.findItem(R.id.action_default_download_label);
        if (defaultItem != null) {
            defaultItem.setVisible(!customGroupMode);
        }
        android.view.MenuItem switchItem = menu.findItem(R.id.action_switch_download_mode);
        if (switchItem != null) {
            switchItem.setTitle(customGroupMode
                    ? R.string.custom_group_switch_to_labels
                    : R.string.custom_group_switch_to_groups);
        }
        android.view.MenuItem groupSettingsItem = menu.findItem(R.id.action_custom_group_settings);
        if (groupSettingsItem != null) {
            groupSettingsItem.setVisible(customGroupMode);
        }
    }

    /**
     * Builds the drawer entries. In custom group mode the groups are folded into one collapsible row
     * per category, so a long group list stays readable. A category holding a single group is shown
     * without its category row, because folding one entry would only add a level to open.
     */
    private List<DownloadLabelItem> buildItems(boolean customGroupMode) {
        mCustomGroupKeys.clear();
        if (customGroupMode) {
            final List<DownloadLabelItem> items = new ArrayList<>();
            List<CustomGroupItem> groups = scene.getCustomGroups();
            if (groups == null) {
                return items;
            }
            for (CustomGroupItem group : groups) {
                if (group != null) {
                    mCustomGroupKeys.add(group.key);
                }
            }

            // A single group is left through this row, so the drawer always has a way back to the
            // overview of every group.
            items.add(DownloadLabelItem.overviewItem(context.getString(R.string.custom_group_overview)));

            CustomGroupStore store = CustomGroupStore.load(context);
            for (int category : CustomGroupOrganizer.CATEGORY_ORDER) {
                List<CustomGroupItem> children = new ArrayList<>();
                long total = 0L;
                for (CustomGroupItem group : groups) {
                    if (group != null && CustomGroupOrganizer.categoryOf(group.key) == category) {
                        children.add(group);
                        total += group.count;
                    }
                }
                if (children.isEmpty()) {
                    continue;
                }
                if (children.size() == 1) {
                    items.add(groupItem(children.get(0), false));
                    continue;
                }
                boolean expanded = store.isCategoryExpanded(category);
                items.add(DownloadLabelItem.categoryItem(category, categoryName(category), total, expanded));
                if (expanded) {
                    for (CustomGroupItem group : children) {
                        items.add(groupItem(group, true));
                    }
                }
            }
            return items;
        }

        final DownloadManager downloadManager = EhApplication.getDownloadManager(context);
        List<DownloadLabel> list = downloadManager.getLabelList();
        final List<String> labels = new ArrayList<>(list.size() + 1);
        // Add default label name
        labels.add(scene.getString(R.string.default_download_label_name));
        for (DownloadLabel raw : list) {
            labels.add(raw.getLabel());
        }

        final List<DownloadLabelItem> items = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (i == 0) {
                items.add(new DownloadLabelItem(label, downloadManager.getDefaultDownloadInfoList().size()));
                continue;
            }
            items.add(new DownloadLabelItem(label, downloadManager.getLabelCount(label)));
        }
        return items;
    }

    @NonNull
    private DownloadLabelItem groupItem(@NonNull CustomGroupItem group, boolean indented) {
        DownloadLabelItem item = new DownloadLabelItem(group.key, group.displayName, group.count);
        item.categoryId = CustomGroupOrganizer.categoryOf(group.key);
        item.indented = indented;
        return item;
    }

    @NonNull
    private String categoryName(int category) {
        switch (category) {
            case CustomGroupOrganizer.CATEGORY_PARODY:
                return context.getString(R.string.custom_group_namespace_parody);
            case CustomGroupOrganizer.CATEGORY_ARTIST:
                return context.getString(R.string.custom_group_namespace_artist);
            case CustomGroupOrganizer.CATEGORY_UPLOADER:
                return context.getString(R.string.custom_group_category_uploader);
            case CustomGroupOrganizer.CATEGORY_TAG:
                return context.getString(R.string.custom_group_category_tag);
            default:
                return context.getString(R.string.custom_group_category_other);
        }
    }

    private class AdapterCallback implements DownloadLabelAdapter.Callback {

        @Override
        public void onLabelClick(int position) {
            if (scene.searching) {
                Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show();
                return;
            }
            DownloadLabelItem item = adapterItem(position);
            if (item == null) {
                return;
            }
            if (scene.isCustomGroupMode()) {
                if (item.overview) {
                    scene.onCustomGroupOverviewClick();
                } else {
                    scene.onCustomGroupItemClick(item.label);
                }
                scene.closeDrawer(Gravity.RIGHT);
                return;
            }
            String label;
            if (position == 0) {
                label = null;
            } else {
                label = item.label;
            }
            if (scene.isContinuousLabelBrowse()) {
                scene.scrollToDownloadLabel(label);
                scene.closeDrawer(Gravity.RIGHT);
                return;
            }
            if (!ObjectUtils.equal(label, scene.mLabel)) {
                scene.mLabel = label;
                scene.updateForLabel();
                if (scene.searchKey != null && !scene.searchKey.isEmpty()) {
                    scene.startSearching();
                } else {
                    scene.updateView();
                }
                scene.closeDrawer(Gravity.RIGHT);
            }
        }

        @Override
        public void onLabelMoreClick(int position) {
            if (!scene.isCustomGroupMode()) {
                return;
            }
            DownloadLabelItem item = adapterItem(position);
            if (item != null) {
                scene.showCustomGroupActions(item.label);
            }
        }

        @Override
        public void onCategoryClick(int position) {
            DownloadLabelItem item = adapterItem(position);
            if (item == null || !item.category) {
                return;
            }
            CustomGroupStore store = CustomGroupStore.load(context);
            store.setCategoryExpanded(item.categoryId, !item.expanded);
            store.save();
            updateDownloadLabels();
        }

        @Override
        public void onCustomGroupOrderChanged(@NonNull List<String> orderedKeys) {
            scene.applyCustomGroupOrder(orderedKeys);
        }
    }

    @Nullable
    private DownloadLabelItem adapterItem(int position) {
        if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
            return null;
        }
        return adapter.getItem(position);
    }
}
