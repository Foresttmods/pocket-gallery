package com.pocketgallery;

import com.pocketgallery.anim.AnimatedIndex;
import com.pocketgallery.anim.FrameBuilder;
import com.pocketgallery.image.GifDecoder;
import com.pocketgallery.image.ImageLoader;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static com.pocketgallery.TestGifs.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifDecoderTest {
    private static byte[] disposalGif() throws Exception {
        return TestGifs.write(4, 4, List.of(
                new Frame(0, 0, 4, 4, fill(4, 4, R), "none", 10),
                new Frame(1, 1, 2, 2, fill(2, 2, B), "restoreToBackgroundColor", 20),
                new Frame(0, 0, 2, 2, new int[]{G, T, T, G}, "restoreToPrevious", 0),
                new Frame(3, 3, 1, 1, new int[]{W}, "none", 5)
        ));
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    @Test
    void compositesFramesWithDisposalAndTransparency() throws Exception {
        List<GifDecoder.Frame> frames = GifDecoder.decodeAll(new ByteArrayInputStream(disposalGif()), 100);
        assertEquals(4, frames.size());

        BufferedImage f0 = frames.get(0).image();
        assertEquals(4, f0.getWidth());
        assertEquals(4, f0.getHeight());
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) assertEquals(RED, f0.getRGB(x, y));

        // frame 1: blue square on red
        BufferedImage f1 = frames.get(1).image();
        assertEquals(BLUE, f1.getRGB(1, 1));
        assertEquals(BLUE, f1.getRGB(2, 2));
        assertEquals(RED, f1.getRGB(0, 0));
        assertEquals(RED, f1.getRGB(3, 3));

        // frame 2: frame 1's area was cleared (restoreToBackgroundColor), transparent pixels keep the canvas
        BufferedImage f2 = frames.get(2).image();
        assertEquals(GREEN, f2.getRGB(0, 0));
        assertEquals(GREEN, f2.getRGB(1, 1));
        assertEquals(RED, f2.getRGB(1, 0), "transparent frame pixel must show the canvas below");
        assertEquals(RED, f2.getRGB(0, 1));
        assertEquals(0, alpha(f2.getRGB(2, 2)), "disposed area is transparent");
        assertEquals(RED, f2.getRGB(3, 0));

        // frame 3: frame 2 was undone (restoreToPrevious)
        BufferedImage f3 = frames.get(3).image();
        assertEquals(RED, f3.getRGB(0, 0));
        assertEquals(0, alpha(f3.getRGB(1, 1)));
        assertEquals(WHITE, f3.getRGB(3, 3));
        assertEquals(RED, f3.getRGB(3, 2));
    }

    @Test
    void delaysAreConvertedAndZeroDelayDefaultsTo100ms() throws Exception {
        List<GifDecoder.Frame> frames = GifDecoder.decodeAll(new ByteArrayInputStream(disposalGif()), 100);
        assertEquals(100, frames.get(0).delayMs());
        assertEquals(200, frames.get(1).delayMs());
        assertEquals(GifDecoder.DEFAULT_DELAY_MS, frames.get(2).delayMs());
        assertEquals(50, frames.get(3).delayMs());
    }

    @Test
    void maxFramesLimitsDecoding() throws Exception {
        assertEquals(2, GifDecoder.decodeAll(new ByteArrayInputStream(disposalGif()), 2).size());
        assertEquals(4, GifDecoder.countFrames(new ByteArrayInputStream(disposalGif())));
    }

    @Test
    void firstFrameAndSniffing() throws Exception {
        byte[] gif = disposalGif();
        assertTrue(ImageLoader.isGif(gif));
        BufferedImage first = GifDecoder.decodeFirstFrame(new ByteArrayInputStream(gif));
        assertEquals(RED, first.getRGB(2, 2));
        // the generic loader returns the composited first frame too
        BufferedImage viaLoader = ImageLoader.decode(gif, 4096);
        assertEquals(RED, viaLoader.getRGB(2, 2));
        assertEquals(4, ImageLoader.readSize(gif).width());
    }

    @Test
    void frameOffsetsOutsideTheScreenAreClipped() throws Exception {
        byte[] gif = TestGifs.write(2, 2, List.of(
                new Frame(0, 0, 2, 2, fill(2, 2, R), "none", 10),
                new Frame(1, 1, 2, 2, fill(2, 2, B), "none", 10)));
        List<GifDecoder.Frame> frames = GifDecoder.decodeAll(new ByteArrayInputStream(gif), 10);
        BufferedImage f1 = frames.get(1).image();
        assertEquals(2, f1.getWidth());
        assertEquals(BLUE, f1.getRGB(1, 1));
        assertEquals(RED, f1.getRGB(0, 0));
    }

    @Test
    void frameBuilderScalesCropsAndDecimates() throws Exception {
        // 6 frames alternating red/blue on 8x4, each 10cs
        List<Frame> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) list.add(new Frame(0, 0, 8, 4, fill(8, 4, i % 2 == 0 ? R : B), "none", 10));
        byte[] gif = TestGifs.write(8, 4, list);

        // crop the left 4x4 square, two LOD targets
        AnimatedIndex.Entry entry = new AnimatedIndex.Entry("x.gif", 0, 0, 4, 4, 8, 4);
        List<FrameBuilder.Target> targets = List.of(new FrameBuilder.Target(4, 4), new FrameBuilder.Target(2, 2));

        FrameBuilder.Frames all = FrameBuilder.build(gif, entry, targets, 100, Long.MAX_VALUE, () -> false);
        assertNotNull(all);
        assertEquals(6, all.count());
        assertEquals(600, all.totalDurationMs());
        assertEquals(16, all.frames().get(0).get(0).length);
        assertEquals(4, all.frames().get(1).get(0).length);
        // ABGR red = 0xFF0000FF
        assertEquals(0xFF0000FF, all.frames().get(0).get(0)[0]);
        assertEquals(0xFFFF0000, all.frames().get(1).get(1)[3]);

        // cap of 3 frames -> every 2nd frame kept, delays summed so the loop keeps its speed
        FrameBuilder.Frames decimated = FrameBuilder.build(gif, entry, targets, 3, Long.MAX_VALUE, () -> false);
        assertNotNull(decimated);
        assertEquals(3, decimated.count());
        assertEquals(600, decimated.totalDurationMs());

        // memory budget below two frames -> not animated
        assertNull(FrameBuilder.build(gif, entry, targets, 100, 100, () -> false));
    }
}
