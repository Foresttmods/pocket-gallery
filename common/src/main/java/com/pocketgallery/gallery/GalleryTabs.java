package com.pocketgallery.gallery;

import com.pocketgallery.BuildInfo;
import com.pocketgallery.PocketGallery;
import com.pocketgallery.tabs.FolderTab;
import com.pocketgallery.tabs.MuseumsTab;
import com.pocketgallery.tabs.PhotosTab;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The gallery's tabs in display order. The public build has Folder, Museums and Photos; other tabs (the personal
 * edition's Danbooru tab, or a future addon) are added with {@link #register}.
 */
public final class GalleryTabs {
    /** Class that adds the personal edition's extra tabs; only present in jars built with -Pedition=personal. */
    private static final String PERSONAL_EXTENSION = "com.pocketgallery.personal.PersonalEdition";

    private static final List<GalleryTab> TABS = new CopyOnWriteArrayList<>();

    private GalleryTabs() {
    }

    public static void registerBuiltIn() {
        if (!TABS.isEmpty()) return;
        register(new FolderTab());
        register(new MuseumsTab());
        register(new PhotosTab());
        if ("personal".equals(BuildInfo.EDITION)) {
            try {
                Class.forName(PERSONAL_EXTENSION).getMethod("init").invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                PocketGallery.LOG.error("Personal edition extras failed to load", e);
            }
        }
    }

    public static void register(GalleryTab tab) {
        for (GalleryTab t : TABS) {
            if (t.id().equals(tab.id())) throw new IllegalArgumentException("Duplicate gallery tab " + tab.id());
        }
        TABS.add(tab);
    }

    public static List<GalleryTab> all() {
        return List.copyOf(TABS);
    }

    public static GalleryTab first() {
        return TABS.getFirst();
    }

    public static GalleryTab byId(String id) {
        for (GalleryTab t : TABS) if (t.id().equals(id)) return t;
        return null;
    }
}
