package com.pocketgallery.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Decoding entry points for gallery images (files or downloaded bytes). All methods are blocking and meant to be
 * called from worker threads.
 */
public final class ImageLoader {
    public static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "bmp", "gif");

    private ImageLoader() {
    }

    public record Size(int width, int height) {
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isSupported(String fileName) {
        return EXTENSIONS.contains(extension(fileName));
    }

    /** GIF detection by magic number ("GIF87a"/"GIF89a"), independent of the file name. */
    public static boolean isGif(byte[] header) {
        return header.length >= 4 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8';
    }

    public static boolean isGif(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return isGif(in.readNBytes(4));
        } catch (IOException e) {
            return extension(file.getFileName().toString()).equals("gif");
        }
    }

    public static byte[] readAll(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    /** Reads only the header to get the pixel size. */
    public static Size readSize(byte[] data) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            return readSize(in);
        }
    }

    public static Size readSize(Path file) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
             ImageInputStream in = ImageIO.createImageInputStream(raw)) {
            return readSize(in);
        }
    }

    private static Size readSize(ImageInputStream in) throws IOException {
        if (in == null) throw new IOException("Cannot open image");
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new IOException("Unsupported image format");
        ImageReader reader = readers.next();
        try {
            reader.setInput(in, true, true);
            return new Size(reader.getWidth(0), reader.getHeight(0));
        } finally {
            reader.dispose();
        }
    }

    /**
     * Decodes a still image (for GIFs: the composited first frame). When the image is much larger than
     * {@code targetMaxSide} the decoder subsamples to at least 2x the target to save memory; the caller does the
     * final high-quality resize.
     */
    public static BufferedImage decode(byte[] data, int targetMaxSide) throws IOException {
        if (isGif(data)) {
            return GifDecoder.decodeFirstFrame(new ByteArrayInputStream(data));
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            return decodeStill(in, targetMaxSide);
        }
    }

    public static BufferedImage decode(Path file, int targetMaxSide) throws IOException {
        return decode(Files.readAllBytes(file), targetMaxSide);
    }

    private static BufferedImage decodeStill(ImageInputStream in, int targetMaxSide) throws IOException {
        if (in == null) throw new IOException("Cannot open image");
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new IOException("Unsupported image format (webp needs Immersive Paintings' bundled reader)");
        ImageReader reader = readers.next();
        try {
            reader.setInput(in, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            ImageReadParam param = reader.getDefaultReadParam();
            int factor = subsampleFactor(Math.max(w, h), targetMaxSide);
            if (factor > 1) param.setSourceSubsampling(factor, factor, 0, 0);
            BufferedImage img = reader.read(0, param);
            if (img == null) throw new IOException("Image could not be decoded");
            return img;
        } finally {
            reader.dispose();
        }
    }

    /** Subsampling keeps at least 2x the target so the following area-average resize still has detail. */
    static int subsampleFactor(int longSide, int targetMaxSide) {
        if (targetMaxSide <= 0) return 1;
        return Math.max(1, longSide / (targetMaxSide * 2));
    }

    /** Small preview image, at most {@code maxSide} on its long side. */
    public static BufferedImage thumbnail(byte[] data, int maxSide) throws IOException {
        BufferedImage img = decode(data, maxSide);
        return ImageOps.fitWithin(img, maxSide, maxSide);
    }
}
