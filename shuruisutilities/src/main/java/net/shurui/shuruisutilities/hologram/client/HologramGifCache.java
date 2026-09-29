package net.shurui.shuruisutilities.hologram.client;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;

import net.shurui.shuruisutilities.hologram.HologramGifs;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Client side of the hologram images: reassemble the streamed zip, decode a picture the first time something
 * asks to draw it, and hand out the texture of whichever frame is due.
 *
 * <p>Decoding is done ONCE per image and off the render thread, because a hundred frame gif is a hundred
 * downscales and nobody wants that on the frame that first sees it. Only the upload has to be on the render
 * thread, so that is all that is enqueued there.
 *
 * <p>Which frame is showing is worked out from the wall clock rather than from a counter, so every player sees
 * the same frame at the same moment and a player who walks up late does not start the loop over.
 *
 * <p>The bytes are held in memory only: nothing here is written to a player's disk, matching how the rank badge
 * textures are handled.
 */
public final class HologramGifCache
{
    private HologramGifCache() {}

    /**
     * How big a decoded frame is allowed to be, in pixels on its longest side.
     *
     * <p>This is a memory ceiling, not a quality one. Every frame is an uncompressed texture on the graphics
     * card, so a 1080p gif of a hundred frames would be most of a gigabyte. A still can afford to be sharper
     * because there is only one of it.
     */
    private static final int MAX_SIDE_STILL = 512;
    private static final int MAX_SIDE_ANIMATED = 256;

    /** Under this many frames an image counts as a still for the purposes of the size ceiling. */
    private static final int STILL_FRAMES = 4;

    /** A frame is never played faster than this, which is also what a gif's "as fast as possible" delay means. */
    private static final int MIN_FRAME_MS = 20;

    private static int pendingVersion;
    private static byte[][] chunks;
    private static int received;
    private static int loadedVersion;
    private static boolean loaded;

    /** name -> the original file's bytes, kept so an image can be decoded the first time it is actually seen. */
    private static final Map<String, byte[]> RAW = new HashMap<>();

    /** name -> its frames, once decoded and uploaded. */
    private static final Map<String, Animation> READY = new ConcurrentHashMap<>();

    /** Names currently being decoded, so a render that happens every frame only starts the work once. */
    private static final Set<String> DECODING = ConcurrentHashMap.newKeySet();

    /** Handle one chunk of the streamed zip. Runs on the client thread. */
    public static synchronized void accept(int version, int index, int total, byte[] data)
    {
        if (loaded && version == loadedVersion)
            return;     // already have this exact folder
        if (chunks == null || version != pendingVersion || chunks.length != total)
        {
            pendingVersion = version;
            chunks = new byte[total][];
            received = 0;
        }
        if (index < 0 || index >= chunks.length || chunks[index] != null)
            return;
        chunks[index] = data;
        if (++received < total)
            return;

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try
        {
            for (byte[] c : chunks)
                all.write(c);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("[Hologram] Failed to assemble the image chunks", e);
            chunks = null;
            return;
        }
        chunks = null;
        apply(version, all.toByteArray());
    }

