package com.pocketgallery.anim;

import com.mojang.blaze3d.platform.NativeImage;
import com.pocketgallery.PocketGallery;
import com.pocketgallery.Workers;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.ip.IpBridge;
import com.pocketgallery.ip.IpCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side animation of paintings that were uploaded from GIFs. Immersive Paintings itself only stores the
 * first frame; we swap the pixels of IP's own {@link DynamicTexture}s (FULL/HALF/QUARTER level of detail) with the
 * GIF's frames, so other players still see the still first frame.
 *
 * <p>Only paintings currently present in the world are animated, frames are built off-thread and bounded by the
 * frame cap / memory budget from the config.</p>
 */
public final class AnimationManager {
    private static final String[] ANIMATED_SIZES = {"FULL", "HALF", "QUARTER"};
    private static final long UNLOAD_AFTER_MS = 30_000;
    private static final long RETRY_FAILED_MS = 60_000;
    private static final int SCAN_INTERVAL_TICKS = 10;

    private static AnimationManager instance;

    private final AnimatedIndex index;
    private final Map<Identifier, Anim> active = new HashMap<>();
    private final Map<Identifier, Loading> loading = new HashMap<>();
    private final Map<Identifier, Long> lastVisible = new HashMap<>();
    private final Map<Identifier, Long> failedUntil = new HashMap<>();
    /** Texture id -> tick it was first seen in IP's texture map (IP registers it one task later). */
    private final Map<Identifier, Long> textureFirstSeen = new HashMap<>();
    private long ticks;
    private boolean loggedFailure;

    private record Loading(AtomicBoolean cancelled, long reservedBytes) {
    }

    private static final class Anim {
        final Identifier painting;
        final FrameBuilder.Frames frames;
        final List<Identifier> textures;
        final int[] endTimes;
        final long start = System.currentTimeMillis();
        int shownFrame = 0;

        Anim(Identifier painting, FrameBuilder.Frames frames, List<Identifier> textures) {
            this.painting = painting;
            this.frames = frames;
            this.textures = textures;
            this.endTimes = new int[frames.count()];
            int acc = 0;
            for (int i = 0; i < endTimes.length; i++) {
                acc += frames.delaysMs()[i];
                endTimes[i] = acc;
            }
        }

        int frameAt(long now) {
            int t = (int) ((now - start) % frames.totalDurationMs());
            int i = Arrays.binarySearch(endTimes, t + 1);
            return Math.min(endTimes.length - 1, i >= 0 ? i : -i - 1);
        }
    }

    private AnimationManager(AnimatedIndex index) {
        this.index = index;
    }

    public static void init(Path indexFile) {
        AnimatedIndex idx = new AnimatedIndex(indexFile);
        idx.load();
        instance = new AnimationManager(idx);
    }

    public static AnimationManager get() {
        return instance;
    }

    public AnimatedIndex index() {
        return index;
    }

    public int activeCount() {
        return active.size();
    }

    // ------------------------------------------------------------------ client tick (main thread)

    public void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        GalleryConfig config = PocketGallery.config();
        ClientLevel level = mc.level;
        if (level == null || !config.animateGifs || config.maxAnimatedPaintings <= 0 || !IpCompat.isAvailable()
                || !IpBridge.animationSupported() || index.size() == 0) {
            if (!active.isEmpty() || !loading.isEmpty()) stopAll(level != null);
            return;
        }
        if (ticks % SCAN_INTERVAL_TICKS != 0) return;
        long now = System.currentTimeMillis();

