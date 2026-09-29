package com.pocketgallery.folder;

import com.pocketgallery.image.ImageLoader;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Recursively lists supported images below a folder. Runs on a worker thread; unreadable sub folders are skipped.
 */
public final class FolderScanner {
    private FolderScanner() {
    }

    public record LocalImage(Path path, String relativePath, long size, long lastModified) {
        public String fileName() {
            return path.getFileName().toString();
        }

        public boolean isGif() {
            return ImageLoader.extension(fileName()).equals("gif");
        }
    }

    public static List<LocalImage> scan(Path root, BooleanSupplier cancelled) throws IOException {
        List<LocalImage> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                // skip hidden folders like .thumbnails
                return !dir.equals(root) && name.startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && ImageLoader.isSupported(file.getFileName().toString())) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    result.add(new LocalImage(file, rel, attrs.size(), attrs.lastModifiedTime().toMillis()));
                }
                return cancelled.getAsBoolean() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparing(i -> i.relativePath().toLowerCase(Locale.ROOT)));
        return result;
    }

    /** Case-insensitive match of all whitespace separated words against the relative path. */
    public static boolean matches(LocalImage image, String search) {
        if (search == null || search.isBlank()) return true;
        String hay = image.relativePath().toLowerCase(Locale.ROOT);
        for (String word : search.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(word)) return false;
        }
        return true;
    }
}
