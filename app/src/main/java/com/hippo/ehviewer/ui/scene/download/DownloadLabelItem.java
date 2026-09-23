package com.hippo.ehviewer.ui.scene.download;

public class DownloadLabelItem {
    String label;
    String name;
    private long count;

    /** Category row of the custom group drawer: it folds the child groups of one dimension. */
    boolean category;
    int categoryId;
    boolean expanded;
    /** Child of a folded category, drawn indented below its category row. */
    boolean indented;
    /** The "all groups" row that leaves a single group and shows the group overview again. */
    boolean overview;

    DownloadLabelItem(){

    }

    DownloadLabelItem(String label, long count){
        this.count = count;
        this.label = label;
    }

    DownloadLabelItem(String label, String name, long count){
        this.count = count;
        this.label = label;
        this.name = name;
    }

    /** A category row. It carries no group key, only the id of the category it folds. */
    static DownloadLabelItem categoryItem(int categoryId, String name, long count, boolean expanded){
        DownloadLabelItem item = new DownloadLabelItem();
        item.category = true;
        item.categoryId = categoryId;
        item.name = name;
        item.count = count;
        item.expanded = expanded;
        return item;
    }

    /** The row that returns to the overview of every group. */
    static DownloadLabelItem overviewItem(String name){
        DownloadLabelItem item = new DownloadLabelItem();
        item.overview = true;
        item.name = name;
        return item;
    }

    public String count(){
        return count+"";
    }

}