        // which indexed GIF paintings are placed in the loaded world?
        Set<Identifier> visible = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            Identifier motive = IpBridge.motiveOf(entity);
            if (motive != null && index.contains(motive.toString())) visible.add(motive);
        }
        for (Identifier id : visible) lastVisible.put(id, now);

        // refresh texture ids of running animations; stop those whose painting/textures went away
        for (Iterator<Map.Entry<Identifier, Anim>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Identifier, Anim> e = it.next();
            Map<String, Identifier> current = IpBridge.textureIds(e.getKey());
            boolean texturesStillThere = current.values().containsAll(e.getValue().textures);
            Long seen = lastVisible.get(e.getKey());
            if (!texturesStillThere) {
                it.remove();
            } else if (seen == null || now - seen > UNLOAD_AFTER_MS) {
                showFrame(e.getValue(), 0); // leave the still image behind
                it.remove();
            }
        }

        // start new animations while slots and memory are available
        for (Identifier id : visible) {
            if (active.containsKey(id) || loading.containsKey(id)) continue;
            if (failedUntil.getOrDefault(id, 0L) > now) continue;
            if (active.size() + loading.size() >= config.maxAnimatedPaintings) break;
            tryStart(id, config);
        }

        // forget old texture sightings
        textureFirstSeen.values().removeIf(t -> ticks - t > 20L * 60 * 5);
    }

    private void tryStart(Identifier painting, GalleryConfig config) {
        AnimatedIndex.Entry entry = index.get(painting.toString());
        if (entry == null) return;
        Map<String, Identifier> textures = IpBridge.textureIds(painting);
        if (!textures.containsKey("FULL")) return; // IP hasn't loaded the image yet

        List<Identifier> ids = new ArrayList<>();
        List<FrameBuilder.Target> targets = new ArrayList<>();
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (String size : ANIMATED_SIZES) {
            Identifier tex = textures.get(size);
            if (tex == null || ids.contains(tex)) continue;
            Long first = textureFirstSeen.putIfAbsent(tex, ticks);
            if (first == null || ticks - first < 2) return; // registration task may still be pending; retry later
            AbstractTexture texture = textureManager.getTexture(tex);
            if (!(texture instanceof DynamicTexture dynamic) || dynamic.getPixels() == null || dynamic.getPixels().isClosed()) {
                return;
            }
            ids.add(tex);
            targets.add(new FrameBuilder.Target(dynamic.getPixels().getWidth(), dynamic.getPixels().getHeight()));
        }
        if (ids.isEmpty()) return;

        long budget = (long) config.maxAnimationMemoryMb * 1024 * 1024 - usedBytes();
        if (budget <= 0) return;
        Path file = Path.of(entry.file());
        if (!Files.isRegularFile(file)) {
            fail(painting, "GIF file not found: " + file);
            return;
        }

        AtomicBoolean cancelled = new AtomicBoolean();
        long perFrame = targets.stream().mapToLong(FrameBuilder.Target::bytesPerFrame).sum();
        long reserve = Math.min(budget, perFrame * config.maxGifFrames);
        loading.put(painting, new Loading(cancelled, reserve));
        int maxFrames = config.maxGifFrames;
        Workers.ANIMATION.submit(() -> {
            FrameBuilder.Frames frames = null;
            String error = null;
            try {
                byte[] data = Files.readAllBytes(file);
                frames = FrameBuilder.build(data, entry, targets, maxFrames, reserve, cancelled::get);
                if (frames == null && !cancelled.get()) error = "not animated or does not fit the memory budget";
            } catch (Throwable t) {
                error = t.toString();
            }
            FrameBuilder.Frames result = frames;
            String err = error;
            Minecraft.getInstance().execute(() -> finishLoading(painting, ids, result, err, cancelled));
        });
    }

    private void finishLoading(Identifier painting, List<Identifier> ids, FrameBuilder.Frames frames, String error, AtomicBoolean cancelled) {
        Loading l = loading.get(painting);
        if (l == null || l.cancelled() != cancelled) return; // superseded
        loading.remove(painting);
        if (cancelled.get()) return;
        if (frames == null) {
            fail(painting, error);
            return;
        }
        active.put(painting, new Anim(painting, frames, ids));
    }

    private void fail(Identifier painting, String reason) {
        failedUntil.put(painting, System.currentTimeMillis() + RETRY_FAILED_MS);
        if (!loggedFailure) {
            loggedFailure = true;
            PocketGallery.LOG.warn("Could not animate painting {}: {} (further failures are not logged)", painting, reason);
        }
    }

    private long usedBytes() {
        long sum = 0;
        for (Anim a : active.values()) sum += a.frames.bytes();
        for (Loading l : loading.values()) sum += l.reservedBytes();
        return sum;
    }

    /** Drops everything; restores the first frame when textures are still alive. */
    public void stopAll(boolean restore) {
        if (restore) {
            for (Anim a : active.values()) showFrame(a, 0);
        }
        active.clear();
        loading.values().forEach(l -> l.cancelled().set(true));
        loading.clear();
        lastVisible.clear();
        textureFirstSeen.clear();
    }

    // ------------------------------------------------------------------ per frame (render thread)

    public void onRenderFrame() {
        if (active.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Anim> it = active.values().iterator(); it.hasNext(); ) {
            Anim anim = it.next();
            int frame = anim.frameAt(now);
            if (frame != anim.shownFrame && !showFrame(anim, frame)) {
                it.remove(); // texture changed size or was replaced; the next scan restarts it
            }
        }
    }

    /** Writes a frame into IP's textures. Returns false when the textures no longer match. */
    private boolean showFrame(Anim anim, int frame) {
        // IP releases the textures when a painting is deleted; never touch (and thereby re-create) released ids
        if (!IpBridge.hasPainting(anim.painting)) return false;
        var textureManager = Minecraft.getInstance().getTextureManager();
        try {
            for (int t = 0; t < anim.textures.size(); t++) {
                AbstractTexture texture = textureManager.getTexture(anim.textures.get(t));
                if (!(texture instanceof DynamicTexture dynamic)) return false;
                NativeImage pixels = dynamic.getPixels();
                FrameBuilder.Target target = anim.frames.targets().get(t);
                if (pixels == null || pixels.isClosed() || pixels.format() != NativeImage.Format.RGBA
                        || pixels.getWidth() != target.width() || pixels.getHeight() != target.height()) {
                    return false;
                }
                int[] data = anim.frames.frames().get(t).get(frame);
                MemoryUtil.memIntBuffer(pixels.getPointer(), data.length).put(data);
                dynamic.upload();
            }
            anim.shownFrame = frame;
            return true;
        } catch (RuntimeException e) {
            if (!loggedFailure) {
                loggedFailure = true;
                PocketGallery.LOG.warn("GIF animation frame update failed", e);
            }
            return false;
        }
    }
}
