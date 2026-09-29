package com.pocketgallery;

import com.pocketgallery.image.ImageOps;
import com.pocketgallery.image.PaintingPipeline;
import com.pocketgallery.image.PaintingSizer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOpsTest {
    private static BufferedImage image(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        return img;
    }

    @Test
    void areaAverageOfCheckerboardIsGray() {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                img.setRGB(x, y, ((x + y) % 2 == 0) ? 0xFFFFFFFF : 0xFF000000);
        BufferedImage out = ImageOps.resize(img, 2, 2);
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++) {
                int p = out.getRGB(x, y);
                assertEquals(0xFF, p >>> 24);
                assertEquals(128, (p >> 16) & 0xFF, 1);
            }
    }

    @Test
    void nonIntegerDownscaleUsesFractionalCoverage() {
        // 3 columns: red, green, blue -> 2 columns: first = red + half green, second = half green + blue
        BufferedImage img = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);
        img.setRGB(1, 0, 0xFF00FF00);
        img.setRGB(2, 0, 0xFF0000FF);
        BufferedImage out = ImageOps.resize(img, 2, 1);
        int a = out.getRGB(0, 0);
        int b = out.getRGB(1, 0);
        assertEquals(170, (a >> 16) & 0xFF, 1);
        assertEquals(85, (a >> 8) & 0xFF, 1);
        assertEquals(0, a & 0xFF);
        assertEquals(0, (b >> 16) & 0xFF);
        assertEquals(85, (b >> 8) & 0xFF, 1);
        assertEquals(170, b & 0xFF, 1);
    }

    @Test
    void transparentPixelsDoNotBleedTheirColour() {
        // premultiplied averaging: a fully transparent "black" pixel must not darken the red one
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);
        img.setRGB(1, 0, 0x00000000);
        int p = ImageOps.resize(img, 1, 1).getRGB(0, 0);
        assertEquals(128, p >>> 24, 1);
        assertEquals(255, (p >> 16) & 0xFF);
    }

    @Test
    void resizeKeepsSolidColourAndSize() {
        BufferedImage out = ImageOps.resize(image(1000, 700, 0xFF336699), 333, 211);
        assertEquals(333, out.getWidth());
        assertEquals(211, out.getHeight());
        assertEquals(0xFF336699, out.getRGB(0, 0));
        assertEquals(0xFF336699, out.getRGB(332, 210));
        assertEquals(0xFF336699, out.getRGB(100, 100));
    }

    @Test
    void cropCopiesTheRectangle() {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(2, 1, 0xFF123456);
        BufferedImage c = ImageOps.crop(img, new PaintingSizer.Rect(2, 1, 2, 3));
        assertEquals(2, c.getWidth());
        assertEquals(3, c.getHeight());
        assertEquals(0xFF123456, c.getRGB(0, 0));
    }

    @Test
    void flattenCompositesOverBackground() {
        BufferedImage img = image(1, 1, 0x80000000); // half transparent black
        int p = ImageOps.flatten(img, 0xFFFFFF).getRGB(0, 0);
        assertEquals(0xFF, p >>> 24);
        assertEquals(127, (p >> 16) & 0xFF, 1);
        assertEquals(0xFF00FF00, ImageOps.flatten(image(1, 1, 0xFF00FF00), 0xFFFFFF).getRGB(0, 0));
    }

    @Test
    void abgrConversionSwapsRedAndBlue() {
        int[] abgr = ImageOps.toAbgr(image(1, 1, 0xFF112233));
        assertEquals(0xFF332211, abgr[0]);
    }

    @Test
    void pipelineProducesPlannedSizeAndOpaquePixels() {
        BufferedImage src = image(1920, 1080, 0x00FF0000);
        PaintingPipeline.Result r = PaintingPipeline.process(src, 1920, 1080, new PaintingPipeline.Settings(4, true, 4096, 8, 256));
        assertEquals(1920, r.image().getWidth());
        assertEquals(960, r.image().getHeight());
        assertTrue((r.image().getRGB(5, 5) >>> 24) == 0xFF);
    }

    @Test
    void pipelineHandlesSubsampledSource() {
        // decoder returned a half-size image of a 4000x2000 original
        BufferedImage half = image(2000, 1000, 0xFF00FF00);
        PaintingPipeline.Settings s = new PaintingPipeline.Settings(4, true, 1024, 8, 256);
        PaintingPipeline.Result r = PaintingPipeline.process(half, 4000, 2000, s);
        assertEquals(1024, r.image().getWidth());
        assertEquals(512, r.image().getHeight());
        assertEquals(0xFF00FF00, r.image().getRGB(10, 10));
    }
}
