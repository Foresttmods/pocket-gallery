package com.pocketgallery.image;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Decodes animated GIFs into fully composited frames, honouring frame offsets, transparency and the three
 * disposal methods (none/doNotDispose, restoreToBackgroundColor, restoreToPrevious).
 *
 * <p>ImageIO's GIF reader only hands out the raw frame rectangles; the compositing is done here on an ARGB canvas
 * the size of the GIF's logical screen.</p>
 */
public final class GifDecoder {
    /** Browsers treat delays below 20 ms as 100 ms; do the same so "0 delay" GIFs don't spin at tick rate. */
    public static final int DEFAULT_DELAY_MS = 100;

    private GifDecoder() {
    }

    /** Receives each composited frame. {@code canvas} is reused between calls - copy it if you keep it. */
    @FunctionalInterface
    public interface FrameConsumer {
        /** @return false to stop decoding early */
        boolean accept(int index, BufferedImage canvas, int delayMs);
    }

    public record Frame(BufferedImage image, int delayMs) {
    }

    public record Info(int width, int height, int frames) {
    }

    /** Decodes up to {@code maxFrames} frames into memory (copies). Convenient for tests and small GIFs. */
    public static List<Frame> decodeAll(InputStream in, int maxFrames) throws IOException {
        List<Frame> frames = new ArrayList<>();
        decode(in, maxFrames, (index, canvas, delay) -> {
            frames.add(new Frame(copy(canvas), delay));
            return true;
        });
        return frames;
    }

    /** Decodes only the first frame, composited on a transparent canvas of the logical screen size. */
    public static BufferedImage decodeFirstFrame(InputStream in) throws IOException {
        BufferedImage[] first = new BufferedImage[1];
        decode(in, 1, (index, canvas, delay) -> {
            first[0] = copy(canvas);
            return false;
        });
        if (first[0] == null) throw new IOException("GIF contains no frames");
        return first[0];
    }

    /**
     * Streams composited frames to {@code consumer}.
     *
     * @return information about the decoded GIF (frame count = frames delivered)
     */
    public static Info decode(InputStream in, int maxFrames, FrameConsumer consumer) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            if (stream == null) throw new IOException("Cannot open image stream");
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("No GIF reader available");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                return decode(reader, maxFrames, consumer);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Info decode(ImageReader reader, int maxFrames, FrameConsumer consumer) throws IOException {
        int screenW = 0;
        int screenH = 0;
        IIOMetadata streamMeta = reader.getStreamMetadata();
        if (streamMeta != null) {
            Node root = streamMeta.getAsTree("javax_imageio_gif_stream_1.0");
            Node lsd = child(root, "LogicalScreenDescriptor");
            if (lsd != null) {
                screenW = intAttr(lsd, "logicalScreenWidth", 0);
                screenH = intAttr(lsd, "logicalScreenHeight", 0);
            }
        }

        int[] canvas = null;
        int[] previous = null;
        BufferedImage canvasImage = null;
        int delivered = 0;

        for (int index = 0; index < maxFrames; index++) {
            BufferedImage frame;
            IIOMetadata meta;
            try {
                frame = reader.read(index);
                meta = reader.getImageMetadata(index);
            } catch (IndexOutOfBoundsException end) {
                break; // no more frames
            } catch (IOException | RuntimeException broken) {
                // Truncated/corrupt tail (ImageIO throws various runtime exceptions): keep what we have.
                if (delivered == 0) throw broken instanceof IOException io ? io : new IOException(broken);
                break;
            }

            Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
            Node desc = child(root, "ImageDescriptor");
            Node gce = child(root, "GraphicControlExtension");
            int left = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
            int top = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
            String disposal = gce == null ? "none" : strAttr(gce, "disposalMethod", "none");
            int delayCs = gce == null ? 0 : intAttr(gce, "delayTime", 0);
            int delayMs = delayCs < 2 ? DEFAULT_DELAY_MS : delayCs * 10;

            if (canvas == null) {
                // Some encoders write a 0x0 logical screen; fall back to the first frame's extent.
                if (screenW <= 0 || screenH <= 0) {
                    screenW = left + frame.getWidth();
                    screenH = top + frame.getHeight();
                }
                canvas = new int[screenW * screenH];
                canvasImage = new BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_ARGB);
            }

            if ("restoreToPrevious".equals(disposal)) {
                previous = canvas.clone();
            }

            // draw the frame: GIF transparency is binary, so transparent pixels simply leave the canvas alone
            int fw = frame.getWidth();
            int fh = frame.getHeight();
            int[] row = new int[fw];
            for (int y = 0; y < fh; y++) {
                int cy = top + y;
                if (cy < 0 || cy >= screenH) continue;
                frame.getRGB(0, y, fw, 1, row, 0, fw);
                for (int x = 0; x < fw; x++) {
                    int cx = left + x;
                    if (cx < 0 || cx >= screenW) continue;
                    int p = row[x];
                    if ((p >>> 24) != 0) canvas[cy * screenW + cx] = p;
                }
            }

            canvasImage.setRGB(0, 0, screenW, screenH, canvas, 0, screenW);
            delivered++;
            boolean more = consumer.accept(index, canvasImage, delayMs);

            // apply this frame's disposal before the next one is drawn
            switch (disposal) {
                case "restoreToBackgroundColor" -> {
                    // Browsers clear to transparent rather than the background colour index.
                    for (int y = Math.max(0, top); y < Math.min(screenH, top + fh); y++) {
                        int from = y * screenW + Math.max(0, left);
                        int to = y * screenW + Math.min(screenW, left + fw);
                        if (to > from) java.util.Arrays.fill(canvas, from, to, 0);
                    }
                }
                case "restoreToPrevious" -> {
                    if (previous != null) System.arraycopy(previous, 0, canvas, 0, canvas.length);
                }
                default -> {
                    // none / doNotDispose: keep the canvas
                }
            }
            previous = null;

            if (!more) break;
        }
        return new Info(screenW, screenH, delivered);
    }

    /** Counts frames by scanning the file (no pixel decoding of frames). Returns 1 if the file can't be scanned. */
    public static int countFrames(InputStream in) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (stream == null || !readers.hasNext()) return 1;
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, true);
                return Math.max(1, reader.getNumImages(true));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    /** Cheap header read: logical screen size without decoding frames. */
    public static Info readInfo(InputStream in) throws IOException {
        int[] size = new int[2];
        Info info = decode(in, 1, (i, canvas, d) -> {
            size[0] = canvas.getWidth();
            size[1] = canvas.getHeight();
            return false;
        });
        return new Info(size[0], size[1], info.frames());
    }

    static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] px = src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
        out.setRGB(0, 0, src.getWidth(), src.getHeight(), px, 0, src.getWidth());
        return out;
    }

    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName())) return n;
        }
        return null;
    }

    private static String strAttr(Node node, String name, String def) {
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) return def;
        Node a = attrs.getNamedItem(name);
        return a == null ? def : a.getNodeValue();
    }

    private static int intAttr(Node node, String name, int def) {
        try {
            return Integer.parseInt(strAttr(node, name, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
