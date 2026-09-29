package com.pocketgallery.upload;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.Workers;
import com.pocketgallery.anim.AnimatedIndex;
import com.pocketgallery.anim.AnimationManager;
import com.pocketgallery.gallery.GalleryEntry;
import com.pocketgallery.image.GifDecoder;
import com.pocketgallery.image.ImageLoader;
import com.pocketgallery.image.PaintingPipeline;
import com.pocketgallery.ip.IpBridge;
import com.pocketgallery.ip.IpCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Background import queue. Images are prepared (download, decode, crop, resize, PNG encode) on a worker thread,
 * one ahead of the upload, and uploaded strictly one at a time because the server keeps a single pending image per
 * player. Completion is detected through the register response (mixin) or, as a fallback, by the new painting
 * appearing in IP's client painting list.
 */
public final class UploadQueue {
    private static final UploadQueue INSTANCE = new UploadQueue();
    /** Wait this long after IP's packet queue drained before declaring the server unresponsive. */
    private static final long RESPONSE_TIMEOUT_MS = 60_000;
    /** How long to wait for the register response after the new painting already showed up. */
    private static final long FALLBACK_GRACE_MS = 2_000;

    public static UploadQueue get() {
        return INSTANCE;
    }

    /** Snapshot of the settings at the time the user pressed Import (per image: the pixel look differs). */
    public record Settings(PaintingPipeline.Settings pipeline, boolean sourcePrefix) {
    }

    /** One image to import with its own settings. */
    public record Request(GalleryEntry entry, Settings settings) {
    }

    /** @param paintingName the final name sent to Immersive Paintings (with the source prefix, if enabled) */
    private record Job(GalleryEntry entry, Settings settings, String paintingName) {
    }

    private record Prepared(Job job, byte[] png, PaintingPipeline.Plan plan, AnimatedIndex.Entry gif, String error) {
    }

    // all fields are only touched on the client thread, except `awaiting` (read by the network thread)
    private final Deque<Job> pending = new ArrayDeque<>();
    private Future<Prepared> preparing;
    private Prepared current;
    private volatile boolean awaiting;
    private Set<String> ownBefore = Set.of();
    private long sentAt;
    private long drainedAt;
    private String fallbackId;
    private long fallbackAt;
    private boolean cancelled;

    private int total;
    private int succeeded;
    private int failed;
    private long batchStart;
    private long lastFinishedAt;
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private String status = "";
    private String currentName = "";
    private boolean toastShown;

    private UploadQueue() {
    }

    public boolean isAwaitingResponse() {
        return awaiting;
    }

    public boolean isRunning() {
        return current != null || preparing != null || !pending.isEmpty();
    }

    public int total() {
        return total;
    }

    public int finished() {
        return succeeded + failed;
    }

    public int succeeded() {
        return succeeded;
    }

    public int failed() {
        return failed;
    }

    public String status() {
        return status;
    }

