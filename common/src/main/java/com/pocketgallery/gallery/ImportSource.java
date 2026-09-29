package com.pocketgallery.gallery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where an imported painting came from. Used for the name prefix ("[Museum] Water Lilies - Claude Monet"), which
 * makes Immersive Paintings' name-sorted "Yours" tab group paintings by source, and for the Source filter there.
 * The built-in sources are constants here; extra gallery tabs register their own with {@link #register}.
 */
public final class ImportSource {
    /** Immersive Paintings' limit on painting names. */
    public static final int MAX_NAME_LENGTH = 256;

    private static final List<ImportSource> ALL = new CopyOnWriteArrayList<>();

    public static final ImportSource MUSEUM = register("Museum", "Museums", "art/", 10);
    public static final ImportSource PHOTO = register("Photo", "Photos", "photos/", 20);
    public static final ImportSource WALLPAPER = register("Wallpaper", "Wallpapers", "wallpapers/", 30);
    /** Anything else in the image folder; always last in the filter. */
    public static final ImportSource FOLDER = register("Folder", "Folder", null, 1000);

    private final String tag;
    private final String plural;
    private final String folderPrefix;
    private final int order;

    private ImportSource(String tag, String plural, String folderPrefix, int order) {
        this.tag = tag;
        this.plural = plural;
        this.folderPrefix = folderPrefix;
        this.order = order;
    }

    /**
     * Adds a source (or returns the existing one with that tag).
     *
     * @param folderPrefix sub folder of the image folder that the source's downloads are kept in, e.g. {@code "art/"}
     *                     (files there keep their source when imported from the Folder tab); null for none
     * @param order        position in the Source filter (built-ins: 10, 20, 30; Folder: 1000)
     */
    public static synchronized ImportSource register(String tag, String plural, String folderPrefix, int order) {
        ImportSource existing = fromTag(tag);
        if (existing != null) return existing;
        ImportSource source = new ImportSource(tag, plural, folderPrefix, order);
        List<ImportSource> sorted = new ArrayList<>(ALL);
        sorted.add(source);
        sorted.sort(Comparator.comparingInt(s -> s.order));
        ALL.clear();
        ALL.addAll(sorted);
        return source;
    }

    /** All sources in filter order. */
    public static List<ImportSource> all() {
        return List.copyOf(ALL);
    }

    public String tag() {
        return tag;
    }

    public String plural() {
        return plural;
    }

    public String prefix() {
        return "[" + tag + "] ";
    }

    /** The image folder's sub folder for this source's downloads ("art", "photos", ...). */
    public String folderName() {
        if (folderPrefix == null) return tag.toLowerCase(Locale.ROOT);
        return folderPrefix.endsWith("/") ? folderPrefix.substring(0, folderPrefix.length() - 1) : folderPrefix;
    }

    /** The painting name sent to Immersive Paintings. */
    public String paintingName(String baseName, boolean withPrefix) {
        String base = baseName == null || baseName.isBlank() ? "Untitled" : baseName.strip();
        String name = withPrefix ? prefix() + base : base;
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    /** The source named by a "[Tag] " prefix, or null. */
    public static ImportSource fromName(String paintingName) {
        if (paintingName == null) return null;
        for (ImportSource s : ALL) {
            if (paintingName.startsWith(s.prefix())) return s;
        }
        return null;
    }

    public static ImportSource fromTag(String tag) {
        if (tag == null) return null;
        for (ImportSource s : ALL) {
            if (s.tag.equalsIgnoreCase(tag)) return s;
        }
        return null;
    }

    /**
     * Source of a file in the image folder: the gallery keeps downloads in {@code art/}, {@code photos/},
     * {@code wallpapers/} (and whatever extra sources register), so importing them later from the Folder tab keeps
     * their origin.
     */
    public static ImportSource fromRelativePath(String relativePath) {
        String p = relativePath == null ? "" : relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (ImportSource s : ALL) {
            if (s.folderPrefix != null && p.startsWith(s.folderPrefix)) return s;
        }
        return FOLDER;
    }

    @Override
    public String toString() {
        return tag;
    }
}
