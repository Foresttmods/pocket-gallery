package com.pocketgallery.anim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent mapping "IP painting id -> local GIF + the crop that was applied on upload", stored as JSON in the
 * config folder. Thread safe.
 */
public final class AnimatedIndex {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * @param file absolute path of the GIF
     * @param srcWidth/srcHeight GIF logical screen size at upload time (used to detect a changed file)
     * @param colors palette size of the pixel look (0 = all colours; missing in older index files)
     */
    public record Entry(String file, int cropX, int cropY, int cropWidth, int cropHeight, int srcWidth, int srcHeight,
                        int colors) {
        public Entry(String file, int cropX, int cropY, int cropWidth, int cropHeight, int srcWidth, int srcHeight) {
            this(file, cropX, cropY, cropWidth, cropHeight, srcWidth, srcHeight, 0);
        }
    }

    private final Path path;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public AnimatedIndex(Path path) {
        this.path = path;
    }

    public synchronized void load() {
        entries.clear();
        if (!Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, Entry> loaded = GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, Entry>>() {
            }.getType());
            if (loaded != null) {
                loaded.forEach((k, v) -> {
                    if (k != null && v != null && v.file() != null) entries.put(k, v);
                });
            }
        } catch (IOException | JsonParseException e) {
            // corrupt index: start fresh, animations simply won't play for old uploads
        }
    }

    public synchronized void put(String paintingId, Entry entry) throws IOException {
        entries.put(paintingId, entry);
        save();
    }

    public synchronized Entry get(String paintingId) {
        return entries.get(paintingId);
    }

    public synchronized boolean contains(String paintingId) {
        return entries.containsKey(paintingId);
    }

    public synchronized int size() {
        return entries.size();
    }

    private void save() throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(entries, writer);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }
}
