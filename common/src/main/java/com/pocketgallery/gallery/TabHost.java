package com.pocketgallery.gallery;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** What a {@link GalleryTab} may do with the gallery screen (implemented by the gallery screen). */
public interface TabHost {
    <T extends AbstractWidget> T addTabWidget(T widget);

    Font font();

    /** The gallery screen, e.g. as the parent of a sub screen. */
    Screen screen();

    /** Shows another screen (a picker, a confirmation...); it should return to {@link #screen()}. */
    void open(Screen screen);

    /** Starts a new search of the current tab from page 1. */
    void runSearch();

    /** Back to page 1 without searching (e.g. after the local filter changed). */
    void resetPage();

    /** Rebuilds the gallery's widgets (e.g. after a button label changed its width). */
    void rebuild();

    /** Forgets failed thumbnails and cached sizes, so a rescan tries them again. */
    void clearImageCaches();

    /** Puts the keyboard focus into the search box. */
    void focusSearch();
}
