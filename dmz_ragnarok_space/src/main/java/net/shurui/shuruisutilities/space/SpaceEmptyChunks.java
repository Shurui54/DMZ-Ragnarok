package net.shurui.shuruisutilities.space;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Keeps the space dimension from filling the disk with chunks of pure air.
 *
 * <p>{@link #isEmpty(ChunkAccess)} is the rule {@code MixinSpaceEmptyChunkSave} uses to skip saving a live chunk. The
 * startup pass below applies the SAME rule to region files already on disk: before the space level opens it rewrites
 * each {@code space/region/*.mca} without its all-air chunks (copying every kept chunk's bytes unchanged) and deletes a
 * file left with none. An all-air chunk regenerates identically, so this is lossless; anything holding a block, a block
 * entity or a structure start is kept, as is anything this code cannot read (oversized external chunks, odd
 * compression). Only the space dimension's {@code region} folder is touched, never {@code entities} or {@code poi}, so
 * pods parked in space are unaffected.
 *
 * <p>It runs once per world (marker file {@code dmzr_empty_pruned_v1} in the space folder); after that the save mixin
 * stops new empty chunks reaching disk. Delete the marker to run it again. Each file is finished before the next is
 * read (a few MB per thread), and each rewrite goes through a temp file and an atomic move, so a failure leaves that
 * file exactly as it was.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SpaceEmptyChunks
{
    private static final String MARKER = "dmzr_empty_pruned_v1";
    private static final int SECTOR = 4096;

    private SpaceEmptyChunks()
    {
    }

    /** True when the chunk holds no block (air of any kind only), no block entity and no structure start. */
    public static boolean isEmpty(ChunkAccess chunk)
    {
        if (!chunk.getBlockEntitiesPos().isEmpty() || !chunk.getAllStarts().isEmpty())
        {
            return false;
        }
        for (LevelChunkSection section : chunk.getSections())
        {
            if (section != null && !section.hasOnlyAir())
            {
                return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event)
    {
        try
        {
            Path root = event.getServer().getWorldPath(LevelResource.ROOT);
            Path spaceDir = DimensionType.getStorageFolder(SpaceKeys.SPACE, root);
            Path regionDir = spaceDir.resolve("region");
            if (!Files.isDirectory(regionDir) || Files.exists(spaceDir.resolve(MARKER)))
            {
                return;
            }
            prune(regionDir);
            Files.writeString(spaceDir.resolve(MARKER), "empty space chunks pruned\n");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[SpaceEmptyChunks] Pruning empty space chunks failed; the world is unchanged "
                    + "where it stopped and it will retry next start.", t);
        }
    }

    private static void prune(Path regionDir) throws Exception
    {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "r.*.mca"))
        {
            for (Path p : ds)
            {
                files.add(p);
            }
        }
        if (files.isEmpty())
        {
            return;
        }
        long before = 0;
        for (Path p : files)
        {
            before += Files.size(p);
        }
        LoggingHandler.sulog.info("[SpaceEmptyChunks] Removing all-air chunks from {} space region file(s) ({} MB). "
                + "One-time; this can take a few minutes on a big world.", files.size(), before >> 20);

        AtomicInteger done = new AtomicInteger();
        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger rewritten = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong chunksDropped = new AtomicLong();
        AtomicBoolean abort = new AtomicBoolean();
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "dmzr-space-prune");
            t.setDaemon(true);
            return t;
        });
        // ONE pass, each file finished (deleted, shrunk or left) before its bytes are dropped, so memory stays at about
        // one region file per thread. The first version kept every file's bytes for a second pass and ran SMP out of a
        // 27 GB heap at ~10,000 of 25,314 files (2026-10-01). Any Error (out of memory included) stops every task, so
        // nothing keeps touching region files once the server goes on to open them.
        try
        {
            List<Future<?>> tasks = new ArrayList<>();
            for (Path p : files)
            {
                tasks.add(pool.submit(() -> {
                    if (abort.get())
                    {
                        return;
                    }
                    try
                    {
                        Plan plan = plan(p);
                        if (plan != null && plan.dropped > 0)
                        {
                            if (plan.keep == 0)
                            {
                                Files.deleteIfExists(p);
                                deleted.incrementAndGet();
                            }
                            else
                            {
                                rewrite(plan);
                                rewritten.incrementAndGet();
                            }
                            chunksDropped.addAndGet(plan.dropped);
                        }
                    }
                    catch (Exception e)
                    {
                        failed.incrementAndGet();
                    }
                    catch (Throwable t)
                    {
                        abort.set(true);
                        failed.incrementAndGet();
                    }
                    int n = done.incrementAndGet();
                    if (n % 2000 == 0)
                    {
                        LoggingHandler.sulog.info("[SpaceEmptyChunks] processed {}/{}", n, files.size());
                    }
                }));
            }
            for (Future<?> f : tasks)
            {
                f.get();
            }
        }
        finally
        {
            abort.set(true);
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        }
        long after = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "r.*.mca"))
        {
            for (Path p : ds)
            {
                after += Files.size(p);
            }
        }
        LoggingHandler.sulog.info("[SpaceEmptyChunks] Done: dropped {} all-air chunk(s), deleted {} empty file(s), "
                        + "rewrote {}, {} failed (left untouched). Space region data {} MB -> {} MB.",
                chunksDropped.get(), deleted.get(), rewritten.get(), failed.get(), before >> 20, after >> 20);
        if (failed.get() > 0)
        {
            throw new IOException(failed.get() + " region file(s) could not be pruned");
        }
    }

    /** What to keep from one region file: the raw byte ranges of kept chunks, by header index. */
    private static final class Plan
    {
        Path file;
        byte[] data;
        boolean[] keepIdx = new boolean[1024];
        int keep;
        int dropped;
    }

    private static Plan plan(Path file) throws IOException
    {
        byte[] data = Files.readAllBytes(file);
        if (data.length < 2 * SECTOR)
        {
            return null;
        }
        Plan plan = new Plan();
        plan.file = file;
        plan.data = data;
        for (int i = 0; i < 1024; i++)
        {
            int loc = ((data[i * 4] & 0xFF) << 16) | ((data[i * 4 + 1] & 0xFF) << 8) | (data[i * 4 + 2] & 0xFF);
            if (loc == 0)
            {
                continue;
            }
            if (chunkIsEmpty(data, loc))
            {
                plan.dropped++;
            }
            else
            {
                plan.keepIdx[i] = true;
                plan.keep++;
            }
        }
        return plan;
    }

    // conservative: anything unreadable or unusual counts as NOT empty, so it is kept.
    private static boolean chunkIsEmpty(byte[] data, int sectorOffset)
    {
        try
        {
            int off = sectorOffset * SECTOR;
            if (off + 5 > data.length)
            {
                return false;
            }
            int len = ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16) | ((data[off + 2] & 0xFF) << 8)
                    | (data[off + 3] & 0xFF);
            int type = data[off + 4];
            if (len <= 1 || off + 4 + len > data.length || (type & 0x80) != 0)
            {
                return false;
            }
            InputStream raw = new ByteArrayInputStream(data, off + 5, len - 1);
            InputStream in;
            if (type == 2)
            {
                in = new InflaterInputStream(raw);
            }
            else if (type == 1)
            {
                in = new GZIPInputStream(raw);
            }
            else if (type == 3)
            {
                in = raw;
            }
            else
            {
                return false;
            }
            CompoundTag tag;
            try (DataInputStream dis = new DataInputStream(in))
            {
                tag = NbtIo.read(dis);
            }
            if (!tag.getList("block_entities", Tag.TAG_COMPOUND).isEmpty())
            {
                return false;
            }
            if (tag.contains("structures", Tag.TAG_COMPOUND)
                    && !tag.getCompound("structures").getCompound("starts").isEmpty())
            {
                return false;
            }
            ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
            for (int s = 0; s < sections.size(); s++)
            {
                CompoundTag sec = sections.getCompound(s);
                if (!sec.contains("block_states", Tag.TAG_COMPOUND))
                {
                    continue;
                }
                ListTag palette = sec.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
                for (int p = 0; p < palette.size(); p++)
                {
                    String name = palette.getCompound(p).getString("Name");
                    if (!name.equals("minecraft:air") && !name.equals("minecraft:void_air")
                            && !name.equals("minecraft:cave_air"))
                    {
                        return false;
                    }
                }
            }
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // write a new region file holding only the kept chunks (bytes copied unchanged, timestamps kept), then swap it in.
    private static void rewrite(Plan plan) throws IOException
    {
        Path tmp = plan.file.resolveSibling(plan.file.getFileName() + ".prune.tmp");
        try
        {
            writeKept(plan, tmp);
            Files.move(tmp, plan.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        finally
        {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeKept(Plan plan, Path tmp) throws IOException
    {
        byte[] data = plan.data;
        try (RandomAccessFile out = new RandomAccessFile(tmp.toFile(), "rw"))
        {
            out.setLength(0);
            byte[] header = new byte[2 * SECTOR];
            // keep the timestamp table as is
            System.arraycopy(data, SECTOR, header, SECTOR, SECTOR);
            out.write(header);
            int nextSector = 2;
            for (int i = 0; i < 1024; i++)
            {
                if (!plan.keepIdx[i])
                {
                    continue;
                }
                int loc = ((data[i * 4] & 0xFF) << 16) | ((data[i * 4 + 1] & 0xFF) << 8) | (data[i * 4 + 2] & 0xFF);
                int count = data[i * 4 + 3] & 0xFF;
                int off = loc * SECTOR;
                int len = Math.min(count * SECTOR, data.length - off);
                out.seek((long) nextSector * SECTOR);
                out.write(data, off, len);
                int pad = count * SECTOR - len;
                if (pad > 0)
                {
                    out.write(new byte[pad]);
                }
                out.seek(i * 4L);
                out.write((nextSector >> 16) & 0xFF);
                out.write((nextSector >> 8) & 0xFF);
                out.write(nextSector & 0xFF);
                out.write(count);
                nextSector += count;
            }
        }
    }
}
