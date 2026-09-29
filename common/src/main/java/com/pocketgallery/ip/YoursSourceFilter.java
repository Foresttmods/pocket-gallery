package com.pocketgallery.ip;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.gallery.ImportSource;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The Source filter shown on Immersive Paintings' "Yours" page: All, one entry per {@link ImportSource} (Museums,
 * Photos, Wallpapers, ..., Folder), and Other (paintings not imported with Pocket Gallery). Remembered for the session.
 */
public final class YoursSourceFilter {
    /** null = All; OTHER = not imported through the gallery. */
    private static final Object OTHER = new Object();
    private static Object current = null;

    private YoursSourceFilter() {
    }

    private static List<Object> options() {
        List<Object> options = new ArrayList<>();
        options.add(null);
        options.addAll(ImportSource.all());
        options.add(OTHER);
        return options;
    }

    public static void cycle(boolean backwards) {
        List<Object> options = options();
        int i = options.indexOf(current);
        if (i < 0) i = 0;
        current = options.get((i + (backwards ? options.size() - 1 : 1)) % options.size());
    }

    public static String label() {
        if (current == null) return "All";
        if (current == OTHER) return "Other";
        return ((ImportSource) current).plural();
    }

    /** {@code source} null = painting not imported through the gallery (or before sources were recorded). */
    static boolean accepts(Object filter, ImportSource paintingSource) {
        if (filter == null) return true;
        if (filter == OTHER) return paintingSource == null;
        return paintingSource == filter;
    }

    /** Removes paintings of other sources from IP's filtered list (called from the mixin). */
    public static void apply(List<Identifier> paintings) {
        Object f = current;
        if (f == null) return;
        paintings.removeIf(id -> !accepts(f, PocketGallery.sources().sourceOf(id.toString(), IpBridge.paintingName(id))));
    }
}
