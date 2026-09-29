package com.pocketgallery.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.pocketgallery.art.MuseumFilter;
import com.pocketgallery.art.PhotoTopic;
import com.pocketgallery.image.PixelLook;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Client config stored as {@code config/pocket_gallery.json}. Plain Gson POJO so it is trivially testable.
 */
public final class GalleryConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Image folder; empty means {@code <gameDir>/paintings}. */
    public String folderPath = "";
    /** Long side of an imported painting in blocks (1-16). */
    public int longSideBlocks = 4;
    /** Center-crop images to the chosen block aspect ratio (otherwise stretch). */
    public boolean cropToFit = true;
    /** Animate paintings uploaded from GIFs on this client. */
    public boolean animateGifs = true;
    public int maxGifFrames = 256;
    /** Max number of GIF paintings animated at the same time. */
    public int maxAnimatedPaintings = 16;
    /** Memory budget (MB) for decoded animation frames across all animated paintings. */
    public int maxAnimationMemoryMb = 512;
    /** Museums tab: only artworks finished in or before this year (keeps modern art out). */
    public int artLatestYear = 1925;
    /** Museums tab: which collections to search. */
    public MuseumFilter museumFilter = MuseumFilter.ALL;
    /** Photos tab: current topic. */
    public PhotoTopic photoTopic = PhotoTopic.LANDSCAPES;
    /** Free Pixabay API key (pixabay.com/api/docs); only the Pixabay topics need it. */
    public String pixabayApiKey = "";
    /** Start painting names with their source ("[Museum] ...") so IP's "Yours" tab groups them. */
    public boolean sourcePrefix = true;
    /** Pixel look for newly shown images: pixels per block (0 = original resolution) and palette size (0 = all). */
    public int defaultPixelsPerBlock = 0;
    public int defaultColors = 0;

    /** Clamp values into their valid ranges (hand-edited files may contain anything). */
    public GalleryConfig sanitize() {
        if (folderPath == null) folderPath = "";
        if (pixabayApiKey == null) pixabayApiKey = "";
        pixabayApiKey = pixabayApiKey.strip();
        longSideBlocks = clamp(longSideBlocks, 1, 16);
        maxGifFrames = clamp(maxGifFrames, 1, 4096);
        maxAnimatedPaintings = clamp(maxAnimatedPaintings, 0, 256);
        maxAnimationMemoryMb = clamp(maxAnimationMemoryMb, 16, 8192);
        artLatestYear = clamp(artLatestYear, 1200, 2100);
        if (museumFilter == null) museumFilter = MuseumFilter.ALL;
        if (photoTopic == null) photoTopic = PhotoTopic.LANDSCAPES;
        PixelLook look = defaultLook();
        defaultPixelsPerBlock = look.pixelsPerBlock();
        defaultColors = look.colors();
        return this;
    }

    public PixelLook defaultLook() {
        return new PixelLook(defaultPixelsPerBlock, defaultColors);
    }

    public GalleryConfig copy() {
        return GSON.fromJson(GSON.toJson(this), GalleryConfig.class);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static GalleryConfig fromJson(String json) {
        GalleryConfig config = null;
        try {
            config = GSON.fromJson(json, GalleryConfig.class);
        } catch (JsonParseException ignored) {
            // fall through to defaults
        }
        return (config == null ? new GalleryConfig() : config).sanitize();
    }

    /** Loads the config, or returns defaults when the file is missing or broken. */
    public static GalleryConfig load(Path file) {
        if (!Files.isRegularFile(file)) return new GalleryConfig();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            GalleryConfig config = GSON.fromJson(reader, GalleryConfig.class);
            return (config == null ? new GalleryConfig() : config).sanitize();
        } catch (IOException | JsonParseException e) {
            return new GalleryConfig();
        }
    }

    /** Writes atomically (temp file + move) so a crash never leaves a half-written config. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
