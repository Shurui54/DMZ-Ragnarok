package net.shurui.shuruisutilities.ragnarok.client;

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
 * Client-side reassembly of the chunked rgnpc (ninjin) model pack streamed by the server
 * ({@code PacketRgNpcAssets}). Once every chunk of a given {@code version} has arrived, the zip is expanded into
 * {@link RgNpcPackResources}. Deliberately the same shape as the rank-badge cache next door, including the
 * XOR-masked on-disk copy, so the two behave identically and neither can drift into a different failure mode.
 *
 * <p>The disk cache earns its keep more here than it does for the badges: this set is megabytes rather than
 * kilobytes, and preloading it at startup ({@link #loadFromDisk}, called before the initial resource load) means
 * GeckoLib bakes the models during normal startup with no runtime reload. A reload
 * ({@link Minecraft#reloadResourcePacks}) then only happens the rare time the server's version differs from the
 * cached one, meaning an admin actually changed the model pack.</p>
 */
public final class RgNpcAssetCache
{
    private RgNpcAssetCache() {}

    private static int appliedVersion;
    private static boolean applied;

    /** The leading run of chunks of {@link #partialVersion} held so far, mirrored on disk by {@link #partFile()}. */
    private static int partialVersion;
    private static int partialTotal;
    private static int partialChunks;
    private static byte[] partial = new byte[0];

    /** Cache file: 4-byte big-endian version header followed by the XOR-masked zip bytes. */
    private static final byte XOR = (byte) 0x5A;

    private static Path cacheFile()
    {
        return FMLPaths.GAMEDIR.get().resolve(ShuruisUtilities.SU_DIRECTORY).resolve("rgnpccache")
                .resolve("rgnpc-assets.cache");
    }

    /**
     * Partial download: a 12-byte header (version, total chunks, chunks held) then the XOR-masked leading bytes.
     *
     * <p>Kept beside the finished cache rather than inside it so a half transfer can never be mistaken for a
     * usable pack. Its whole reason for existing is that this set is large enough that a slow connection can be
     * dropped for a keepalive timeout part way through: without somewhere to put the part that did arrive, every
     * reconnect starts from the first byte and such a player never finishes at all.
     */
    private static Path partFile()
    {
        return FMLPaths.GAMEDIR.get().resolve(ShuruisUtilities.SU_DIRECTORY).resolve("rgnpccache")
                .resolve("rgnpc-assets.part");
    }

    /**
     * What to tell the server we already hold, as {@code {version, haveChunks}}.
     *
     * <p>{@code haveChunks} is {@link net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssetsHave#COMPLETE} for a
     * whole applied pack, otherwise the length of the leading run recovered from {@link #partFile()}. Version 0
     * with 0 chunks means "nothing at all", which is what a first-time client reports.
     */
    public static synchronized int[] have()
    {
        if (applied)
            return new int[] {appliedVersion, -1};
        readPart();
        return new int[] {partialVersion, partialChunks};
    }

    /** Recover the partial download from disk into the fields above. Best effort: a bad file simply means none. */
    private static void readPart()
    {
        if (partialChunks > 0)
            return;   // already in hand this session
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
            LoggingHandler.sulog.debug("[rgnpc] Ignoring an unreadable partial download: {}", e.toString());
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

    /** Mirror the leading run to disk so the next connection can resume from it. Best effort. */
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
            LoggingHandler.sulog.debug("[rgnpc] Could not record the partial download: {}", e.toString());
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
            LoggingHandler.sulog.debug("[rgnpc] Could not remove the partial download: {}", e.toString());
        }
    }

    /**
     * Load the cached models (if any) into {@link RgNpcPackResources} <b>before</b> the initial resource load, so
     * GeckoLib bakes them at startup and no reload is needed. Marks the cached {@code version} as applied so the
     * server's matching resend on join is ignored. Safe no-op when there is no (or a corrupt) cache. Must run on
     * the client thread, before resources are first loaded (see {@link RgNpcPackFinder}).
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
            RgNpcPackResources.replaceAll(files);
            appliedVersion = version;
            applied = true;
            LoggingHandler.sulog.info("[rgnpc] Preloaded {} cached model file(s) (v{}) - no reload needed",
                    RgNpcPackResources.size(), version);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("Failed to preload cached rgnpc models; will fetch from server", e);
        }
    }

    /**
     * Tell the server what we now hold. Sent after every chunk, whatever became of it, because the server sends the
     * next chunk only when it hears this: that acknowledgement is the whole of the flow control. A chunk we could not
     * use (a duplicate, a gap, a different pack) still gets an answer, which is how the server learns where to resume.
     */
    public static void acknowledge()
    {
        int[] have = have();
        net.shurui.shuruisutilities.commons.network.NetworkUtils.sendToServer(
                new net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssetsHave(have[0], have[1]));
    }

    /**
     * Handle one chunk. Must run on the client thread (the packet handler enqueues it there).
     *
     * <p>Chunks are appended to a leading run rather than dropped into a sparse array, because that is what the
     * wire actually delivers: the server queues indices in order and TCP keeps them in order. Holding a prefix is
     * also the only shape that can be written to disk and resumed, which is the point of doing it this way.
     */
    public static synchronized void accept(int version, int index, int total, byte[] data)
    {
        if (applied && version == appliedVersion)
            return; // already have this version (from disk cache or an earlier resend); ignore
        if (total <= 0)
            return;
        if (version != partialVersion || total != partialTotal)
        {
            readPart();
            if (version != partialVersion || total != partialTotal)
            {
                // A different pack than the one we were part way through: that progress is worthless now.
                discardPart();
                partialVersion = version;
                partialTotal = total;
            }
        }
        if (index != partialChunks)
            return;   // a duplicate, or a gap we cannot bridge: the next resume will ask from the right place

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

    /** A version arrived at runtime that differs from what's loaded: apply it, persist it, and reload once. */
    private static void apply(int version, byte[] zipBytes)
    {
        Map<String, byte[]> files = unzip(zipBytes);
        if (files.isEmpty())
            return;

        RgNpcPackResources.replaceAll(files);
        // DragonMineZ memoises "does this race geo exist" per location and never clears it, so a swap that adds or
        // drops a model would otherwise still be judged against the pack before it. The reload below clears it as
        // well (sdu clears both DMZ caches from its client reload listener), but that path is reflective and goes
        // quiet if DMZ ever renames a field, and a stale "it exists" is a render-thread crash, not a cosmetic bug.
        net.shurui.dev.sdu.compat.dmz.DmzModelCache.clear();
        appliedVersion = version;
        applied = true;
        writeCache(version, zipBytes);
        LoggingHandler.sulog.info("[rgnpc] Loaded {} server-supplied model file(s) (v{}); reloading resources",
                RgNpcPackResources.size(), version);
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
            LoggingHandler.sulog.warn("Failed to unzip rgnpc models", e);
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
            LoggingHandler.sulog.warn("Failed to write rgnpc model cache (models still work; join may reload next time)",
                    e);
        }
    }
}
