package com.pocketgallery.anim;

import com.pocketgallery.image.GifDecoder;
import com.pocketgallery.image.ImageOps;
import com.pocketgallery.image.PaintingPipeline;
import com.pocketgallery.image.Palette;
import com.pocketgallery.image.PaintingSizer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Builds texture-ready animation frames (ABGR int arrays) for one GIF painting, for every texture size IP uses.
 * Frames are cropped exactly like on upload, flattened and resized to each target texture size. Long GIFs are
 * decimated (every k-th frame, delays summed) to respect the frame cap and memory budget.
 */
public final class FrameBuilder {
    private FrameBuilder() {
    }

    public record Target(int width, int height) {
        long bytesPerFrame() {
            return (long) width * height * 4L;
        }
    }

    /** frames.get(t).get(f) = pixels of frame f for target t; delays[f] in ms. */
    public record Frames(List<Target> targets, List<List<int[]>> frames, int[] delaysMs, long bytes) {
        public int count() {
            return delaysMs.length;
        }

        public int totalDurationMs() {
            int sum = 0;
            for (int d : delaysMs) sum += d;
            return Math.max(1, sum);
        }
    }

    /**
     * @return null when not even two frames fit into the budget (animation pointless)
     */
    public static Frames build(byte[] gif, AnimatedIndex.Entry entry, List<Target> targets, int maxFrames,
                               long budgetBytes, BooleanSupplier cancelled) throws IOException {
        long perFrame = 0;
        for (Target t : targets) perFrame += t.bytesPerFrame();
        if (perFrame <= 0) return null;
        int allowed = (int) Math.min(maxFrames, budgetBytes / perFrame);
        if (allowed < 2) return null;

        int total = GifDecoder.countFrames(new ByteArrayInputStream(gif));
        if (total < 2) return null;
        int stride = (int) Math.ceil((double) total / allowed);

        List<List<int[]>> frames = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) frames.add(new ArrayList<>());
        List<Integer> delays = new ArrayList<>();
        int[][] palettes = new int[targets.size()][];

        GifDecoder.decode(new ByteArrayInputStream(gif), total, (index, canvas, delayMs) -> {
            if (cancelled.getAsBoolean()) return false;
            if (index % stride != 0) {
                // decimated frame: extend the previous kept frame's display time
                if (!delays.isEmpty()) delays.set(delays.size() - 1, delays.getLast() + delayMs);
                return true;
            }
            PaintingSizer.Rect crop = cropFor(entry, canvas.getWidth(), canvas.getHeight());
            BufferedImage cropped = ImageOps.crop(canvas, crop);
            for (int t = 0; t < targets.size(); t++) {
                Target target = targets.get(t);
                BufferedImage sized = ImageOps.resize(cropped, target.width(), target.height());
                BufferedImage flat = ImageOps.flatten(sized, PaintingPipeline.BACKGROUND_RGB);
                if (entry.colors() > 0) {
                    // one palette per target (from the first frame) so colours don't flicker between frames
                    if (palettes[t] == null) palettes[t] = Palette.build(flat, entry.colors());
                    flat = Palette.map(flat, palettes[t]);
                }
                frames.get(t).add(ImageOps.toAbgr(flat));
            }
            delays.add(delayMs);
            return true;
        });

        if (cancelled.getAsBoolean() || delays.size() < 2) return null;
        int[] d = delays.stream().mapToInt(Integer::intValue).toArray();
        return new Frames(targets, frames, d, perFrame * d.length);
    }

    /** The upload-time crop, rescaled if the GIF's canvas size differs from what was recorded. */
    static PaintingSizer.Rect cropFor(AnimatedIndex.Entry e, int canvasW, int canvasH) {
        if (e.srcWidth() == canvasW && e.srcHeight() == canvasH) {
            return clamp(new PaintingSizer.Rect(e.cropX(), e.cropY(), e.cropWidth(), e.cropHeight()), canvasW, canvasH);
        }
        double fx = (double) canvasW / Math.max(1, e.srcWidth());
        double fy = (double) canvasH / Math.max(1, e.srcHeight());
        return clamp(new PaintingSizer.Rect((int) Math.round(e.cropX() * fx), (int) Math.round(e.cropY() * fy),
                (int) Math.round(e.cropWidth() * fx), (int) Math.round(e.cropHeight() * fy)), canvasW, canvasH);
    }

    private static PaintingSizer.Rect clamp(PaintingSizer.Rect r, int w, int h) {
        int x = Math.max(0, Math.min(w - 1, r.x()));
        int y = Math.max(0, Math.min(h - 1, r.y()));
        int cw = Math.max(1, Math.min(w - x, r.width()));
        int ch = Math.max(1, Math.min(h - y, r.height()));
        return new PaintingSizer.Rect(x, y, cw, ch);
    }
}
