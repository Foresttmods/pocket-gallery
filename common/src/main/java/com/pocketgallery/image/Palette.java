package com.pocketgallery.image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Colour reduction for the pixel look: median cut over a 15-bit (5 bits per channel) histogram, then every pixel is
 * mapped to its nearest palette colour without dithering, which gives the flat colour areas of hand-made pixel art.
 * Works row by row, so large images need no extra full-size buffers. Alpha is ignored (paintings are opaque).
 */
public final class Palette {
    private static final int BINS = 1 << 15;

    private Palette() {
    }

    /** Returns a copy of {@code src} using at most {@code colors} colours; the source itself when colors <= 0. */
    public static BufferedImage reduce(BufferedImage src, int colors) {
        if (colors <= 0) return src;
        int[] palette = build(src, colors);
        return map(src, palette);
    }

    /** The palette (opaque ARGB colours) median cut picks for the image. */
    public static int[] build(BufferedImage src, int colors) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] hist = new int[BINS];
        long[] sumR = new long[BINS];
        long[] sumG = new long[BINS];
        long[] sumB = new long[BINS];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int i = index(r >> 3, g >> 3, b >> 3);
                hist[i]++;
                sumR[i] += r;
                sumG[i] += g;
                sumB[i] += b;
            }
        }

        List<Box> boxes = new ArrayList<>();
        Box first = Box.shrink(0, 31, 0, 31, 0, 31, hist);
        if (first == null) return new int[]{0xFF000000};
        boxes.add(first);
        while (boxes.size() < colors) {
            Box best = null;
            long bestScore = -1;
            for (Box b : boxes) {
                if (!b.splittable()) continue;
                long score = b.count * (long) b.longestSide();
                if (score > bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
            if (best == null) break; // every box is a single histogram bin
            Box[] halves = best.split(hist);
            boxes.remove(best);
            boxes.add(halves[0]);
            boxes.add(halves[1]);
        }

        int[] palette = new int[boxes.size()];
        for (int k = 0; k < boxes.size(); k++) {
            Box b = boxes.get(k);
            long n = 0, r = 0, g = 0, bl = 0;
            for (int ri = b.r0; ri <= b.r1; ri++) {
                for (int gi = b.g0; gi <= b.g1; gi++) {
                    for (int bi = b.b0; bi <= b.b1; bi++) {
                        int i = index(ri, gi, bi);
                        n += hist[i];
                        r += sumR[i];
                        g += sumG[i];
                        bl += sumB[i];
                    }
                }
            }
            n = Math.max(1, n);
            palette[k] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
        }
        return palette;
    }

    /** Maps each pixel to its nearest palette colour (cached per 15-bit colour). */
    public static BufferedImage map(BufferedImage src, int[] palette) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] cache = new int[BINS];
        java.util.Arrays.fill(cache, -1);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int key = index(r >> 3, g >> 3, b >> 3);
                int c = cache[key];
                if (c < 0) {
                    c = nearest(palette, r, g, b);
                    cache[key] = c;
                }
                row[x] = palette[c];
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /** Number of distinct RGB colours (used by tests and the preview). */
    public static int countColors(BufferedImage img) {
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        int w = img.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < img.getHeight(); y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int p : row) set.add(p & 0xFFFFFF);
        }
        return set.size();
    }

    private static int nearest(int[] palette, int r, int g, int b) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int p = palette[i];
            int dr = ((p >> 16) & 0xFF) - r, dg = ((p >> 8) & 0xFF) - g, db = (p & 0xFF) - b;
            // perceptual-ish weights (green matters most, blue least)
            int d = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static int index(int r5, int g5, int b5) {
        return (r5 << 10) | (g5 << 5) | b5;
    }

    /** Axis-aligned box in 5-bit colour space, always shrunk to the bins it actually contains. */
    private static final class Box {
        final int r0, r1, g0, g1, b0, b1;
        final long count;

        private Box(int r0, int r1, int g0, int g1, int b0, int b1, long count) {
            this.r0 = r0;
            this.r1 = r1;
            this.g0 = g0;
            this.g1 = g1;
            this.b0 = b0;
            this.b1 = b1;
            this.count = count;
        }

        boolean splittable() {
            return r1 > r0 || g1 > g0 || b1 > b0;
        }

        int longestSide() {
            return Math.max(r1 - r0, Math.max(g1 - g0, b1 - b0)) + 1;
        }

        /** Tight box around the non-empty bins inside the bounds, or null when there are none. */
        static Box shrink(int r0, int r1, int g0, int g1, int b0, int b1, int[] hist) {
            int nr0 = 32, nr1 = -1, ng0 = 32, ng1 = -1, nb0 = 32, nb1 = -1;
            long count = 0;
            for (int r = r0; r <= r1; r++) {
                for (int g = g0; g <= g1; g++) {
                    for (int b = b0; b <= b1; b++) {
                        int n = hist[index(r, g, b)];
                        if (n == 0) continue;
                        count += n;
                        nr0 = Math.min(nr0, r);
                        nr1 = Math.max(nr1, r);
                        ng0 = Math.min(ng0, g);
                        ng1 = Math.max(ng1, g);
                        nb0 = Math.min(nb0, b);
                        nb1 = Math.max(nb1, b);
                    }
                }
            }
            return count == 0 ? null : new Box(nr0, nr1, ng0, ng1, nb0, nb1, count);
        }

        /**
         * Splits at the weighted median of the longest axis. Because the box is tight, both its first and last
         * slice hold pixels, so a cut anywhere in [lo, hi-1] leaves two non-empty halves.
         */
        Box[] split(int[] hist) {
            int rs = r1 - r0, gs = g1 - g0, bs = b1 - b0;
            int axis = rs >= gs && rs >= bs ? 0 : gs >= bs ? 1 : 2;
            int lo = axis == 0 ? r0 : axis == 1 ? g0 : b0;
            int hi = axis == 0 ? r1 : axis == 1 ? g1 : b1;
            long[] slice = new long[32];
            for (int r = r0; r <= r1; r++) {
                for (int g = g0; g <= g1; g++) {
                    for (int b = b0; b <= b1; b++) {
                        int v = axis == 0 ? r : axis == 1 ? g : b;
                        slice[v] += hist[index(r, g, b)];
                    }
                }
            }
            long half = count / 2;
            long cum = 0;
            int cut = hi - 1;
            for (int v = lo; v < hi; v++) {
                cum += slice[v];
                if (cum >= half) {
                    cut = v;
                    break;
                }
            }
            Box a, b;
            if (axis == 0) {
                a = shrink(r0, cut, g0, g1, b0, b1, hist);
                b = shrink(cut + 1, r1, g0, g1, b0, b1, hist);
            } else if (axis == 1) {
                a = shrink(r0, r1, g0, cut, b0, b1, hist);
                b = shrink(r0, r1, cut + 1, g1, b0, b1, hist);
            } else {
                a = shrink(r0, r1, g0, g1, b0, cut, hist);
                b = shrink(r0, r1, g0, g1, cut + 1, b1, hist);
            }
            return new Box[]{a, b};
        }
    }
}
