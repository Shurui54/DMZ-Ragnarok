package net.shurui.shuruisutilities.hologram;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.hologram.network.PacketHologramGifs;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Images, dropped in a folder on the server, shown in the world as billboards.
 *
 * <p>The picture itself travels. The server reads nothing but each file's size on disk and its pixel dimensions,
 * zips the folder once, and streams that zip to each client on join, exactly as the rank badge textures already
 * do. Decoding, downscaling and animating all happen on the client, which is the only side that can put an image
 * on the screen anyway.
 *
 * <p>This replaces an earlier attempt that drew a gif as a grid of coloured block glyphs, one text character per
 * pixel. That worked on a vanilla client with no downloads at all, which was the whole point of it, but it could
 * only ever be about 32 pixels wide, it was lit like a nameplate rather than like a picture, and the wire cost
 * went up with every pixel that moved. A real texture has none of those properties. The trade is that a client
 * without this mod sees nothing where the billboard is, which is fine here because the mod is required to join.
 *
 * <p>What is NOT sent: anything outside the folder, and nothing at all to a client that never comes near a
 * hologram, because the transfer is per player on join and the folder is small by policy (see {@link #MAX_FILE}).
 */
public final class HologramGifs
{
    private HologramGifs() {}

    /** Where an admin drops the image files. Created on first load with a note inside. */
    public static final String FOLDER = "hologram_gifs";

    /** How much of the zip goes in one packet. The same figure the rank badges are streamed at. */
    private static final int CHUNK = 256 * 1024;

    /** A single image bigger than this is skipped: it is a billboard, not a video player. */
    private static final long MAX_FILE = 8L * 1024 * 1024;

    /** And the whole folder is capped too, because every byte of it is sent to every player who joins. */
    private static final long MAX_TOTAL = 24L * 1024 * 1024;

    /**
     * Frames past this are dropped by the client decoder.
     *
     * <p>Lives here rather than on the client so the two sides agree on what a long gif is; the server quotes it
     * in the log line an admin reads, the client enforces it.
     */
    public static final int MAX_FRAMES = 120;

    /** One loaded image: what it is called, and how big the picture is. The pixels are in {@link #zip}. */
    public record Gif(String name, int width, int height, long bytes)
    {
        /** The shape of the picture, used to turn a chosen width in blocks into a height in blocks. */
        public float aspect()
        {
            return width <= 0 ? 1.0f : height / (float) width;
        }
    }

    private static final Map<String, Gif> LOADED = new HashMap<>();

    /** The whole folder, zipped, ready to stream. Null when the folder is empty or unreadable. */
    private static byte[] zip;

    /** Changes whenever the folder's contents change, so a client can tell a resend from something new. */
    private static int version;

    public static java.util.Set<String> names()
    {
        return LOADED.keySet();
    }

    public static Gif get(String name)
    {
        return name == null ? null : LOADED.get(name.toLowerCase(Locale.ROOT));
    }

    public static int version()
    {
        return version;
    }

    /** {@code <gif:name>} anywhere in a line makes that line the gif. */
    public static String tagged(String line)
    {
        if (line == null)
            return null;
        String s = line.trim();
        if (!s.toLowerCase(Locale.ROOT).startsWith("<gif:") || !s.endsWith(">"))
            return null;
        return s.substring(5, s.length() - 1).trim().toLowerCase(Locale.ROOT);
    }

    public static File folder()
    {
        return new File(ShuruisUtilities.getSUDirectory(), FOLDER);
    }

    /** Re-read the folder and re-zip it. Returns how many images are now loaded. */
    public static synchronized int reload()
    {
        LOADED.clear();
        zip = null;
        version = 0;

        File dir = folder();
        if (!dir.isDirectory() && !dir.mkdirs())
        {
            LoggingHandler.sulog.warn("[Hologram] Could not create {}", dir);
            return 0;
        }
        readme(dir);

        File[] files = dir.listFiles((d, n) -> extensionOf(n) != null);
        if (files == null)
            return 0;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        Map<String, byte[]> payload = new TreeMap<>();
        long total = 0;
        for (File f : files)
        {
            if (f.length() > MAX_FILE)
            {
                LoggingHandler.sulog.warn("[Hologram] '{}' is {} KB, over the {} KB limit, so it was skipped",
                        f.getName(), f.length() / 1024, MAX_FILE / 1024);
                continue;
            }
            if (total + f.length() > MAX_TOTAL)
            {
                LoggingHandler.sulog.warn("[Hologram] '{}' was skipped: {} would put the folder over the {} MB that "
                        + "is streamed to every player on join", f.getName(), f.getName(), MAX_TOTAL / (1024 * 1024));
                continue;
            }

            try
            {
                Gif gif = read(f);
                if (gif == null)
                {
                    LoggingHandler.sulog.warn("[Hologram] '{}' is not an image this can read", f.getName());
                    continue;
                }
                LOADED.put(gif.name(), gif);
                payload.put(gif.name() + extensionOf(f.getName()), Files.readAllBytes(f.toPath()));
                total += f.length();
                LoggingHandler.sulog.info("[Hologram] image '{}': {}x{}, {} KB", gif.name(), gif.width(),
                        gif.height(), gif.bytes() / 1024);
            }
            catch (Exception e)
            {
                LoggingHandler.sulog.warn("[Hologram] Could not read {}: {}", f.getName(), e.toString());
            }
        }

        pack(payload);
        return LOADED.size();
    }

    /** Zip what was read, and hash it, so a client knows one version of the folder from another. */
    private static void pack(Map<String, byte[]> payload)
    {
        if (payload.isEmpty())
            return;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int h = 1;
        try (ZipOutputStream z = new ZipOutputStream(bos))
        {
            for (Map.Entry<String, byte[]> e : payload.entrySet())
            {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
                h = 31 * h + e.getKey().hashCode();
                h = 31 * h + Arrays.hashCode(e.getValue());
            }
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.error("[Hologram] Failed to zip the hologram images", e);
            return;
        }
        zip = bos.toByteArray();
        version = h;
        LoggingHandler.sulog.info("[Hologram] Prepared {} image(s) ({} KB) for client sync", payload.size(),
                zip.length / 1024);
    }

    private static void readme(File dir)
    {
        File note = new File(dir, "README.txt");
        if (note.exists())
            return;
        try
        {
            Files.write(note.toPath(), ("Drop .gif, .png or .jpg files in this folder and a hologram line of\n"
                    + "<gif:filename> will show that picture as a billboard in the world.\n\n"
                    + "The file itself is streamed to each player when they join and held in memory only, so keep\n"
                    + "the folder small: one file is capped at 8 MB and the whole folder at 24 MB. Animations are\n"
                    + "capped at " + MAX_FRAMES + " frames. How big the picture is drawn is a per hologram setting,\n"
                    + "not a property of the file: use /hologram size <name> <blocks wide>.\n\n"
                    + "Run /hologram reloadgifs after changing anything in here.\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException ignored)
        {
        }
    }

    /** The handled image extensions. A still is simply an animation of one frame. */
    private static String extensionOf(String fileName)
    {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : new String[] { ".gif", ".png", ".jpg", ".jpeg" })
            if (lower.endsWith(ext))
                return ext;
        return null;
    }

    /**
     * Measure one file.
     *
     * <p>Only the dimensions are wanted, so the pixels are never decoded here: an {@link ImageReader} answers
     * width and height straight out of the header.
     */
    private static Gif read(File file) throws Exception
    {
        String ext = extensionOf(file.getName());
        String name = file.getName().substring(0, file.getName().length() - ext.length()).toLowerCase(Locale.ROOT);

        try (ImageInputStream in = ImageIO.createImageInputStream(file))
        {
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
                return null;
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(in, false, false);
                int[] size = canvasSize(reader);
                if (size[0] <= 0 || size[1] <= 0)
                    return null;
                return new Gif(name, size[0], size[1], file.length());
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    /**
     * How big the finished picture is, in pixels.
     *
     * <p>A gif frame is often only the rectangle that changed, sat over the frame before it, so the picture is as
     * big as the frames' bounds reach and not necessarily as big as any one frame. Both sides of the wire call
     * this, so the width the server measures is the width the client draws.
     */
    public static int[] canvasSize(ImageReader reader) throws IOException
    {
        int count = Math.min(reader.getNumImages(true), MAX_FRAMES);
        int width = 0;
        int height = 0;
        for (int i = 0; i < count; i++)
        {
            int[] bounds = frameInfo(reader.getImageMetadata(i));
            width = Math.max(width, bounds[0] + reader.getWidth(i));
            height = Math.max(height, bounds[1] + reader.getHeight(i));
        }
        return new int[] { width, height };
    }

    /**
     * Frame placement and timing out of the image's own metadata.
     *
     * @return left, top, width, height, delay (hundredths of a second), disposal method
     */
    public static int[] frameInfo(IIOMetadata meta)
    {
        int[] info = { 0, 0, 0, 0, 10, 0 };
        if (meta == null || meta.getNativeMetadataFormatName() == null)
            return info;
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(meta.getNativeMetadataFormatName());
        for (int i = 0; i < root.getLength(); i++)
        {
            org.w3c.dom.Node node = root.item(i);
            String nodeName = node.getNodeName();
            if ("ImageDescriptor".equals(nodeName))
            {
                info[0] = intAttr(node, "imageLeftPosition", 0);
                info[1] = intAttr(node, "imageTopPosition", 0);
                info[2] = intAttr(node, "imageWidth", 0);
                info[3] = intAttr(node, "imageHeight", 0);
            }
            else if ("GraphicControlExtension".equals(nodeName))
            {
                info[4] = intAttr(node, "delayTime", 10);
                String disposal = strAttr(node, "disposalMethod");
                info[5] = "restoreToBackgroundColor".equals(disposal) ? 2
                        : ("restoreToPrevious".equals(disposal) ? 3 : 0);
            }
        }
        return info;
    }

    private static int intAttr(org.w3c.dom.Node node, String name, int fallback)
    {
        String v = strAttr(node, name);
        try
        {
            return v == null ? fallback : Integer.parseInt(v);
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private static String strAttr(org.w3c.dom.Node node, String name)
    {
        org.w3c.dom.Node attr = node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return attr == null ? null : attr.getNodeValue();
    }

    /** Stream the folder to one player, in chunks. Does nothing when there is nothing to send. */
    public static void sendTo(ServerPlayer player)
    {
        byte[] payload = zip;
        if (payload == null || payload.length == 0)
            return;
        int total = (payload.length + CHUNK - 1) / CHUNK;
        for (int i = 0; i < total; i++)
        {
            int off = i * CHUNK;
            byte[] part = Arrays.copyOfRange(payload, off, Math.min(off + CHUNK, payload.length));
            NetworkUtils.sendTo(new PacketHologramGifs(version, i, total, part), player);
        }
    }
}
