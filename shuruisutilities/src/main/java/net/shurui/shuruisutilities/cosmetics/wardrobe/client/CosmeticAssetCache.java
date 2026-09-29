package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAssets;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Client side reassembly of the cosmetic art pack the Ragnarok Key streams ({@link PacketCosmeticAssets}, id 150).
 * Deliberately the same shape as {@code RgNpcAssetCache} (and {@code RankAssetCache} before it): a leading run of
 * chunks mirrored to a {@code .part} file so an interrupted transfer resumes, an XOR-masked finished copy keyed by the
 * pack's content-hash version, and a preload at {@code AddPackFindersEvent} ({@link #loadFromDisk}) so a returning
 * player bakes the cosmetics during normal startup with no runtime reload.
 *
 * <p>The version IS the content hash (the key derives it from a SHA-256 over every path and byte of the pack), so a
 * rejoin or a shard hop to a server holding the same pack reports {@link PacketCosmeticAssets#COMPLETE} and is sent
 * nothing; a reload happens only when the pack really changed.
 *
 * <h2>Before the pack arrives</h2>
 * Nothing crashes and nothing draws wrong: the worn layer and the wardrobe tiles skip a cosmetic whose item model is
 * still the missing model ({@link CosmeticArt}), pets and mounts skip rendering until their GeckoLib rig is baked,
 * and triggered animations fall back to particles, exactly as they already did for an absent rig.
 */
public final class CosmeticAssetCache
{
    private CosmeticAssetCache() {}

    private static int appliedVersion;
    private static boolean applied;

    /** The leading run of chunks of {@link #partialVersion} held so far, mirrored on disk by {@link #partFile()}. */
    private static int partialVersion;
    private static int partialTotal;
    private static int partialChunks;
    private static byte[] partial = new byte[0];

    /** Cache file: 4-byte big-endian version header followed by the XOR-masked zip bytes. */
    private static final byte XOR = (byte) 0x5A;

    private static Path dir()
    {
        return FMLPaths.GAMEDIR.get().resolve(ShuruisUtilities.SU_DIRECTORY).resolve("cosmeticcache");
    }

    private static Path cacheFile()
    {
        return dir().resolve("cosmetic-assets.cache");
    }

    /** Partial download: a 12-byte header (version, total chunks, chunks held) then the XOR-masked leading bytes. */
    private static Path partFile()
    {
        return dir().resolve("cosmetic-assets.part");
    }

    /** The version of the pack currently applied, or 0 when none is. */
    public static synchronized int appliedVersion()
    {
        return applied ? appliedVersion : 0;
    }

    /**
     * What to tell the server we already hold, as {@code {version, haveChunks}}: {@link PacketCosmeticAssets#COMPLETE}
     * for a whole applied pack, otherwise the leading run recovered from {@link #partFile()}. {@code {0, 0}} means
     * nothing at all.
     */
    public static synchronized int[] have()
    {
        if (applied)
            return new int[] {appliedVersion, PacketCosmeticAssets.COMPLETE};
        readPart();
        return new int[] {partialVersion, partialChunks};
    }

    /** Tell the server what we now hold. Sent on join and after every chunk: it is the whole of the flow control. */
    public static void acknowledge()
    {
        int[] h = have();
        net.shurui.shuruisutilities.commons.network.NetworkUtils.sendToServer(PacketCosmeticAssets.have(h[0], h[1]));
    }

    private static void readPart()
    {
        if (partialChunks > 0)
            return;
        Path file = partFile();
        if (!Files.isRegularFile(file))
            return;
        try
        {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length < 12)
                return;
            int v = readInt(raw, 0);
            int total = readInt(raw, 4);
            int held = readInt(raw, 8);
            if (total <= 0 || held <= 0 || held >= total)
                return;
            byte[] bytes = new byte[raw.length - 12];
            for (int i = 0; i < bytes.length; i++)
                bytes[i] = (byte) (raw[i + 12] ^ XOR);
            partialVersion = v;
            partialTotal = total;
            partialChunks = held;
            partial = bytes;
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.debug("[Cosmetics] Ignoring an unreadable partial art download: {}", e.toString());
        }
    }

    private static int readInt(byte[] b, int off)
    {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeInt(byte[] b, int off, int v)
    {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void writePart()
    {
        try
        {
            Path file = partFile();
            Files.createDirectories(file.getParent());
            byte[] raw = new byte[12 + partial.length];
            writeInt(raw, 0, partialVersion);
            writeInt(raw, 4, partialTotal);
            writeInt(raw, 8, partialChunks);
            for (int i = 0; i < partial.length; i++)
                raw[i + 12] = (byte) (partial[i] ^ XOR);
            Files.write(file, raw);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.debug("[Cosmetics] Could not record the partial art download: {}", e.toString());
        }
    }

    private static void discardPart()
    {
        partialVersion = 0;
        partialTotal = 0;
        partialChunks = 0;
        partial = new byte[0];
        try
        {
            Files.deleteIfExists(partFile());
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.debug("[Cosmetics] Could not remove the partial art download: {}", e.toString());
        }
    }

    /**
     * Load the cached art (if any) into {@link CosmeticPackResources} BEFORE the initial resource load, so item models,
     * GeckoLib rigs and sounds are there at startup and no reload is needed. Safe no-op without a cache. Called from
     * {@link CosmeticPackFinder}.
     */
    public static synchronized void loadFromDisk()
    {
        if (applied)
            return;
        Path file = cacheFile();
        if (!Files.isRegularFile(file))
            return;
        try
        {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length < 4)
                return;
            int version = readInt(raw, 0);
            byte[] zipBytes = new byte[raw.length - 4];
            for (int i = 0; i < zipBytes.length; i++)
                zipBytes[i] = (byte) (raw[i + 4] ^ XOR);
            Map<String, byte[]> files = unzip(zipBytes);
            if (files.isEmpty())
                return;
            CosmeticPackResources.replaceAll(files);
            appliedVersion = version;
            applied = true;
            LoggingHandler.sulog.info("[Cosmetics] Preloaded {} cached cosmetic art file(s) (v{}), no reload needed",
                    CosmeticPackResources.size(), version);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Failed to preload cached cosmetic art; will fetch from server", e);
        }
    }

    /**
     * Handle one chunk (client thread). Chunks are appended to a leading run, because the server sends them in order
     * one at a time and a prefix is the only shape that can be resumed from disk.
     */
    public static synchronized void accept(int version, int index, int total, byte[] data)
    {
        if (applied && version == appliedVersion)
            return;
        if (total <= 0 || data == null)
            return;
        if (version != partialVersion || total != partialTotal)
        {
            readPart();
            if (version != partialVersion || total != partialTotal)
            {
                discardPart();
                partialVersion = version;
                partialTotal = total;
            }
        }
        if (index != partialChunks)
            return;   // a duplicate, or a gap: the acknowledgement tells the server where to resume

        byte[] grown = new byte[partial.length + data.length];
        System.arraycopy(partial, 0, grown, 0, partial.length);
        System.arraycopy(data, 0, grown, partial.length, data.length);
        partial = grown;
        partialChunks++;

        if (partialChunks < total)
        {
            writePart();
            return;
        }
        byte[] whole = partial;
        discardPart();
        apply(version, whole);
    }

    /** A complete pack that differs from what is loaded: serve it, persist it, and reload resources once. */
    private static void apply(int version, byte[] zipBytes)
    {
        Map<String, byte[]> files = unzip(zipBytes);
        if (files.isEmpty())
            return;
        CosmeticPackResources.replaceAll(files);
        appliedVersion = version;
        applied = true;
        writeCache(version, zipBytes);
        LoggingHandler.sulog.info("[Cosmetics] Loaded {} server-supplied cosmetic art file(s) (v{}); reloading resources",
                CosmeticPackResources.size(), version);
        // Guarded so the decode path can also be exercised in a dedicated server process (the in-process round-trip
        // check), where the client classes may not be loaded.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient())
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null)
                mc.reloadResourcePacks();
        }
    }

    /** Unzip the streamed archive into a {@code path -> bytes} map. Empty on failure. */
    public static Map<String, byte[]> unzip(byte[] zipBytes)
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
            LoggingHandler.sulog.warn("[Cosmetics] Failed to unzip cosmetic art", e);
            return new HashMap<>();
        }
        return files;
    }

    private static void writeCache(int version, byte[] zipBytes)
    {
        try
        {
            Path file = cacheFile();
            Files.createDirectories(file.getParent());
            byte[] raw = new byte[4 + zipBytes.length];
            writeInt(raw, 0, version);
            for (int i = 0; i < zipBytes.length; i++)
                raw[i + 4] = (byte) (zipBytes[i] ^ XOR);
            Files.write(file, raw);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Failed to write the cosmetic art cache (the next join may reload)", e);
        }
    }
}
