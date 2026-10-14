import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

/**
 * Google Flow - Animated GIF Writer
 * =================================
 * Builds a looping animated GIF frame-by-frame using only
 * javax.imageio (bundled with every JDK - no external GIF library).
 * The technique: get the standard "gif" ImageWriter, hand it a small
 * XML-ish IIOMetadata tree per frame describing the frame delay, and
 * one Application Extension block (NETSCAPE2.0) on the very first
 * frame's metadata to make the whole GIF loop forever.
 */
public class AnimatedGifWriter {

    private final ImageWriter writer;
    private final ImageOutputStream output;
    private final int delayCentiseconds;
    private boolean first = true;

    public AnimatedGifWriter(File outFile, int delayCentiseconds) throws Exception {
        this.delayCentiseconds = delayCentiseconds;
        Iterator<ImageWriter> it = ImageIO.getImageWritersBySuffix("gif");
        if (!it.hasNext()) throw new IllegalStateException("No GIF writer available in this JDK");
        this.writer = it.next();
        this.output = ImageIO.createImageOutputStream(outFile);
        writer.setOutput(output);
        writer.prepareWriteSequence(null);
    }

    public void addFrame(BufferedImage frame) throws Exception {
        addFrame(frame, delayCentiseconds);
    }

    /** Same as addFrame(frame) but with its own delay (used by the Scene Builder to hold still images). */
    public void addFrame(BufferedImage frame, int delayCs) throws Exception {
        ImageWriteParam params = writer.getDefaultWriteParam();
        ImageTypeSpecifierHolder typeSpecifier = new ImageTypeSpecifierHolder(frame);
        IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier.spec, params);

        String metaFormat = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metaFormat);

        IIOMetadataNode gce = getOrCreateNode(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", Integer.toString(delayCs));
        gce.setAttribute("transparentColorIndex", "0");

        if (first) {
            IIOMetadataNode appExtensions = getOrCreateNode(root, "ApplicationExtensions");
            IIOMetadataNode netscape = new IIOMetadataNode("ApplicationExtension");
            netscape.setAttribute("applicationID", "NETSCAPE");
            netscape.setAttribute("authenticationCode", "2.0");
            // loop forever: sub-block 1, loop count 0 (0 = infinite), little-endian
            netscape.setUserObject(new byte[]{0x1, 0x0, 0x0});
            appExtensions.appendChild(netscape);
            first = false;
        }

        metadata.setFromTree(metaFormat, root);
        writer.writeToSequence(new IIOImage(frame, null, metadata), params);
    }

    public void close() throws Exception {
        writer.endWriteSequence();
        output.close();
    }

    private static IIOMetadataNode getOrCreateNode(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    /** Tiny helper so we can build an ImageTypeSpecifier for the writer's default metadata call. */
    private static class ImageTypeSpecifierHolder {
        final javax.imageio.ImageTypeSpecifier spec;
        ImageTypeSpecifierHolder(BufferedImage img) {
            spec = javax.imageio.ImageTypeSpecifier.createFromRenderedImage(img);
        }
    }
}
