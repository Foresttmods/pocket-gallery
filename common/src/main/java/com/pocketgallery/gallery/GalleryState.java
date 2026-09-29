package com.pocketgallery.gallery;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.image.PixelLook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gallery data that outlives the screen: the current tab, the selection (across tabs) and the per-image pixel
 * look. The tabs keep their own results. Client thread only.
 */
public final class GalleryState {
    private static final GalleryState INSTANCE = new GalleryState();

    public static GalleryState get() {
        return INSTANCE;
    }

    private GalleryTab tab;

    // selection (keeps insertion order = import order)
    public final LinkedHashMap<String, GalleryEntry> selected = new LinkedHashMap<>();
    /** Per-image pixel look chosen in the preview panel (entries without one use the configured default). */
    private final Map<String, PixelLook> looks = new HashMap<>();

    private GalleryState() {
    }

    public GalleryTab tab() {
        if (tab == null) tab = GalleryTabs.first();
        return tab;
    }

    public void setTab(GalleryTab tab) {
        this.tab = tab;
    }

    public List<GalleryEntry> entries() {
        return tab().entries();
    }

    // ================================================================= pixel look

    public PixelLook lookOf(GalleryEntry e) {
        PixelLook l = looks.get(e.key());
        return l != null ? l : PocketGallery.config().defaultLook();
    }

    public void setLook(GalleryEntry e, PixelLook look) {
        if (look.equals(PocketGallery.config().defaultLook())) looks.remove(e.key());
        else looks.put(e.key(), look);
    }

    // ================================================================= selection

    public List<GalleryEntry> selectedList() {
        return new ArrayList<>(selected.values());
    }

    /** Toggles, returns whether the entry is now selected. */
    public boolean toggle(GalleryEntry e) {
        if (selected.remove(e.key()) != null) return false;
        selected.put(e.key(), e);
        return true;
    }

    public boolean isSelected(GalleryEntry e) {
        return selected.containsKey(e.key());
    }

    public void selectAll(List<GalleryEntry> entries) {
        for (GalleryEntry e : entries) selected.putIfAbsent(e.key(), e);
    }

    public Map<String, GalleryEntry> selection() {
        return selected;
    }
}
