package com.pocketgallery;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Builds GIFs with explicit frame offsets, disposal methods and transparency using ImageIO's GIF writer. */
final class TestGifs {
    /** Palette: 0 = transparent, 1 = red, 2 = green, 3 = blue, 4 = white. */
    static final int T = 0, R = 1, G = 2, B = 3, W = 4;
    static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, WHITE = 0xFFFFFFFF;

    record Frame(int left, int top, int width, int height, int[] indices, String disposal, int delayCs) {
    }

    private TestGifs() {
    }

    static byte[] write(int screenW, int screenH, List<Frame> frames) throws IOException {
        byte[] r = {0, (byte) 255, 0, 0, (byte) 255};
        byte[] g = {0, 0, (byte) 255, 0, (byte) 255};
        byte[] b = {0, 0, 0, (byte) 255, (byte) 255};
        IndexColorModel palette = new IndexColorModel(8, 5, r, g, b, 0); // index 0 transparent

        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();

            // logical screen size
            IIOMetadata streamMeta = writer.getDefaultStreamMetadata(param);
            String streamFormat = "javax_imageio_gif_stream_1.0";
            IIOMetadataNode streamRoot = (IIOMetadataNode) streamMeta.getAsTree(streamFormat);
            IIOMetadataNode lsd = child(streamRoot, "LogicalScreenDescriptor");
            lsd.setAttribute("logicalScreenWidth", String.valueOf(screenW));
            lsd.setAttribute("logicalScreenHeight", String.valueOf(screenH));
            lsd.setAttribute("colorResolution", "8");
            lsd.setAttribute("pixelAspectRatio", "0");
            streamMeta.setFromTree(streamFormat, streamRoot);
            writer.prepareWriteSequence(streamMeta);

            for (Frame f : frames) {
                BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_BYTE_INDEXED, palette);
                img.getRaster().setPixels(0, 0, f.width(), f.height(), f.indices());

                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
                String format = "javax_imageio_gif_image_1.0";
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode desc = child(root, "ImageDescriptor");
                desc.setAttribute("imageLeftPosition", String.valueOf(f.left()));
                desc.setAttribute("imageTopPosition", String.valueOf(f.top()));
                desc.setAttribute("imageWidth", String.valueOf(f.width()));
                desc.setAttribute("imageHeight", String.valueOf(f.height()));
                desc.setAttribute("interlaceFlag", "FALSE");
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", f.disposal());
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "TRUE");
                gce.setAttribute("transparentColorIndex", "0");
                gce.setAttribute("delayTime", String.valueOf(f.delayCs()));
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(img, null, meta), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static int[] fill(int w, int h, int index) {
        int[] a = new int[w * h];
        java.util.Arrays.fill(a, index);
        return a;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equals(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
