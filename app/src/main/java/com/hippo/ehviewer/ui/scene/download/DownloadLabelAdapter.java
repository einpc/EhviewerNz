package com.hippo.ehviewer.ui.scene.download;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter;
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange;
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder;
import com.hippo.ehviewer.R;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter of the download drawer list.
 *
 * <p>In custom group mode the entries are folded into categories and every entry can be reordered by
 * a long press drag, so the group order is changed in place instead of through an "move up / move
 * down" menu. The drag is limited to the entries of one category, so a group cannot leave its
 * category by accident.
 */
public class DownloadLabelAdapter
        extends RecyclerView.Adapter<DownloadLabelAdapter.LabelHolder>
        implements DraggableItemAdapter<DownloadLabelAdapter.LabelHolder> {

    private static final int MAX_NAME_LINES = 3;

    public interface Callback {
        /** Handles a tap on the entry at the position. */
        void onLabelClick(int position);

        /** Handles a tap on the "more" action of the entry at the position. */
        void onLabelMoreClick(int position);

        /** Handles a tap on a category row, which folds or unfolds its entries. */
        void onCategoryClick(int position);

        /** Persists the group order after a drag. */
        void onCustomGroupOrderChanged(@NonNull List<String> orderedKeys);
    }

    private final LayoutInflater mInflater;
    private final Callback mCallback;
    private final List<DownloadLabelItem> mItems = new ArrayList<>();
    /** Every custom group key, in the order the organizer returned them. */
    private final List<String> mAllGroupKeys = new ArrayList<>();

    /** Only custom group entries are draggable; the download labels keep their own order. */
    private boolean mCustomGroupMode;

    private final int mChildIndent;

    public DownloadLabelAdapter(@NonNull LayoutInflater inflater, @NonNull Callback callback) {
        mInflater = inflater;
        mCallback = callback;
        // The drawer is narrow, so an entry is indented by less than the width of the arrow of its
        // category row. That keeps most of the row for the name.
        mChildIndent = (int) (16 * inflater.getContext().getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    public void setItems(@NonNull List<DownloadLabelItem> items, boolean customGroupMode,
                         @NonNull List<String> allGroupKeys) {
        mItems.clear();
        mItems.addAll(items);
        mAllGroupKeys.clear();
        mAllGroupKeys.addAll(allGroupKeys);
        mCustomGroupMode = customGroupMode;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    public DownloadLabelItem getItem(int position) {
        return mItems.get(position);
    }

    /**
     * Stable id of the entry. The download labels and the custom group keys never repeat inside the
     * list, and an id that survives a reorder is what the drag wrapper needs.
     */
    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= mItems.size()) {
            return RecyclerView.NO_ID;
        }
        DownloadLabelItem item = mItems.get(position);
        if (item.overview) {
            return "overview".hashCode();
        }
        if (item.category) {
            return ("category:" + item.categoryId).hashCode();
        }
        String key = item.label;
        return key == null ? RecyclerView.NO_ID : key.hashCode();
    }

    @NonNull
    @Override
    public LabelHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new LabelHolder(mInflater.inflate(R.layout.item_download_label_list, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull LabelHolder holder, int position) {
        DownloadLabelItem item = mItems.get(position);
        String name = item.name != null ? item.name : item.label;
        holder.name.setText(name);
        // The overview row stands for every group, so it shows no count of its own.
        holder.count.setText(item.overview ? null : item.count());

        boolean category = item.category;
        boolean plain = category || item.overview;
        if (!mCustomGroupMode) {
            holder.expand.setVisibility(View.GONE);
        } else {
            // The drawer is narrow, so only a category row spends width on the arrow. An entry is
            // indented instead, which keeps most of the width for its name.
            holder.expand.setVisibility(category ? View.VISIBLE : View.GONE);
            if (category) {
                holder.expand.setImageResource(item.expanded
                        ? R.drawable.v_arrow_down_x24 : R.drawable.v_arrow_right_x24);
            }
        }
        holder.name.setTypeface(null, category ? Typeface.BOLD : Typeface.NORMAL);
        int indent = item.indented ? mChildIndent : 0;
        holder.name.setPadding(holder.namePaddingStart + indent, holder.name.getPaddingTop(),
                holder.namePaddingEnd, holder.name.getPaddingBottom());
        // A group name can be long, but the row must not grow into a tall column of single
        // characters when the drawer is narrow.
        holder.name.setMaxLines(mCustomGroupMode ? MAX_NAME_LINES : Integer.MAX_VALUE);
        holder.name.setEllipsize(mCustomGroupMode ? TextUtils.TruncateAt.END : null);

        // A category row and the overview row keep the space of the actions without showing them, so
        // the counts of a category and of its entries stay in the same column.
        int groupActionsVisibility = mCustomGroupMode
                ? (plain ? View.INVISIBLE : View.VISIBLE) : View.GONE;
        holder.more.setVisibility(groupActionsVisibility);
        holder.dragHandler.setVisibility(groupActionsVisibility);
    }

    @Override
    public boolean onCheckCanStartDrag(@NonNull LabelHolder holder, int position, int x, int y) {
        // Reordering starts from the drag handle only, the same way the download label list does it.
        // That keeps a swipe on the entry scrolling the list. A category row and the overview row
        // cannot be dragged.
        return mCustomGroupMode && position >= 0 && position < mItems.size()
                && isGroupEntry(mItems.get(position))
                && ViewUtils.isViewUnder(holder.dragHandler, x, y, 0);
    }

    /** A row that stands for a single custom group, so it can be reordered inside its category. */
    private static boolean isGroupEntry(@Nullable DownloadLabelItem item) {
        return item != null && !item.category && !item.overview;
    }

    @Override
    public ItemDraggableRange onGetItemDraggableRange(@NonNull LabelHolder holder, int position) {
        if (!mCustomGroupMode || position < 0 || position >= mItems.size()) {
            return null;
        }
        DownloadLabelItem item = mItems.get(position);
        if (!isGroupEntry(item)) {
            return new ItemDraggableRange(position, position);
        }
        // The category order is fixed, so an entry can only move among its own siblings.
        int start = position;
        while (start > 0 && isSiblingOf(mItems.get(start - 1), item)) {
            start--;
        }
        int end = position;
        while (end < mItems.size() - 1 && isSiblingOf(mItems.get(end + 1), item)) {
            end++;
        }
        return new ItemDraggableRange(start, end);
    }

    private static boolean isSiblingOf(@Nullable DownloadLabelItem other, @NonNull DownloadLabelItem item) {
        return isGroupEntry(other) && other.categoryId == item.categoryId;
    }

    @Override
    public void onMoveItem(int fromPosition, int toPosition) {
        if (fromPosition == toPosition
                || fromPosition < 0 || fromPosition >= mItems.size()
                || toPosition < 0 || toPosition >= mItems.size()) {
            return;
        }
        // Only the data is moved here: the drag wrapper notifies the move itself.
        DownloadLabelItem item = mItems.remove(fromPosition);
        mItems.add(toPosition, item);
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
        if (!result || !mCustomGroupMode) {
            return;
        }
        DownloadLabelItem dragged = toPosition >= 0 && toPosition < mItems.size()
                ? mItems.get(toPosition) : null;
        mCallback.onCustomGroupOrderChanged(buildOrderAfterDrag(dragged));
    }

    /**
     * Only the entries of the dragged category changed their relative order, so the recorded order is
     * rewritten by filling the places of those entries with their new order. Entries of the other
     * categories, including the ones hidden inside a folded category, keep their place.
     */
    @NonNull
    private List<String> buildOrderAfterDrag(@Nullable DownloadLabelItem dragged) {
        Set<String> siblings = new HashSet<>();
        List<String> siblingOrder = new ArrayList<>();
        if (dragged != null && !dragged.category) {
            for (DownloadLabelItem item : mItems) {
                if (!item.category && item.label != null && item.categoryId == dragged.categoryId) {
                    siblings.add(item.label);
                    siblingOrder.add(item.label);
                }
            }
        }
        if (siblings.isEmpty()) {
            List<String> keys = new ArrayList<>(mItems.size());
            for (DownloadLabelItem item : mItems) {
                if (!item.category && item.label != null) {
                    keys.add(item.label);
                }
            }
            return keys;
        }

        List<String> keys = new ArrayList<>(mAllGroupKeys.size());
        int index = 0;
        for (String key : mAllGroupKeys) {
            if (siblings.contains(key)) {
                keys.add(siblingOrder.get(index++));
            } else {
                keys.add(key);
            }
        }
        return keys;
    }

    class LabelHolder extends AbstractDraggableItemViewHolder {

        final TextView name;
        final TextView count;
        final ImageView more;
        final ImageView dragHandler;
        final ImageView expand;
        final int namePaddingStart;
        final int namePaddingEnd;

        LabelHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text1);
            count = itemView.findViewById(R.id.text2);
            more = itemView.findViewById(R.id.more);
            dragHandler = itemView.findViewById(R.id.drag_handler);
            expand = itemView.findViewById(R.id.expand);
            namePaddingStart = name.getPaddingStart();
            namePaddingEnd = name.getPaddingEnd();

            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    if (mItems.get(position).category) {
                        mCallback.onCategoryClick(position);
                    } else {
                        mCallback.onLabelClick(position);
                    }
                }
            });
            more.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    mCallback.onLabelMoreClick(position);
                }
            });
        }
    }
}
