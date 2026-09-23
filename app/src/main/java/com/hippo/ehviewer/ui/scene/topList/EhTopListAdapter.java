package com.hippo.ehviewer.ui.scene.topList;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.EhTopListDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.topList.TopListInfo;
import com.hippo.ehviewer.client.data.topList.TopListItem;
import com.hippo.ehviewer.client.data.topList.TopListItemArray;
import com.hippo.ehviewer.widget.TileThumbNew;

import java.util.ArrayList;
import java.util.List;

abstract class EhTopListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int ITEM_PERIOD = 0;
    private static final int ITEM_TEXT = 1;
    private static final int ITEM_GALLERY = 2;

    private final Context context;
    private final TopListInfo ehTopListInfo;
    private final int searchType;
    private final boolean galleryMode;
    private final List<Entry> entries = new ArrayList<>();

    private static class Entry {

        final int type;
        final int period;
        @Nullable
        final TopListItem item;
        @Nullable
        final GalleryInfo gallery;
        final int rank;

        Entry(int type, int period, @Nullable TopListItem item,
              @Nullable GalleryInfo gallery, int rank) {
            this.type = type;
            this.period = period;
            this.item = item;
            this.gallery = gallery;
            this.rank = rank;
        }
    }

    public EhTopListAdapter(@NonNull Context context, TopListInfo topListInfo, int searchType,
                            @Nullable List<List<GalleryInfo>> galleryPeriods) {
        this.context = context;
        this.ehTopListInfo = topListInfo;
        this.searchType = searchType;
        this.galleryMode = topListInfo.type == EhTopListDetail.ListType.GALLERY
                && galleryPeriods != null;
        buildEntries(galleryPeriods);
    }

    private void buildEntries(@Nullable List<List<GalleryInfo>> galleryPeriods) {
        for (int period = 0; period < ehTopListInfo.size(); period++) {
            if (!galleryMode) {
                entries.add(new Entry(ITEM_TEXT, period, null, null, 0));
                continue;
            }
            entries.add(new Entry(ITEM_PERIOD, period, null, null, 0));
            if (galleryPeriods == null || period >= galleryPeriods.size()) {
                continue;
            }
            List<GalleryInfo> galleries = galleryPeriods.get(period);
            TopListItemArray array = ehTopListInfo.get(period);
            for (int i = 0, size = galleries.size(); i < size; i++) {
                GalleryInfo gallery = galleries.get(i);
                if (gallery == null) {
                    continue;
                }
                TopListItem item = array != null && i < array.length() ? array.get(i) : null;
                entries.add(new Entry(ITEM_GALLERY, period, item, gallery, i + 1));
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).type;
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        ViewGroup.LayoutParams layoutParams = holder.itemView.getLayoutParams();
        if (layoutParams instanceof StaggeredGridLayoutManager.LayoutParams) {
            ((StaggeredGridLayoutManager.LayoutParams) layoutParams).setFullSpan(
                    !(holder instanceof GalleryHolder));
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        switch (viewType) {
            case ITEM_PERIOD:
                return new PeriodHolder(View.inflate(context, R.layout.item_top_list_period_header, null));
            case ITEM_GALLERY:
                return new GalleryHolder(View.inflate(context, R.layout.item_top_list_gallery, null));
            default:
            case ITEM_TEXT:
                return new TextHolder(View.inflate(context, R.layout.gallery_top_list_table_item, null));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        switch (entry.type) {
            case ITEM_PERIOD: {
                PeriodHolder periodHolder = (PeriodHolder) holder;
                periodHolder.title.setText(timeInfoId(entry.period));
                periodHolder.title.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
                periodHolder.title.setOnClickListener(v -> clickTitle(urlFollow(entry.period)));
                break;
            }
            case ITEM_GALLERY: {
                bindGallery((GalleryHolder) holder, entry);
                break;
            }
            default:
            case ITEM_TEXT: {
                bindText((TextHolder) holder, entry);
                break;
            }
        }
    }

    private void bindGallery(@NonNull GalleryHolder holder, @NonNull Entry entry) {
        GalleryInfo gallery = entry.gallery;
        if (gallery == null) {
            return;
        }
        holder.rank.setText(String.valueOf(entry.rank));
        holder.thumb.setThumbSize(gallery.thumbWidth, gallery.thumbHeight);
        holder.thumb.load(EhCacheKeyFactory.getThumbKey(gallery.gid), gallery.thumb);

        holder.category.setText(EhUtils.getCategory(gallery.category));
        holder.category.setBackgroundColor(EhUtils.getCategoryColor(gallery.category));

        if (TextUtils.isEmpty(gallery.simpleLanguage)) {
            holder.simpleLanguage.setText(null);
            holder.simpleLanguage.setVisibility(View.GONE);
        } else {
            holder.simpleLanguage.setText(gallery.simpleLanguage);
            holder.simpleLanguage.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (entry.item != null) {
                onItemClick(entry.item, searchType);
            }
        });
    }

    private void bindText(@NonNull TextHolder holder, @NonNull Entry entry) {
        holder.title.setText(timeInfoId(entry.period));
        holder.title.setPaintFlags(holder.title.getPaint().getFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
        holder.title.setOnClickListener(null);

        holder.tableLayout.removeAllViews();
        TopListItemArray topListItemArray = ehTopListInfo.get(entry.period);
        if (topListItemArray == null) {
            return;
        }
        for (int i = 0; i < topListItemArray.length(); i++) {
            TopListItem topListItem = topListItemArray.get(i);
            if (topListItem == null) {
                continue;
            }
            View view = View.inflate(context, R.layout.gallery_top_list_item, null);
            TextView textView = view.findViewById(R.id.list_item);
            GradientDrawable gradientDrawable = new GradientDrawable();
            gradientDrawable.setColor(getRandomColor(i));
            gradientDrawable.setCornerRadius(8);
            textView.setBackground(gradientDrawable);
            textView.setText(topListItem.value);
            view.setOnClickListener(v -> onItemClick(topListItem, searchType));
            holder.tableLayout.addView(view);
        }
    }

    abstract void clickTitle(String urlFollow);

    abstract int getRandomColor(int position);

    abstract void onItemClick(TopListItem topListItem, int searchType);

    private int timeInfoId(int index) {
        switch (index) {
            default:
            case 3:
                return R.string.all_time_top_list;
            case 2:
                return R.string.past_year_top_list;
            case 1:
                return R.string.past_month_top_list;
            case 0:
                return R.string.yesterday_top_list;
        }
    }

    private String urlFollow(int index) {
        switch (index) {
            default:
            case 3:
                return "tl=11";
            case 2:
                return "tl=12";
            case 1:
                return "tl=13";
            case 0:
                return "tl=15";
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private static class PeriodHolder extends RecyclerView.ViewHolder {

        private final TextView title;

        PeriodHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.period_title);
        }
    }

    private static class TextHolder extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TableLayout tableLayout;

        TextHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.list_of_time);
            tableLayout = itemView.findViewById(R.id.list_items_table_view);
        }
    }

    private static class GalleryHolder extends RecyclerView.ViewHolder {

        private final TileThumbNew thumb;
        private final TextView rank;
        private final TextView category;
        private final TextView simpleLanguage;

        GalleryHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            rank = itemView.findViewById(R.id.rank);
            category = itemView.findViewById(R.id.category);
            simpleLanguage = itemView.findViewById(R.id.simple_language);
        }
    }
}
