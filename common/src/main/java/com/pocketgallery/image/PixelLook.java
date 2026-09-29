package com.pocketgallery.image;

/**
 * Optional "Minecraft look" for one painting: a fixed pixel density (pixels per block, like vanilla's 16x paintings)
 * and an optional reduced colour palette. {@code pixelsPerBlock == 0} keeps the image's own resolution;
 * {@code colors == 0} keeps all colours.
 */
public record PixelLook(int pixelsPerBlock, int colors) {
    // the arrays must be initialised before OFF: the constructor snaps values to them
    /** Pixel densities offered in the UI (0 = original resolution). */
    public static final int[] DENSITIES = {0, 16, 32, 64, 128, 256};
    /** Palette sizes offered in the UI (0 = all colours). */
    public static final int[] PALETTES = {0, 64, 32, 16, 8};
    public static final PixelLook OFF = new PixelLook(0, 0);

    public PixelLook {
        pixelsPerBlock = nearest(DENSITIES, pixelsPerBlock);
        colors = nearest(PALETTES, colors);
    }

    public boolean isPixelated() {
        return pixelsPerBlock > 0;
    }

    public boolean isReduced() {
        return colors > 0;
    }

    public boolean isOff() {
        return !isPixelated() && !isReduced();
    }

    public PixelLook nextDensity() {
        return new PixelLook(next(DENSITIES, pixelsPerBlock), colors);
    }

    public PixelLook previousDensity() {
        return new PixelLook(previous(DENSITIES, pixelsPerBlock), colors);
    }

    public PixelLook nextColors() {
        return new PixelLook(pixelsPerBlock, next(PALETTES, colors));
    }

    public String densityLabel() {
        return isPixelated() ? pixelsPerBlock + "x" : "Off";
    }

    public String colorsLabel() {
        return isReduced() ? String.valueOf(colors) : "All";
    }

    /** Short tag for the grid badge, e.g. "32x" or "16c"; empty when off. */
    public String badge() {
        if (isPixelated()) return pixelsPerBlock + "x";
        return isReduced() ? colors + "c" : "";
    }

    private static int nearest(int[] allowed, int value) {
        int best = allowed[0];
        for (int a : allowed) {
            if (Math.abs(a - value) < Math.abs(best - value)) best = a;
        }
        return best;
    }

    private static int next(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    private static int previous(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i - 1 + values.length) % values.length];
        }
        return values[0];
    }
}
