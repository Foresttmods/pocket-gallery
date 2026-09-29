package com.pocketgallery.art;

import com.pocketgallery.gallery.ImportSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * One artwork, photo or wallpaper from a web search.
 *
 * @param kind        what the import counts as ([Museum], [Photo], [Wallpaper]) and which cache folder it goes to
 * @param width       pixel size of {@link #downloadUrl} if the source reports it, else 0
 * @param thumbUrl    small image for the grid
 * @param previewUrl  medium image for the preview panel
 * @param downloadUrl the large image that gets imported
 * @param license     short license text shown in the preview (e.g. "Public domain", "CC BY-SA 4.0")
 */
public record ArtItem(ArtSource source, ImportSource kind, String id, String title, String artist, String date,
                      int width, int height, String thumbUrl, String previewUrl, String downloadUrl, String license,
                      String pageUrl) {
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final int MAX_NAME = 200;

    /** An item of the source's default kind. */
    public ArtItem(ArtSource source, String id, String title, String artist, String date, int width, int height,
                   String thumbUrl, String previewUrl, String downloadUrl, String license, String pageUrl) {
        this(source, source.defaultKind(), id, title, artist, date, width, height, thumbUrl, previewUrl, downloadUrl,
                license, pageUrl);
    }

    public String key() {
        return "art:" + source.id() + ":" + id;
    }

    /** "Title - Artist" (the painting name before the source prefix). */
    public String displayName() {
        String t = title == null || title.isBlank() ? "Untitled" : title.strip();
        String name = artist == null || artist.isBlank() ? t : t + " - " + artist.strip();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).strip() : name;
    }

    /** File extension of the download (jpg when unknown). */
    public String extension() {
        try {
            String path = URI.create(downloadUrl).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (EXTENSIONS.contains(ext)) return ext;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        return "jpg";
    }

    /** Readable, file-system safe cache file name, e.g. {@code Water Lilies - Claude Monet (16568).jpg}. */
    public String fileName() {
        return safeFileName(displayName(), 80) + " (" + safeFileName(id, 40) + ")." + extension();
    }

    /**
     * Where the downloaded image is kept: {@code <folder>/art/<museum>/...}, {@code <folder>/photos/<source>/...} or
     * {@code <folder>/wallpapers/<source>/...}. These files also show up in the Folder tab and keep their source there.
     */
    public Path cacheFile(Path imageFolder) {
        return imageFolder.resolve(kind.folderName()).resolve(source.id()).resolve(fileName());
    }

    static String safeFileName(String s, int maxLength) {
        String cleaned = (s == null ? "" : s).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ").strip();
        if (cleaned.length() > maxLength) cleaned = cleaned.substring(0, maxLength).strip();
        // Windows rejects names ending in a dot or space
        cleaned = cleaned.replaceAll("[. ]+$", "");
        return cleaned.isEmpty() ? "image" : cleaned;
    }
}
