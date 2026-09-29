package com.pocketgallery.image;

import java.awt.image.BufferedImage;

/**
 * Turns a decoded image into exactly what gets uploaded: optional center crop to the block aspect ratio,
 * downscale only when above the server's size limit (or to the fixed density of the pixel look), alpha flattened to
 * opaque, and an optional reduced palette.
 */
public final class PaintingPipeline {
    /** Background for transparent pixels (paintings are opaque in Immersive Paintings). */
    public static final int BACKGROUND_RGB = 0xFFFFFF;

    private PaintingPipeline() {
    }

    public record Settings(int longSideBlocks, boolean cropToFit, int maxPixelSide, int minResolution, int maxResolution,
                           PixelLook look) {
        public Settings {
            if (look == null) look = PixelLook.OFF;
        }

        public Settings(int longSideBlocks, boolean cropToFit, int maxPixelSide, int minResolution, int maxResolution) {
            this(longSideBlocks, cropToFit, maxPixelSide, minResolution, maxResolution, PixelLook.OFF);
        }

        public Settings withLook(PixelLook newLook) {
            return new Settings(longSideBlocks, cropToFit, maxPixelSide, minResolution, maxResolution, newLook);
        }
    }

    /**
     * @param crop crop rectangle in source pixel coordinates (the whole image when not cropping)
     */
    public record Plan(PaintingSizer.BlockSize blocks, PaintingSizer.Rect crop, int outWidth, int outHeight, int resolution,
                       int colors) {
    }

    public record Result(BufferedImage image, Plan plan) {
    }

    /** Computes sizes without touching pixels (used for the preview panel and the animation index). */
    public static Plan plan(int srcWidth, int srcHeight, Settings s) {
        PaintingSizer.BlockSize blocks = PaintingSizer.fit(srcWidth, srcHeight, s.longSideBlocks());
        PaintingSizer.Rect crop = s.cropToFit()
                ? PaintingSizer.centerCrop(srcWidth, srcHeight, blocks.width(), blocks.height())
                : new PaintingSizer.Rect(0, 0, srcWidth, srcHeight);
        PixelLook look = s.look();
        if (look.isPixelated()) {
            int ppb = pixelDensity(look.pixelsPerBlock(), blocks, s);
            return new Plan(blocks, crop, blocks.width() * ppb, blocks.height() * ppb, ppb, look.colors());
        }
        int[] out = PaintingSizer.limitSize(crop.width(), crop.height(), s.maxPixelSide());
        int res = PaintingSizer.resolution(out[0], out[1], blocks, s.minResolution(), s.maxResolution());
        return new Plan(blocks, crop, out[0], out[1], res, look.colors());
    }

    /** The requested pixels per block, limited to Immersive Paintings' allowed range and the server's size limit. */
    static int pixelDensity(int requested, PaintingSizer.BlockSize blocks, Settings s) {
        int ppb = Math.max(s.minResolution(), Math.min(s.maxResolution(), requested));
        if (s.maxPixelSide() > 0) ppb = Math.min(ppb, Math.max(1, s.maxPixelSide() / blocks.longSide()));
        return Math.max(1, ppb);
    }

    /**
     * Runs the plan against a source whose size may differ from the planned source size (e.g. the decoder
     * subsampled a huge image). The crop rectangle is scaled proportionally.
     */
    public static BufferedImage apply(BufferedImage src, Plan plan, int plannedSrcWidth, int plannedSrcHeight) {
        PaintingSizer.Rect crop = plan.crop();
        if (src.getWidth() != plannedSrcWidth || src.getHeight() != plannedSrcHeight) {
            double fx = (double) src.getWidth() / plannedSrcWidth;
            double fy = (double) src.getHeight() / plannedSrcHeight;
            int x = (int) Math.round(crop.x() * fx);
            int y = (int) Math.round(crop.y() * fy);
            int w = Math.max(1, Math.min(src.getWidth() - x, (int) Math.round(crop.width() * fx)));
            int h = Math.max(1, Math.min(src.getHeight() - y, (int) Math.round(crop.height() * fy)));
            crop = new PaintingSizer.Rect(x, y, w, h);
        }
        BufferedImage cropped = ImageOps.crop(src, crop);
        BufferedImage sized = ImageOps.resize(cropped, plan.outWidth(), plan.outHeight());
        BufferedImage flat = ImageOps.flatten(sized, BACKGROUND_RGB);
        return plan.colors() > 0 ? Palette.reduce(flat, plan.colors()) : flat;
    }

    public static Result process(BufferedImage src, int realSrcWidth, int realSrcHeight, Settings s) {
        Plan plan = plan(realSrcWidth, realSrcHeight, s);
        return new Result(apply(src, plan, realSrcWidth, realSrcHeight), plan);
    }
}
