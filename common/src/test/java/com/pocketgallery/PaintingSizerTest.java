package com.pocketgallery;

import com.pocketgallery.image.PaintingPipeline;
import com.pocketgallery.image.PaintingSizer;
import com.pocketgallery.image.PaintingSizer.BlockSize;
import com.pocketgallery.image.PaintingSizer.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaintingSizerTest {
    @Test
    void sixteenByNineAtLongSideFourPicksFourByTwo() {
        // 4x2 (2.0) is closer in log space to 1.78 than 4x3 (1.33)
        assertEquals(new BlockSize(4, 2), PaintingSizer.fit(1920, 1080, 4));
    }

    @Test
    void commonAspectRatios() {
        assertEquals(new BlockSize(4, 4), PaintingSizer.fit(1000, 1000, 4));
        assertEquals(new BlockSize(4, 3), PaintingSizer.fit(1600, 1200, 4));
        assertEquals(new BlockSize(3, 4), PaintingSizer.fit(1200, 1600, 4));
        assertEquals(new BlockSize(16, 9), PaintingSizer.fit(1920, 1080, 16));
        assertEquals(new BlockSize(2, 3), PaintingSizer.fit(1000, 1500, 3));
        assertEquals(new BlockSize(1, 1), PaintingSizer.fit(10, 30, 1));
    }

    @Test
    void extremePanoramaClampsShortSideToOne() {
        assertEquals(new BlockSize(4, 1), PaintingSizer.fit(10000, 500, 4));
        assertEquals(new BlockSize(1, 4), PaintingSizer.fit(500, 10000, 4));
    }

    @Test
    void longSideIsClampedToImmersivePaintingsLimits() {
        assertEquals(16, PaintingSizer.fit(100, 100, 99).longSide());
        assertEquals(1, PaintingSizer.fit(100, 100, 0).longSide());
    }

    @Test
    void chosenSizeMinimisesLogError() {
        // brute force over every short side and check the chosen one is optimal
        for (int w = 1; w <= 50; w++) {
            for (int h = 1; h <= 50; h++) {
                for (int l = 1; l <= 16; l++) {
                    BlockSize b = PaintingSizer.fit(w, h, l);
                    assertEquals(l, b.longSide());
                    double aspect = (double) w / h;
                    double best = Math.abs(Math.log(((double) b.width() / b.height()) / aspect));
                    for (int s = 1; s <= l; s++) {
                        int cw = w >= h ? l : s;
                        int ch = w >= h ? s : l;
                        double err = Math.abs(Math.log(((double) cw / ch) / aspect));
                        assertTrue(best <= err + 1e-9, "not optimal for " + w + "x" + h + " l=" + l);
                    }
                }
            }
        }
    }

    @Test
    void centerCropTrimsTheLongerDimension() {
        // 16:9 into 4x2 (2:1): too tall -> keep full width, trim height
        assertEquals(new Rect(0, 60, 1920, 960), PaintingSizer.centerCrop(1920, 1080, 4, 2));
        // 16:9 into 4x3: too wide -> trim width
        assertEquals(new Rect(240, 0, 1440, 1080), PaintingSizer.centerCrop(1920, 1080, 4, 3));
        // exact ratio: untouched
        assertEquals(new Rect(0, 0, 800, 600), PaintingSizer.centerCrop(800, 600, 4, 3));
        // portrait
        assertEquals(new Rect(0, 150, 1000, 1333), PaintingSizer.centerCrop(1000, 1633, 3, 4));
    }

    @Test
    void limitSizeOnlyDownscales() {
        assertArrayEquals(new int[]{4096, 2048}, PaintingSizer.limitSize(8000, 4000, 4096));
        assertArrayEquals(new int[]{2048, 4096}, PaintingSizer.limitSize(3000, 6000, 4096));
        assertArrayEquals(new int[]{1000, 500}, PaintingSizer.limitSize(1000, 500, 4096));
    }

    @Test
    void resolutionIsLongSidePixelsPerLongSideBlockClamped() {
        BlockSize four = new BlockSize(4, 2);
        assertEquals(256, PaintingSizer.resolution(1920, 960, four, 8, 256)); // 480 -> 256
        assertEquals(100, PaintingSizer.resolution(400, 200, four, 8, 256));
        assertEquals(8, PaintingSizer.resolution(16, 8, four, 8, 256));   // 4 -> 8
        assertEquals(63, PaintingSizer.resolution(250, 125, four, 8, 256)); // 62.5 rounds up
    }

    @Test
    void pipelinePlanCombinesEverything() {
        PaintingPipeline.Settings s = new PaintingPipeline.Settings(4, true, 4096, 8, 256);
        PaintingPipeline.Plan plan = PaintingPipeline.plan(10000, 5625, s); // 16:9, huge
        assertEquals(new BlockSize(4, 2), plan.blocks());
        assertEquals(new Rect(0, 312, 10000, 5000), plan.crop());
        assertEquals(4096, plan.outWidth());
        assertEquals(2048, plan.outHeight());
        assertEquals(256, plan.resolution());

        PaintingPipeline.Plan stretched = PaintingPipeline.plan(1920, 1080, new PaintingPipeline.Settings(4, false, 4096, 8, 256));
        assertEquals(new Rect(0, 0, 1920, 1080), stretched.crop());
        assertEquals(1920, stretched.outWidth());
    }
}