    private static void apply(int version, byte[] zipBytes)
    {
        Map<String, byte[]> files = unzip(zipBytes);
        if (files.isEmpty())
            return;

        // whatever was on the card belongs to the old version of the folder
        for (Animation animation : READY.values())
            animation.release();
        READY.clear();
        DECODING.clear();
        RAW.clear();

        for (Map.Entry<String, byte[]> e : files.entrySet())
        {
            String file = e.getKey();
            int dot = file.lastIndexOf('.');
            RAW.put((dot < 0 ? file : file.substring(0, dot)).toLowerCase(Locale.ROOT), e.getValue());
        }
        loadedVersion = version;
        loaded = true;
        LoggingHandler.sulog.info("[Hologram] Received {} image(s) from the server", RAW.size());
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes)
    {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes)))
        {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zip.getNextEntry()) != null)
            {
                if (entry.isDirectory())
                    continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buf)) > 0)
                    out.write(buf, 0, n);
                files.put(entry.getName(), out.toByteArray());
            }
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("[Hologram] Failed to unzip the images", e);
            return new HashMap<>();
        }
        return files;
    }

    /**
     * The texture of the frame due right now, or null when this image is not ready to draw yet.
     *
     * <p>A null is not an error and does not need reporting: it is what the first sight of a picture looks like
     * while it decodes, and the renderer simply skips the billboard until there is something to put on it.
     */
    public static ResourceLocation frame(String name, long timeMs)
    {
        if (name == null)
            return null;
        String key = name.toLowerCase(Locale.ROOT);
        Animation animation = READY.get(key);
        if (animation != null)
            return animation.at(timeMs);
        decode(key);
        return null;
    }

    private static void decode(String name)
    {
        byte[] bytes;
        synchronized (HologramGifCache.class)
        {
            bytes = RAW.get(name);
        }
        if (bytes == null || !DECODING.add(name))
            return;

        Util.backgroundExecutor().execute(() ->
        {
            List<RawFrame> frames;
            try
            {
                frames = read(bytes);
            }
            catch (Exception e)
            {
                LoggingHandler.sulog.warn("[Hologram] Could not decode image '{}'", name, e);
                DECODING.remove(name);
                return;
            }
            if (frames.isEmpty())
            {
                DECODING.remove(name);
                return;
            }
            // the upload is the only part that needs the render thread
            Minecraft.getInstance().execute(() -> upload(name, frames));
        });
    }

    private static void upload(String name, List<RawFrame> frames)
    {
        try
        {
            ResourceLocation[] ids = new ResourceLocation[frames.size()];
            int[] delays = new int[frames.size()];
            int totalMs = 0;
            for (int i = 0; i < frames.size(); i++)
            {
                RawFrame frame = frames.get(i);
                ResourceLocation id = new ResourceLocation("dmz_ragnarok", "hologram_gif/" + name + "/" + i);
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(frame.image()));
                ids[i] = id;
                delays[i] = frame.delayMs();
                totalMs += frame.delayMs();
            }
            READY.put(name, new Animation(ids, delays, totalMs));
            LoggingHandler.sulog.info("[Hologram] Image '{}' ready: {} frame(s), {}x{}, {}ms loop", name, ids.length,
                    frames.get(0).image().getWidth(), frames.get(0).image().getHeight(), totalMs);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("[Hologram] Could not upload image '{}'", name, e);
        }
        finally
        {
            DECODING.remove(name);
        }
    }

    /** One image's frames, on the card. */
    public record Animation(ResourceLocation[] frames, int[] delays, int totalMs)
    {
        /** Which frame is showing at a given moment, by wall clock, so every viewer agrees. */
        public ResourceLocation at(long timeMs)
        {
            if (frames.length <= 1 || totalMs <= 0)
                return frames[0];
            long t = Math.floorMod(timeMs, (long) totalMs);
            for (int i = 0; i < frames.length; i++)
            {
                t -= delays[i];
                if (t < 0)
                    return frames[i];
            }
            return frames[frames.length - 1];
        }

        private void release()
        {
            for (ResourceLocation id : frames)
                Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** One decoded frame, before it goes to the card. */
    private record RawFrame(NativeImage image, int delayMs) {}

    /**
     * Decode a file into finished frames.
     *
     * <p>A gif frame is usually only the rectangle that changed, drawn over the frame before it, and it carries a
     * disposal rule saying what to do with that rectangle afterwards. So the frames are composited onto a canvas
     * in order rather than read as complete pictures; skipping that gives the smeared, ghosting result that
     * looks like the gif is being drawn twice.
     */
    private static List<RawFrame> read(byte[] bytes) throws Exception
    {
        List<RawFrame> out = new ArrayList<>();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
        {
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
                return out;
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(in, false, false);
                int count = Math.min(reader.getNumImages(true), HologramGifs.MAX_FRAMES);
                if (count <= 0)
                    return out;

                int[] size = HologramGifs.canvasSize(reader);
                int canvasW = size[0];
                int canvasH = size[1];
                if (canvasW <= 0 || canvasH <= 0)
                    return out;

                int maxSide = count <= STILL_FRAMES ? MAX_SIDE_STILL : MAX_SIDE_ANIMATED;
                double fit = Math.min(1.0, maxSide / (double) Math.max(canvasW, canvasH));
                int width = Math.max(1, (int) Math.round(canvasW * fit));
                int height = Math.max(1, (int) Math.round(canvasH * fit));

                BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = canvas.createGraphics();
                for (int i = 0; i < count; i++)
                {
                    int[] bounds = HologramGifs.frameInfo(reader.getImageMetadata(i));
                    BufferedImage part = reader.read(i);
                    BufferedImage before = bounds[5] == 3 ? deepCopy(canvas) : null;
                    g.drawImage(part, bounds[0], bounds[1], null);

                    out.add(new RawFrame(toNative(canvas, width, height),
                            Math.max(MIN_FRAME_MS, bounds[4] * 10)));   // gif delays are hundredths of a second

                    if (bounds[5] == 2)
                    {
                        // restore to background: clear just this frame's rectangle
                        java.awt.Composite old = g.getComposite();
                        g.setComposite(java.awt.AlphaComposite.Clear);
                        g.fillRect(bounds[0], bounds[1], reader.getWidth(i), reader.getHeight(i));
                        g.setComposite(old);
                    }
                    else if (before != null)
                    {
                        g.dispose();
                        canvas = before;
                        g = canvas.createGraphics();
                    }
                }
                g.dispose();
            }
            finally
            {
                reader.dispose();
            }
        }
        return out;
    }

    private static BufferedImage deepCopy(BufferedImage src)
    {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    /**
     * Downscale a frame and hand it over to Minecraft's own image type.
     *
     * <p>Note the channel order: a {@link NativeImage} pixel is ABGR, an AWT one is ARGB, so red and blue swap.
     * Getting that wrong does not fail, it just makes everybody blue.
     */
    private static NativeImage toNative(BufferedImage src, int width, int height)
    {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
            {
                int argb = scaled.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int gr = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (gr << 8) | r);
            }
        return image;
    }

    /** Forget everything, on disconnect, so the next server's folder starts clean. */
    public static synchronized void clear()
    {
        for (Animation animation : READY.values())
            animation.release();
        READY.clear();
        DECODING.clear();
        RAW.clear();
        chunks = null;
        received = 0;
        loaded = false;
        loadedVersion = 0;
    }

    /** Names the server has sent us, for a diagnostic. */
    public static synchronized Set<String> names()
    {
        return new HashSet<>(RAW.keySet());
    }
}
