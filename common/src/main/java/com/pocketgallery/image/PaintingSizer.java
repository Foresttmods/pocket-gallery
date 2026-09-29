package com.pocketgallery.image;

/**
 * Pure math for turning an image into an Immersive Paintings size: block dimensions by aspect ratio,
 * the centered crop rectangle, the output pixel size and the "resolution" (pixels per block).
 */
public final class PaintingSizer {
    /** Immersive Paintings' hard limits on painting dimensions. */
    public static final int MIN_BLOCKS = 1;
    public static final int MAX_BLOCKS = 16;

    private PaintingSizer() {
    }

    public record BlockSize(int width, int height) {
        public int longSide() {
            return Math.max(width, height);
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    public record Rect(int x, int y, int width, int height) {
    }

    /**
     * Picks integer block dimensions whose long side equals {@code longSide} and whose aspect ratio best matches
     * the image (minimal |log(candidate aspect / image aspect)|). The short side is clamped to [1, longSide], so
     * extreme panoramas become {@code longSide x 1}.
     */
    public static BlockSize fit(int imageWidth, int imageHeight, int longSide) {
        int l = Math.max(MIN_BLOCKS, Math.min(MAX_BLOCKS, longSide));
        if (imageWidth <= 0 || imageHeight <= 0) return new BlockSize(l, l);

        boolean landscape = imageWidth >= imageHeight;
        double aspect = (double) imageWidth / imageHeight; // width / height
        // ideal short side for a fixed long side
        double idealShort = landscape ? l / aspect : l * aspect;

        int bestShort = 1;
        double bestError = Double.MAX_VALUE;
        int lo = Math.max(1, (int) Math.floor(idealShort));
        int hi = Math.min(l, Math.max(1, (int) Math.ceil(idealShort)));
        for (int s = Math.min(lo, hi); s <= Math.max(lo, hi); s++) {
            int w = landscape ? l : s;
            int h = landscape ? s : l;
            double err = Math.abs(Math.log(((double) w / h) / aspect));
            // strictly-less keeps the smaller short side on exact ties (deterministic)
            if (err < bestError - 1e-12) {
                bestError = err;
                bestShort = s;
            }
        }
        return landscape ? new BlockSize(l, bestShort) : new BlockSize(bestShort, l);
    }

    /**
     * Largest centered rectangle of the image with exactly the aspect ratio {@code blocksW:blocksH}.
     */
    public static Rect centerCrop(int imageWidth, int imageHeight, int blocksW, int blocksH) {
        if (imageWidth <= 0 || imageHeight <= 0 || blocksW <= 0 || blocksH <= 0) {
            return new Rect(0, 0, Math.max(1, imageWidth), Math.max(1, imageHeight));
        }
        double target = (double) blocksW / blocksH;
        double aspect = (double) imageWidth / imageHeight;
        int w = imageWidth;
        int h = imageHeight;
        if (aspect > target) {
            // too wide: trim left/right
            w = (int) Math.max(1, Math.min(imageWidth, Math.round(imageHeight * target)));
        } else if (aspect < target) {
            // too tall: trim top/bottom
            h = (int) Math.max(1, Math.min(imageHeight, Math.round(imageWidth / target)));
        }
        return new Rect((imageWidth - w) / 2, (imageHeight - h) / 2, w, h);
    }

    /**
     * Output pixel size after limiting the long side to {@code maxSide} (keeping the aspect ratio). Images that
     * already fit are returned unchanged - we never upscale.
     */
    public static int[] limitSize(int width, int height, int maxSide) {
        int longest = Math.max(width, height);
        if (longest <= maxSide || maxSide <= 0) return new int[]{width, height};
        double scale = (double) maxSide / longest;
        return new int[]{
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))
        };
    }

    /** Pixels per block: round(longSidePx / longSideBlocks) clamped to Immersive Paintings' allowed range. */
    public static int resolution(int pixelWidth, int pixelHeight, BlockSize size, int minResolution, int maxResolution) {
        int longPx = Math.max(pixelWidth, pixelHeight);
        int res = (int) Math.round((double) longPx / size.longSide());
        return Math.max(minResolution, Math.min(maxResolution, res));
    }
}
