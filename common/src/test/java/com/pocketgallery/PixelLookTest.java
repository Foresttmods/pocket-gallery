package com.pocketgallery;

import com.pocketgallery.image.PaintingPipeline;
import com.pocketgallery.image.Palette;
import com.pocketgallery.image.PixelLook;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelLookTest {
    private static BufferedImage gradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / Math.max(1, w - 1);
                int g = y * 255 / Math.max(1, h - 1);
                int b = (x + y) * 255 / Math.max(1, w + h - 2);
                img.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        return img;
    }

    @Test
    void lookValuesSnapToOfferedSteps() {
        assertEquals(new PixelLook(32, 16), new PixelLook(30, 20));
        assertEquals(PixelLook.OFF, new PixelLook(-5, -1));
        PixelLook l = PixelLook.OFF;
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < PixelLook.DENSITIES.length; i++) {
            l = l.nextDensity();
            seen.append(l.densityLabel()).append(' ');
        }
        assertEquals("16x 32x 64x 128x 256x Off ", seen.toString());
        assertEquals(new PixelLook(256, 0), PixelLook.OFF.previousDensity());
        assertEquals("64", new PixelLook(0, 0).nextColors().colorsLabel());
        assertEquals("32x", new PixelLook(32, 16).badge());
        assertEquals("16c", new PixelLook(0, 16).badge());
        assertEquals("", PixelLook.OFF.badge());
        assertTrue(PixelLook.OFF.isOff());
    }

    @Test
    void paletteReducesToAtMostNColors() {
        BufferedImage img = gradient(64, 64);
        assertTrue(Palette.countColors(img) > 1000);
        for (int n : new int[]{64, 32, 16, 8}) {
            BufferedImage reduced = Palette.reduce(img, n);
            int count = Palette.countColors(reduced);
            assertTrue(count <= n && count >= n / 2, n + " -> " + count);
            assertEquals(64, reduced.getWidth());
        }
        assertSame(img, Palette.reduce(img, 0));
    }

    @Test
    void paletteKeepsFewColorsExactly() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF123456};
        for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) img.setRGB(x, y, colors[(x + y) % 3]);
        BufferedImage reduced = Palette.reduce(img, 16);
        for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) assertEquals(img.getRGB(x, y), reduced.getRGB(x, y));
        // a single colour image
        BufferedImage flat = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) flat.setRGB(x, y, 0xFF808080);
        assertEquals(1, Palette.countColors(Palette.reduce(flat, 8)));
    }

    @Test
    void pixelLookPlansExactPixelsPerBlock() {
        PaintingPipeline.Settings base = new PaintingPipeline.Settings(4, true, 4096, 8, 256);
        PaintingPipeline.Plan plan = PaintingPipeline.plan(1920, 1080, base.withLook(new PixelLook(16, 0)));
        assertEquals(4, plan.blocks().width());
        assertEquals(2, plan.blocks().height());
        assertEquals(64, plan.outWidth());
        assertEquals(32, plan.outHeight());
        assertEquals(16, plan.resolution());

        // limited by the server's max image size: 16 blocks * 256 = 4096 > 1024 -> 64 px/block
        PaintingPipeline.Settings small = new PaintingPipeline.Settings(16, true, 1024, 8, 256);
        PaintingPipeline.Plan capped = PaintingPipeline.plan(4000, 4000, small.withLook(new PixelLook(256, 0)));
        assertEquals(64, capped.resolution());
        assertEquals(1024, capped.outWidth());

        // limited by IP's minimum resolution
        PaintingPipeline.Settings minRes = new PaintingPipeline.Settings(4, true, 4096, 32, 256);
        assertEquals(32, PaintingPipeline.plan(1000, 1000, minRes.withLook(new PixelLook(16, 0))).resolution());

        // off = unchanged behaviour
        PaintingPipeline.Plan off = PaintingPipeline.plan(1920, 1080, base);
        assertEquals(1920, off.outWidth());
        assertEquals(0, off.colors());
    }

    @Test
    void pixelLookProcessesImage() {
        BufferedImage src = gradient(800, 400);
        PaintingPipeline.Settings s = new PaintingPipeline.Settings(4, true, 4096, 8, 256).withLook(new PixelLook(16, 8));
        PaintingPipeline.Result r = PaintingPipeline.process(src, 800, 400, s);
        assertEquals(64, r.image().getWidth());
        assertEquals(32, r.image().getHeight());
        assertTrue(Palette.countColors(r.image()) <= 8);
        assertEquals(8, r.plan().colors());
        assertFalse(r.plan().resolution() != 16);
    }
}
