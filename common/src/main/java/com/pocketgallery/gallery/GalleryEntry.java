package com.pocketgallery.gallery;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.art.ArtItem;
import com.pocketgallery.folder.FolderScanner;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One selectable image in the gallery. The built-in kinds are {@link Local} (a file in the image folder) and
 * {@link Art} (a museum artwork, photo or wallpaper from a web source); extra gallery tabs bring their own.
 * The byte/file methods block and run on worker threads.
 */
public interface GalleryEntry {
    /** Unique key (used for selection and thumbnail caching). */
    String key();

    /** Default painting name (without the source prefix). */
    String name();

    boolean isGif();

    /** Pixel size if already known without decoding (API metadata), else 0. */
    int knownWidth();

    int knownHeight();

    /** Where the image comes from (for the painting name prefix and the Source filter). */
    ImportSource source();

    /** Thumbnail cache key: {@link #key()} plus whatever invalidates the thumbnail (e.g. a file's date). */
    default String thumbKey() {
        return key();
    }

    /** A small version of the image for the grid. */
    byte[] thumbnailBytes() throws IOException;

    /** A medium-size version for the preview panel: the local / cached file, else the source's preview size. */
    byte[] previewBytes() throws IOException;

    /** The full image if it is already on disk (folder file or earlier download), else null. */
    @Nullable Path localFile();

    /** The full image on disk, downloading it first when needed. */
    Path fetchFile() throws IOException;

    /** Grey lines under the name in the grid tooltip. */
    default List<String> tooltipLines() {
        return List.of();
    }

    /** Lines under the size information in the preview panel. */
    default List<InfoLine> previewLines() {
        return List.of();
    }

    /** Whether the import gets Immersive Paintings' NSFW flag. */
    default boolean nsfw() {
        return false;
    }

    /** What {@code config/pocket_gallery_sources.json} records about the import. */
    SourceIndex.Entry sourceRecord();

    record InfoLine(String text, int color) {
    }

    /** A file in the image folder. */
    record Local(FolderScanner.LocalImage image) implements GalleryEntry {
        @Override
        public String key() {
            return "file:" + image.path().toAbsolutePath();
        }

        @Override
        public String name() {
            String n = image.fileName();
            int dot = n.lastIndexOf('.');
            n = dot > 0 ? n.substring(0, dot) : n;
            return n.length() > 200 ? n.substring(0, 200) : n;
        }

        @Override
        public boolean isGif() {
            return image.isGif();
        }

        @Override
        public int knownWidth() {
            return 0;
        }

        @Override
        public int knownHeight() {
            return 0;
        }

        @Override
        public ImportSource source() {
            return ImportSource.fromRelativePath(image.relativePath());
        }

        @Override
        public String thumbKey() {
            return key() + "|" + image.size() + "|" + image.lastModified();
        }

        @Override
        public byte[] thumbnailBytes() throws IOException {
            return Files.readAllBytes(image.path());
        }

        @Override
        public byte[] previewBytes() throws IOException {
            return Files.readAllBytes(image.path());
        }

        @Override
        public Path localFile() {
            return image.path();
        }

        @Override
        public Path fetchFile() {
            return image.path();
        }

        @Override
        public List<String> tooltipLines() {
            return List.of(image.relativePath());
        }

        @Override
        public SourceIndex.Entry sourceRecord() {
            return new SourceIndex.Entry(source().tag(), "", "", image.relativePath());
        }

        public Path path() {
            return image.path();
        }
    }

    /** A museum artwork, photo or wallpaper found by a web search. */
    record Art(ArtItem item) implements GalleryEntry {
        @Override
        public String key() {
            return item.key();
        }

        @Override
        public String name() {
            return item.displayName();
        }

        @Override
        public boolean isGif() {
            return false;
        }

        @Override
        public int knownWidth() {
            return item.width();
        }

        @Override
        public int knownHeight() {
            return item.height();
        }

        @Override
        public ImportSource source() {
            return item.kind();
        }

        private Path cacheFile() {
            return item.cacheFile(PocketGallery.imageFolder());
        }

        @Override
        public byte[] thumbnailBytes() throws IOException {
            Path cached = cacheFile();
            if (Files.isRegularFile(cached)) return Files.readAllBytes(cached);
            return PocketGallery.web().fetchBytes(item.thumbUrl(), item.source().displayName());
        }

        @Override
        public byte[] previewBytes() throws IOException {
            Path cached = cacheFile();
            if (Files.isRegularFile(cached)) return Files.readAllBytes(cached);
            return PocketGallery.web().fetchBytes(item.previewUrl(), item.source().displayName());
        }

        @Override
        public @Nullable Path localFile() {
            Path cached = cacheFile();
            return Files.isRegularFile(cached) ? cached : null;
        }

        @Override
        public Path fetchFile() throws IOException {
            Path target = cacheFile();
            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                PocketGallery.web().download(item.downloadUrl(), target, item.source().displayName());
            }
            return target;
        }

        @Override
        public List<String> tooltipLines() {
            String date = item.date() == null || item.date().isBlank() ? "" : item.date() + " - ";
            return List.of(date + item.source().displayName());
        }

        @Override
        public List<InfoLine> previewLines() {
            List<InfoLine> lines = new ArrayList<>();
            if (item.date() != null && !item.date().isBlank()) lines.add(new InfoLine(item.date(), 0xFFAAAAAA));
            lines.add(new InfoLine(item.source().displayName(), 0xFFAAAAAA));
            lines.add(new InfoLine(item.license(), 0xFF888888));
            return lines;
        }

        @Override
        public SourceIndex.Entry sourceRecord() {
            return new SourceIndex.Entry(source().tag(), item.source().displayName(), item.license(), item.pageUrl());
        }
    }
}