    public String currentName() {
        return currentName;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public List<String> errors() {
        synchronized (errors) {
            return List.copyOf(errors);
        }
    }

    /** 0..1 progress of the whole batch (the image currently uploading counts by IP's queue progress). */
    public float progress() {
        if (total == 0) return 0;
        float part = 0;
        if (current != null && awaiting) {
            float remaining = IpBridge.remainingUploadSeconds();
            part = remaining > 0 ? 0.3f : 0.9f;
        }
        return Math.min(1, (finished() + part) / total);
    }

    /** Estimated remaining milliseconds, or -1 when unknown. */
    public long etaMs() {
        int done = finished();
        if (done == 0) {
            float r = IpBridge.remainingUploadSeconds();
            return r > 0 ? (long) (r * 1000 * (total - done)) : -1;
        }
        long perItem = (lastFinishedAt - batchStart) / done;
        return perItem * (total - done);
    }

    public void enqueue(List<Request> requests) {
        if (requests.isEmpty()) return;
        if (!isRunning()) {
            total = 0;
            succeeded = 0;
            failed = 0;
            errors.clear();
            cancelled = false;
            batchStart = System.currentTimeMillis();
            lastFinishedAt = batchStart;
            toastShown = false;
        }
        for (Request r : requests) {
            GalleryEntry e = r.entry();
            pending.add(new Job(e, r.settings(), e.source().paintingName(e.name(), r.settings().sourcePrefix())));
        }
        total += requests.size();
        status = "Preparing...";
        if (!toastShown) {
            Minecraft.getInstance().gui.toastManager().addToast(new UploadToast(this));
            toastShown = true;
        }
    }

    /** Stops after the image that is currently uploading (IP's packet queue can't be recalled). */
    public void cancel() {
        if (!isRunning()) return;
        cancelled = true;
        int dropped = pending.size();
        pending.clear();
        total -= dropped;
        if (preparing != null) {
            preparing.cancel(true);
            preparing = null;
            total--;
        }
        status = current != null ? "Cancelling after current image..." : "Cancelled";
    }

    // ------------------------------------------------------------------ client tick

    public void tick() {
        if (!isRunning()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            abort("Disconnected - import stopped.");
            return;
        }

        if (preparing == null && !pending.isEmpty() && !cancelled) {
            Job next = pending.poll();
            preparing = Workers.IMPORT.submit(() -> prepare(next));
        }

        if (current == null) {
            if (preparing == null || !preparing.isDone()) {
                if (preparing != null) status = "Preparing " + (finished() + 1) + "/" + total + "...";
                return;
            }
            Prepared p;
            try {
                p = preparing.get();
            } catch (Exception e) {
                p = null;
            }
            preparing = null;
            if (p == null) return;
            currentName = p.job().paintingName();
            if (p.error() != null) {
                fail(p.job().paintingName(), p.error());
                return;
            }
            if (!IpCompat.isAvailable()) {
                abort(IpCompat.problem());
                return;
            }
            send(p, mc.player.getUUID());
            // start preparing the next image while this one uploads
            if (!pending.isEmpty() && !cancelled) {
                Job next = pending.poll();
                preparing = Workers.IMPORT.submit(() -> prepare(next));
            }
            return;
        }

        waitForResult(mc.player.getUUID());
    }

    private void send(Prepared p, UUID me) {
        UploadResponses.expectOne();
        fallbackId = null;
        ownBefore = new HashSet<>();
        for (IpBridge.PaintingRef ref : IpBridge.paintingsBy(me)) ownBefore.add(ref.id().toString());
        PaintingPipeline.Plan plan = p.plan();
        boolean nsfw = p.job().entry().nsfw();
        current = p;
        awaiting = true;
        sentAt = System.currentTimeMillis();
        drainedAt = 0;
        if (!IpBridge.sendUpload(p.png(), plan.blocks().width(), plan.blocks().height(), plan.resolution(), p.job().paintingName(), nsfw)) {
            current = null;
            awaiting = false;
            abort(IpCompat.problem());
            return;
        }
        status = "Uploading " + (finished() + 1) + "/" + total;
    }

    private void waitForResult(UUID me) {
        String name = current.job().paintingName();
        float remaining = IpBridge.remainingUploadSeconds();
        status = "Uploading " + (finished() + 1) + "/" + total + (remaining > 0 ? " (" + (int) Math.ceil(remaining) + "s of data left)" : " (server processing...)");

        // 1) authoritative answer from the server (via mixin)
        UploadResponses.Response response;
        while ((response = UploadResponses.poll()) != null) {
            if (response.success()) {
                succeed(response.paintingId());
            } else {
                handleServerError(name, response.error());
            }
            return;
        }

        // 2) fallback (hook unavailable): a new painting of ours with this name showed up in IP's list. The server
        //    sends the response right after the sync, so give it a moment to arrive before relying on this.
        long now = System.currentTimeMillis();
        if (fallbackId == null && now - sentAt > 500) {
            for (IpBridge.PaintingRef ref : IpBridge.paintingsBy(me)) {
                if (!ownBefore.contains(ref.id().toString()) && name.equals(ref.name())) {
                    fallbackId = ref.id().toString();
                    fallbackAt = now;
                    break;
                }
            }
        }
        if (fallbackId != null && now - fallbackAt > FALLBACK_GRACE_MS) {
            succeed(fallbackId);
            return;
        }

        // 3) timeout once all our packets have left
        if (remaining <= 0) {
            if (drainedAt == 0) drainedAt = now;
            if (now - drainedAt > RESPONSE_TIMEOUT_MS) {
                // identical re-upload keeps the same id, so accept an existing painting with our name
                for (IpBridge.PaintingRef ref : IpBridge.paintingsBy(me)) {
                    if (name.equals(ref.name())) {
                        succeed(ref.id().toString());
                        return;
                    }
                }
                awaiting = false;
                current = null;
                fail(name, "No answer from the server (upload rejected? check permissions / Immersive Paintings limits)");
            }
        }
    }

    private void handleServerError(String name, String key) {
        awaiting = false;
        current = null;
        String ipText = Component.translatable("immersive_paintings.error." + key).getString();
        switch (key) {
            case "limit_reached" -> {
                int limit = IpBridge.commonConfigInt("maxUserImages", 1000);
                fail(name, ipText);
                abort("Immersive Paintings' per-player limit reached (" + limit + " paintings: '" + ipText
                        + "'). Delete some in IP's 'Yours' tab, or raise 'maxUserImages' in Immersive Paintings' "
                        + "common config (server side).");
            }
            case "no_permission" -> {
                fail(name, ipText);
                abort("You are not allowed to upload paintings on this server ('" + ipText + "').");
            }
            case "too_large" -> fail(name, ipText + " (server image size limit; lower it via IP's maxUserImageWidth/Height)");
            default -> fail(name, ipText.equals("immersive_paintings.error." + key) ? key : ipText);
        }
    }

    private void succeed(String paintingId) {
        Prepared p = current;
        awaiting = false;
        current = null;
        succeeded++;
        lastFinishedAt = System.currentTimeMillis();
        if (p != null && p.gif() != null && paintingId != null) {
            try {
                AnimationManager.get().index().put(paintingId, p.gif());
            } catch (IOException e) {
                PocketGallery.LOG.warn("Could not save the GIF index", e);
            }
        }
        if (p != null && paintingId != null) {
            try {
                PocketGallery.sources().put(paintingId, p.job().entry().sourceRecord());
            } catch (IOException e) {
                PocketGallery.LOG.warn("Could not save the source index", e);
            }
        }
        maybeFinish();
    }

    private void fail(String name, String message) {
        failed++;
        lastFinishedAt = System.currentTimeMillis();
        errors.add(name + ": " + message);
        PocketGallery.LOG.warn("Import of '{}' failed: {}", name, message);
        maybeFinish();
    }

    private void abort(String message) {
        int dropped = pending.size() + (preparing != null ? 1 : 0);
        pending.clear();
        if (preparing != null) preparing.cancel(true);
        preparing = null;
        if (current != null) {
            awaiting = false;
            current = null;
            dropped++;
        }
        failed += dropped;
        errors.add(message);
        status = message;
        chat(Component.literal("[Pocket Gallery] " + message));
        maybeFinish();
    }

    private void maybeFinish() {
        if (isRunning()) return;
        UploadResponses.reset();
        status = cancelled ? "Cancelled: " + succeeded + " imported" : "Done: " + succeeded + " imported" + (failed > 0 ? ", " + failed + " failed" : "");
        if (succeeded > 0 || failed > 0) {
            chat(Component.literal("[Pocket Gallery] " + status
                    + (succeeded > 0 ? ". Place them with an Immersive Paintings painting: right click > 'Yours'." : "")));
        }
    }

    private static void chat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    // ------------------------------------------------------------------ worker thread

    private static Prepared prepare(Job job) {
        GalleryEntry entry = job.entry();
        try {
            Path file = entry.fetchFile();
            byte[] data = Files.readAllBytes(file);
            PaintingPipeline.Settings s = job.settings().pipeline();

            ImageLoader.Size real;
            boolean gif = ImageLoader.isGif(data);
            if (gif) {
                GifDecoder.Info info = GifDecoder.readInfo(new ByteArrayInputStream(data));
                real = new ImageLoader.Size(info.width(), info.height());
            } else {
                real = ImageLoader.readSize(data);
            }
            BufferedImage decoded = ImageLoader.decode(data, s.maxPixelSide());
            PaintingPipeline.Result result = PaintingPipeline.process(decoded, real.width(), real.height(), s);
            decoded = null; // allow GC of the big source before encoding

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(result.image(), "png", out)) throw new IOException("PNG encoder missing");

            AnimatedIndex.Entry gifEntry = null;
            if (gif && GifDecoder.countFrames(new ByteArrayInputStream(data)) > 1) {
                var crop = result.plan().crop();
                gifEntry = new AnimatedIndex.Entry(file.toAbsolutePath().toString(), crop.x(), crop.y(),
                        crop.width(), crop.height(), real.width(), real.height(), result.plan().colors());
            }
            return new Prepared(job, out.toByteArray(), result.plan(), gifEntry, null);
        } catch (OutOfMemoryError e) {
            return new Prepared(job, null, null, null, "Out of memory while decoding (image too large)");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return new Prepared(job, null, null, null, msg);
        }
    }
}
