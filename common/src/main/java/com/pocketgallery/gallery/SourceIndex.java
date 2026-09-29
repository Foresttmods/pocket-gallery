package com.pocketgallery.gallery;

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
 * Persistent "IP painting id -> where it was imported from" record ({@code config/pocket_gallery_sources.json}).
 * Lets the Source filter work even for paintings imported without the name prefix. Thread safe.
 */
public final class SourceIndex {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * @param source  {@link ImportSource} tag, e.g. "Museum"
     * @param origin  collection / site, e.g. "Art Institute of Chicago" (may be empty)
     * @param license license text for downloaded images (may be empty)
     * @param url     page of the original (may be empty)
     */
    public record Entry(String source, String origin, String license, String url) {
    }

    private final Path path;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public SourceIndex(Path path) {
        this.path = path;
    }

    public synchronized void load() {
        entries.clear();
        if (path == null || !Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, Entry> loaded = GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, Entry>>() {
            }.getType());
            if (loaded != null) {
                loaded.forEach((k, v) -> {
                    if (k != null && v != null && v.source() != null) entries.put(k, v);
                });
            }
        } catch (IOException | JsonParseException e) {
            // corrupt index: the filter falls back to the name prefix
        }
    }

    public synchronized void put(String paintingId, Entry entry) throws IOException {
        entries.put(paintingId, entry);
        save();
    }

    public synchronized Entry get(String paintingId) {
        return entries.get(paintingId);
    }

    /** Source from the index, else from the name prefix, else null ("other"). */
    public ImportSource sourceOf(String paintingId, String paintingName) {
        Entry e = get(paintingId);
        ImportSource s = e == null ? null : ImportSource.fromTag(e.source());
        return s != null ? s : ImportSource.fromName(paintingName);
    }

    public synchronized int size() {
        return entries.size();
    }

    private void save() throws IOException {
        if (path == null) return;
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(entries, writer);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }
}
