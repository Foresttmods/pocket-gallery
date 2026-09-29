package com.pocketgallery.gallery;

import com.pocketgallery.config.GalleryConfig;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * One tab of the gallery (Folder, Museums, Photos, ...). A tab owns its results and search text (both outlive the
 * screen, so reopening the gallery is instant) and adds its own buttons to the row under the search box.
 * Everything runs on the client thread.
 */
public abstract class GalleryTab {
    /** A line of status text with its colour. */
    public record Status(String text, int color) {
        public static final int NORMAL = 0xFFCCCCCC;
        public static final int ERROR = 0xFFFF6060;
        public static final int NOTE = 0xFFE0C060;
    }

    private final String id;
    private String search = "";

    protected GalleryTab(String id) {
        this.id = id;
    }

    /** Stable id; also names the translation keys {@code pocket_gallery.gui.tab.<id>} and {@code ...search.<id>}. */
    public final String id() {
        return id;
    }

    public Component label() {
        return Component.translatable("pocket_gallery.gui.tab." + id);
    }

    public Component searchHint() {
        return Component.translatable("pocket_gallery.gui.search." + id);
    }

    public final String search() {
        return search;
    }

    /** Called while the player types. */
    public void setSearch(String text, TabHost host) {
        search = text == null ? "" : text;
    }

    /** True for web searches: a Search button, Enter searches, and more pages load while paging. */
    public abstract boolean isRemote();

    public abstract List<GalleryEntry> entries();

    /** The tab became visible (also when the gallery opens on it). */
    public void onShown(TabHost host) {
    }

    /**
     * Adds the tab's buttons to the row below the search box.
     *
     * @return the x where the status text starts (right of the buttons)
     */
    public int addControls(TabHost host, int x, int y) {
        return x;
    }

    public abstract Status status();

    /** Text in the empty grid. */
    public abstract String emptyMessage();

    /** Settings were saved: search again if something this tab depends on changed. */
    public void onConfigChanged(GalleryConfig before, GalleryConfig after) {
    }

    // ---------------------------------------------------------------- web searches (remote tabs)

    public boolean searched() {
        return false;
    }

    public boolean more() {
        return false;
    }

    public boolean loading() {
        return false;
    }

    public String error() {
        return null;
    }

    public void newSearch() {
    }

    public void fetchNextPage() {
    }
}
