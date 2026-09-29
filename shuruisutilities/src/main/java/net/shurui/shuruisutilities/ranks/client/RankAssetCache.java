package net.shurui.shuruisutilities.ranks.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Client-side reassembly of the chunked rank-asset zip streamed by the server ({@code PacketRankAssets}). Once every
 * chunk of a given {@code version} has arrived, the zip is expanded into {@link RankPackResources}.
 *
 * <p>To avoid a resource reload on every join, the assembled set is also written to a small on-disk cache keyed by
 * {@code version} and <b>preloaded at startup</b> ({@link #loadFromDisk}, called before the initial resource load) so
 * the {@code shuruisutilities:ranks} font stitches with the glyphs during normal startup, no runtime reload. A reload
 * ({@link Minecraft#reloadResourcePacks}) is then only triggered the rare time the server's version differs from the
 * cached one (i.e. an admin actually changed the rank set). The cache bytes are XOR-masked so the file isn't a
 * directly-openable zip on disk, matching the in-memory design's intent that the PNGs not be trivially lifted.</p>
 */
public final class RankAssetCache
{
    private RankAssetCache() {}

    private static int pendingVersion;
    private static byte[][] chunks;
    private static int received;
    private static int appliedVersion;
    private static boolean applied;

    /** Cache file: 4-byte big-endian version header followed by the XOR-masked zip bytes. */
    private static final byte XOR = (byte) 0x5A;

    private static Path cacheFile()
    {
        return FMLPaths.GAMEDIR.get().resolve(ShuruisUtilities.SU_DIRECTORY).resolve("rankcache").resolve("rank-assets.cache");
    }

    /**
     * Load the cached rank textures (if any) into {@link RankPackResources} <b>before</b> the initial resource load, so
     * the ranks font stitches with them at startup and no reload is needed. Marks the cached {@code version} as applied
     * so the server's matching resend on join is ignored. Safe no-op when there is no (or a corrupt) cache. Must run on
     * the client thread, before resources are first loaded (see {@link RankPackFinder}).
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
            int version = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16) | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            byte[] zipBytes = new byte[raw.length - 4];
            for (int i = 0; i < zipBytes.length; i++)
                zipBytes[i] = (byte) (raw[i + 4] ^ XOR);
            Map<String, byte[]> files = unzip(zipBytes);
            if (files.isEmpty())
                return;
            RankPackResources.replaceAll(files);
            appliedVersion = version;
            applied = true;
            LoggingHandler.sulog.info("[Ranks] Preloaded {} cached rank texture(s) (v{}) - no reload needed", files.size(),
                    version);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("Failed to preload cached rank assets; will fetch from server", e);
        }
    }

    /** Handle one chunk. Must run on the client thread (the packet handler enqueues it there). */
    public static synchronized void accept(int version, int index, int total, byte[] data)
    {
        if (applied && version == appliedVersion)
            return; // already have this version (from disk cache or an earlier resend); ignore
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
            LoggingHandler.sulog.warn("Failed to assemble rank-asset chunks", e);
        }
        chunks = null;
        apply(version, all.toByteArray());
    }

    /** A version arrived at runtime that differs from what's loaded: apply it, persist it, and reload once. */
    private static void apply(int version, byte[] zipBytes)
    {
        Map<String, byte[]> files = unzip(zipBytes);
        if (files.isEmpty())
            return;

        RankPackResources.replaceAll(files);
        appliedVersion = version;
        applied = true;
        writeCache(version, zipBytes);
        LoggingHandler.sulog.info("[Ranks] Loaded {} server-supplied rank texture(s) (v{}); reloading resources",
                files.size(), version);
        Minecraft.getInstance().reloadResourcePacks();
    }

    /** Unzip the streamed archive into a {@code path -> bytes} map. Returns empty on failure. */
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
            LoggingHandler.sulog.warn("Failed to unzip rank assets", e);
            return new HashMap<>();
        }
        return files;
    }

    /** Persist the version + XOR-masked zip so the next startup can preload it without a reload. Best-effort. */
    private static void writeCache(int version, byte[] zipBytes)
    {
        try
        {
            Path file = cacheFile();
            Files.createDirectories(file.getParent());
            byte[] raw = new byte[4 + zipBytes.length];
            raw[0] = (byte) (version >>> 24);
            raw[1] = (byte) (version >>> 16);
            raw[2] = (byte) (version >>> 8);
            raw[3] = (byte) version;
            for (int i = 0; i < zipBytes.length; i++)
                raw[i + 4] = (byte) (zipBytes[i] ^ XOR);
            Files.write(file, raw);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("Failed to write rank-asset cache (badges still work; join may reload next time)", e);
        }
    }
}
