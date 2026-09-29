package com.pocketgallery.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Pixel helpers that don't depend on Minecraft: cropping, high quality resampling, alpha flattening.
 */
public final class ImageOps {
    private ImageOps() {
    }

    /** Copies (not views) a sub-rectangle into a new ARGB image. */
    public static BufferedImage crop(BufferedImage src, PaintingSizer.Rect r) {
        if (r.x() == 0 && r.y() == 0 && r.width() == src.getWidth() && r.height() == src.getHeight()) {
            return toArgb(src);
        }
        BufferedImage out = new BufferedImage(r.width(), r.height(), BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[r.width()];
        for (int y = 0; y < r.height(); y++) {
            src.getRGB(r.x(), r.y() + y, r.width(), 1, row, 0, r.width());
            out.setRGB(0, y, r.width(), 1, row, 0, r.width());
        }
        return out;
    }

    /** Returns the image as TYPE_INT_ARGB (a copy unless it already is one). */
    public static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int w = src.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < src.getHeight(); y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /**
     * Resizes to exactly {@code dstW x dstH}. Downscaling uses an exact area-average (box) filter with
     * premultiplied alpha, streaming one source row at a time so huge images don't need extra full-size buffers.
     * Any upscaling axis falls back to bicubic interpolation.
     */
    public static BufferedImage resize(BufferedImage src, int dstW, int dstH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (dstW == srcW && dstH == srcH) return toArgb(src);
        if (dstW > srcW || dstH > srcH) return resizeBicubic(src, dstW, dstH);

        double sx = (double) srcW / dstW;
        double sy = (double) srcH / dstH;

        // Horizontal contributions: for each destination column, the first source column and per-column weights.
        int[] hStart = new int[dstW];
        float[][] hWeight = new float[dstW][];
        for (int x = 0; x < dstW; x++) {
            double start = x * sx;
            double end = Math.min(srcW, (x + 1) * sx);
            int first = (int) Math.floor(start);
            int last = Math.min(srcW - 1, (int) Math.ceil(end) - 1);
            hStart[x] = first;
            float[] weights = new float[last - first + 1];
            for (int i = first; i <= last; i++) {
                weights[i - first] = (float) (Math.min(end, i + 1) - Math.max(start, i));
            }
            hWeight[x] = weights;
        }

        BufferedImage out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
        int[] srcRow = new int[srcW];
        float[] horiz = new float[dstW * 4];
        float[] acc = new float[dstW * 4];
        float[] accNext = new float[dstW * 4];
        int[] outRow = new int[dstW];
        double area = sx * sy;
        int curY = 0;

        for (int j = 0; j < srcH && curY < dstH; j++) {
            src.getRGB(0, j, srcW, 1, srcRow, 0, srcW);

            // horizontal pass for this source row (premultiplied)
            for (int x = 0; x < dstW; x++) {
                float a = 0, r = 0, g = 0, b = 0;
                float[] weights = hWeight[x];
                int base = hStart[x];
                for (int i = 0; i < weights.length; i++) {
                    int p = srcRow[base + i];
                    float w = weights[i];
                    float pa = ((p >>> 24) & 0xFF) * w;
                    a += pa;
                    r += ((p >> 16) & 0xFF) * pa;
                    g += ((p >> 8) & 0xFF) * pa;
                    b += (p & 0xFF) * pa;
                }
                int o = x * 4;
                horiz[o] = a;
                horiz[o + 1] = r;
                horiz[o + 2] = g;
                horiz[o + 3] = b;
            }

            // distribute into the (at most two) destination rows this source row overlaps
            for (int y = curY; y <= curY + 1 && y < dstH; y++) {
                double overlap = Math.min(j + 1, (y + 1) * sy) - Math.max(j, y * sy);
                if (overlap <= 0) continue;
                float[] target = y == curY ? acc : accNext;
                float w = (float) overlap;
                for (int k = 0; k < target.length; k++) target[k] += horiz[k] * w;
            }

            // emit finished rows
            while (curY < dstH && (curY + 1) * sy <= j + 1 + 1e-9) {
                for (int x = 0; x < dstW; x++) {
                    int o = x * 4;
                    float a = acc[o];
                    int alpha = clamp8(a / area);
                    int r = 0, g = 0, b = 0;
                    if (a > 0) {
                        r = clamp8(acc[o + 1] / a);
                        g = clamp8(acc[o + 2] / a);
                        b = clamp8(acc[o + 3] / a);
                    }
                    outRow[x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                }
                out.setRGB(0, curY, dstW, 1, outRow, 0, dstW);
                float[] tmp = acc;
                acc = accNext;
                accNext = tmp;
                java.util.Arrays.fill(accNext, 0);
                curY++;
            }
        }
        return out;
    }

    private static BufferedImage resizeBicubic(BufferedImage src, int dstW, int dstH) {
        BufferedImage out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, dstW, dstH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Scales to fit inside {@code maxW x maxH} keeping aspect; returns the source when it already fits. */
    public static BufferedImage fitWithin(BufferedImage src, int maxW, int maxH) {
        double scale = Math.min(1.0, Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight()));
        if (scale >= 1.0) return src;
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        return resize(src, w, h);
    }

    /**
     * Composites the image over an opaque background colour. Immersive Paintings treats non-graffiti paintings as
     * opaque, so transparent pixels would otherwise show whatever RGB garbage they carry.
     */
    public static BufferedImage flatten(BufferedImage src, int backgroundRgb) {
        BufferedImage img = toArgb(src);
        int w = img.getWidth();
        int[] row = new int[w];
        int br = (backgroundRgb >> 16) & 0xFF, bg = (backgroundRgb >> 8) & 0xFF, bb = backgroundRgb & 0xFF;
        BufferedImage out = img == src ? new BufferedImage(w, img.getHeight(), BufferedImage.TYPE_INT_ARGB) : img;
        for (int y = 0; y < img.getHeight(); y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                row[x] = blendOpaque(row[x], br, bg, bb);
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    static int blendOpaque(int p, int br, int bg, int bb) {
        int a = (p >>> 24) & 0xFF;
        if (a == 255) return p;
        int r = ((p >> 16) & 0xFF) * a / 255 + br * (255 - a) / 255;
        int g = ((p >> 8) & 0xFF) * a / 255 + bg * (255 - a) / 255;
        int b = (p & 0xFF) * a / 255 + bb * (255 - a) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** ARGB image to the ABGR int layout used by Minecraft's NativeImage (RGBA bytes, little endian). */
    public static int[] toAbgr(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF);
        }
        return px;
    }

    private static int clamp8(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : Math.min(255, i);
    }
}
