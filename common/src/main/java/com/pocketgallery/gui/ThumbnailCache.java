package com.pocketgallery.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.pocketgallery.PocketGallery;
import com.pocketgallery.Workers;
import com.pocketgallery.image.ImageOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazily loaded GUI textures (thumbnails and previews) with an LRU limit. Decoding happens on worker threads;
 * texture creation on the client thread. Requests for images that scrolled out of view before a worker got to
 * them are skipped, which keeps fast scrolling through thousands of files responsive.
 */
public final class ThumbnailCache {
    /** Loads the source image; runs on a worker thread. */
    @FunctionalInterface
    public interface Loader {
        BufferedImage load() throws Exception;
    }

    public record Thumb(Identifier texture, int width, int height) {
    }

    private enum State {LOADING, FAILED}

    private static final long STALE_REQUEST_MS = 750;
    private static final AtomicLong COUNTER = new AtomicLong();

    private final String prefix;
    private final int maxSide;
    private final int capacity;
    private final Path diskCache;
    private final ExecutorService executor;
    /** Nearest-neighbour sampling (pixel-look previews) instead of smooth. */
    private final boolean crisp;
    private final LinkedHashMap<String, Thumb> ready = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, State> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();
    private boolean closed;

    /**
     * @param diskCache folder for cached PNG thumbnails, or null for memory only
     */
    public ThumbnailCache(String prefix, int maxSide, int capacity, Path diskCache, ExecutorService executor) {
        this(prefix, maxSide, capacity, diskCache, executor, false);
    }

    public ThumbnailCache(String prefix, int maxSide, int capacity, Path diskCache, ExecutorService executor, boolean crisp) {
        this.prefix = prefix;
        this.maxSide = maxSide;
        this.capacity = capacity;
        this.diskCache = diskCache;
        this.executor = executor;
        this.crisp = crisp;
    }

    /** Returns the thumbnail if ready, otherwise schedules loading and returns null. Client thread only. */
    public Thumb get(String key, Loader loader) {
        if (closed) return null;
        Thumb t = ready.get(key);
        if (t != null) return t;
        lastRequest.put(key, System.currentTimeMillis());
        if (pending.containsKey(key)) return null;
        pending.put(key, State.LOADING);
        executor.execute(() -> load(key, loader));
        return null;
    }

    public boolean failed(String key) {
        return pending.get(key) == State.FAILED;
    }

    /** Forget failures so they get retried (e.g. after a rescan). */
    public void clearFailures() {
        pending.values().removeIf(s -> s == State.FAILED);
    }

    private void load(String key, Loader loader) {
        if (closed) return;
        Long requested = lastRequest.get(key);
        if (requested == null || System.currentTimeMillis() - requested > STALE_REQUEST_MS) {
            pending.remove(key); // scrolled away; will be requested again when visible
            return;
        }
        try {
            BufferedImage img = readDisk(key);
            if (img == null) {
                BufferedImage src = loader.load();
                if (src == null) throw new IOException("undecodable");
                img = ImageOps.fitWithin(src, maxSide, maxSide);
                writeDisk(key, img);
            }
            NativeImage nativeImage = toNative(img);
            int w = img.getWidth(), h = img.getHeight();
            Minecraft.getInstance().execute(() -> upload(key, nativeImage, w, h));
        } catch (Throwable e) {
            pending.put(key, State.FAILED);
            if (!(e instanceof IOException)) PocketGallery.LOG.debug("Thumbnail failed for {}", key, e);
        }
    }

    private void upload(String key, NativeImage image, int w, int h) {
        if (closed) {
            image.close();
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath(PocketGallery.MOD_ID, prefix + "/" + COUNTER.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(id, image, crisp ? FilterMode.NEAREST : FilterMode.LINEAR));
        ready.put(key, new Thumb(id, w, h));
        pending.remove(key);
        evict();
    }

    private void evict() {
        Iterator<Map.Entry<String, Thumb>> it = ready.entrySet().iterator();
        while (ready.size() > capacity && it.hasNext()) {
            Map.Entry<String, Thumb> eldest = it.next();
            Minecraft.getInstance().getTextureManager().release(eldest.getValue().texture());
            it.remove();
        }
    }

    /** Releases all textures. Client thread. */
    public void close() {
        closed = true;
        for (Thumb t : ready.values()) Minecraft.getInstance().getTextureManager().release(t.texture());
        ready.clear();
        pending.clear();
        lastRequest.clear();
    }

    private Path diskFile(String key) {
        if (diskCache == null) return null;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            String name = HexFormat.of().formatHex(sha.digest((key + "|" + maxSide).getBytes(StandardCharsets.UTF_8)));
            return diskCache.resolve(name.substring(0, 2)).resolve(name + ".png");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private BufferedImage readDisk(String key) {
        Path file = diskFile(key);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private void writeDisk(String key, BufferedImage img) {
        Path file = diskFile(key);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(img, "png", file.toFile());
        } catch (IOException e) {
            // cache is best effort
        }
    }

    static NativeImage toNative(BufferedImage img) {
        int[] abgr = ImageOps.toAbgr(img);
        NativeImage n = new NativeImage(img.getWidth(), img.getHeight(), false);
        int w = img.getWidth();
        for (int i = 0; i < abgr.length; i++) n.setPixelABGR(i % w, i / w, abgr[i]);
        return n;
    }

    /**
     * DynamicTexture with an explicit filter: linear so downscaled thumbnails don't shimmer, nearest for pixel-look
     * previews that are drawn enlarged.
     */
    static final class SmoothTexture extends DynamicTexture {
        SmoothTexture(Identifier id, NativeImage image, FilterMode filter) {
            super(id::toString, image);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(filter);
        }
    }
}
