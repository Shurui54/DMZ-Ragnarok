package net.shurui.shuruisutilities.commands.world;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.shurui.shuruisutilities.core.misc.TaskRegistry;
import net.shurui.shuruisutilities.core.misc.TaskRegistry.TickTask;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/**
 * The resumable worker behind {@code /updateblocks area}: a whole-chunk sweep that reconnects blocks which were never
 * placed by the game at all.
 *
 * <p><b>Why this exists separately from {@link TickTaskBlockUpdater}.</b> That one sweeps a cube around the player and
 * only ever touches chunks that are ALREADY loaded, which is right for repairing a paste you are standing in. It is
 * the wrong shape for the three builds that actually need this. Planet Vegeta and Beerus ship as authored {@code .mca}
 * region files copied into the save by {@code PlanetRegionSeeder}, and the overworld is the converted 1.7.10 map. In
 * every one of those cases the blocks were WRITTEN INTO REGION FILES, never placed through {@code Level.setBlock}, so
 * no shape update and no neighbour update has ever run on them. Fences, panes, walls, stairs, redstone and leaves come
 * up in whatever state the author or the converter happened to write, and nothing in the game will ever fix them.
 *
 * <p><b>How it stays affordable.</b> Two things, both necessary:
 * <ul>
 *   <li><b>Palette skipping.</b> A chunk section is 4096 positions but its palette is usually a handful of entries. If
 *       nothing in the palette can connect to anything ({@link ShapeSensitiveBlocks}) the whole section is skipped
 *       without a single position being read. Built areas are a thin band inside a large solid volume, so the great
 *       majority of sections are rejected this way.</li>
 *   <li><b>Existence before loading.</b> A chunk is only loaded if its region file already holds it, decided by reading
 *       the region's 4 KiB location table (cached per region file, so one read serves 1024 chunks). This is what keeps
 *       a large radius from quietly generating a new world at the edges: an absent chunk is counted and stepped over,
 *       never asked for.</li>
 * </ul>
 *
 * <p>Chunks that WERE on disk are loaded normally and left to the chunk manager to unload again, so a long run walks
 * through the world rather than accumulating it. The per-block work is deliberately identical to
 * {@link TickTaskBlockUpdater}: recompute from the six neighbours, write back only on a real change, then propagate.
 */
public class TickTaskAreaBlockUpdater implements TickTask
{
    private static final ConcurrentHashMap<UUID, TickTaskAreaBlockUpdater> ACTIVE = new ConcurrentHashMap<>();

    // report progress roughly every this many ticks (200 ticks ~ 10s). A whole-map run is long enough that silence
    // would read as a hang.
    private static final int PROGRESS_INTERVAL_TICKS = 200;

    // Ceiling on chunks pulled off disk in a single tick, independent of the position budget. The position budget
    // alone does not bound this: a run over empty or all-skipped sections would otherwise burn through hundreds of
    // chunk loads in one tick without ever spending its position budget, and chunk loading is the expensive half.
    private static final int CHUNK_LOADS_PER_TICK = 4;

    // SRG name of MinecraftServer.storageSource, the same field PlanetRegionSeeder and ChunkPurgeJob reflect to reach
    // the save's per-dimension folders.
    private static final String SRG_STORAGE_SOURCE = "f_129744_";

    private static final int REGION_HEADER_BYTES = 4096;
    private static final int CHUNKS_PER_REGION_AXIS = 32;

    // dimensions with a headless (server-run) sweep in flight, so a restart loop cannot stack one sweep per boot on
    // top of the last. Keyed by dimension id because a headless run has no player to key on.
    private static final java.util.Set<String> HEADLESS_ACTIVE = ConcurrentHashMap.newKeySet();

    /** Where a run's progress and summary go. Chat for an operator, the log for a sweep the server started itself. */
    public interface Reporter
    {
        void line(String message);
    }

    // null for a headless run. Only a player-owned run occupies the per-player slot and answers to /updateblocks cancel.
    private final ServerPlayer player;
    private final UUID playerId;
    private final ServerLevel level;
    private final File regionDir;
    private final Reporter reporter;
    private final String label;

    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final long totalChunks;
    private final long startMillis;

    // sweep cursor over the chunk rectangle, x outer and z inner.
    private int curChunkX;
    private int curChunkZ;

    // presence tables keyed by region coordinate, each a 1024-entry mirror of that region file's location table. Held
    // for the life of the run: a rectangle revisits the same region file for up to 1024 chunks, and the table is 1 KiB
    // of booleans against a 4 KiB read.
    private final Map<Long, boolean[]> regionPresence = new HashMap<>();

    // tallies
    private long chunksVisited;
    private long chunksAbsent;
    private long sectionsScanned;
    private long sectionsSkipped;
    private long visited;
    private long changed;

    private int ticksRun;
    private volatile boolean cancelled;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private TickTaskAreaBlockUpdater(ServerPlayer player, ServerLevel level, File regionDir, Reporter reporter,
            String label, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)
    {
        this.player = player;
        this.playerId = player == null ? null : player.getUUID();
        this.level = level;
        this.regionDir = regionDir;
        this.reporter = reporter;
        this.label = label;

        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        this.totalChunks = (long) (maxChunkX - minChunkX + 1) * (long) (maxChunkZ - minChunkZ + 1);
        this.startMillis = System.currentTimeMillis();

        this.curChunkX = minChunkX;
        this.curChunkZ = minChunkZ;
    }

    /**
     * Begin a run for this player, or report why it cannot start.
     *
     * @return null on success, or a message explaining the refusal
     */
    public static String start(ServerPlayer player, int chunkRadius)
    {
        ServerLevel level = player.serverLevel();
        File regionDir = resolveRegionDir(level);
        if (regionDir == null || !regionDir.isDirectory())
            return "This dimension has no region data on disk yet, so there is nothing to sweep.";

        // flush everything held in memory so the location tables we are about to read describe the same world the
        // server is running. Without this a chunk created this session could read as absent and be stepped over.
        level.save(null, true, false);

        int centreX = player.blockPosition().getX() >> 4;
        int centreZ = player.blockPosition().getZ() >> 4;
        TickTaskAreaBlockUpdater task = new TickTaskAreaBlockUpdater(player, level, regionDir,
                message -> ChatOutputHandler.chatConfirmation(player.createCommandSourceStack(), message),
                level.dimension().location().toString(),
                centreX - chunkRadius, centreZ - chunkRadius, centreX + chunkRadius, centreZ + chunkRadius);
        ACTIVE.put(task.playerId, task);
        task.report(String.format("Sweeping block updates across %d chunk(s), radius %d, in %s. This runs in the "
                        + "background; use /updateblocks cancel to stop it.",
                task.totalChunks, chunkRadius, level.dimension().location()));
        TaskRegistry.schedule(task);
        return null;
    }

    /**
     * Begin a sweep the SERVER asked for rather than an operator, over an explicit chunk rectangle, reporting to the
     * log. This is what repairs an authored planet the moment its region files are first seeded into a save: the
     * blocks arrive as raw region data, so this is the only thing that will ever connect them.
     *
     * <p>At most one headless sweep per dimension is allowed to be in flight. A server that is restarted while one is
     * running simply starts it again next boot, which is safe because recomputing an already correct block changes
     * nothing.
     */
    public static void startHeadless(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            String label)
    {
        String dimKey = level.dimension().location().toString();
        if (!HEADLESS_ACTIVE.add(dimKey))
            return;
        File regionDir = resolveRegionDir(level);
        if (regionDir == null || !regionDir.isDirectory())
        {
            HEADLESS_ACTIVE.remove(dimKey);
            return;
        }
        TickTaskAreaBlockUpdater task = new TickTaskAreaBlockUpdater(null, level, regionDir,
                message -> LoggingHandler.sulog.info("[updateblocks] {}", message), label,
                minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        task.report(String.format("%s: sweeping block updates across %d freshly seeded chunk(s) in %s.",
                label, task.totalChunks, dimKey));
        TaskRegistry.schedule(task);
    }

    public static boolean isRunning(UUID id)
    {
        return ACTIVE.containsKey(id);
    }

    /** Stop this player's run if one exists. Clears the slot immediately so a fresh run can start right away. */
    public static boolean cancel(UUID id)
    {
        TickTaskAreaBlockUpdater task = ACTIVE.get(id);
        if (task == null)
            return false;
        task.cancelled = true;
        ACTIVE.remove(id, task);
        return true;
    }

    // the save folder holding this dimension's region files, or null if it cannot be resolved.
    private static File resolveRegionDir(ServerLevel level)
    {
        try
        {
            MinecraftServer server = level.getServer();
            LevelStorageSource.LevelStorageAccess storage =
                    ObfuscationReflectionHelper.getPrivateValue(MinecraftServer.class, server, SRG_STORAGE_SOURCE);
            if (storage == null)
                return null;
            return new File(storage.getDimensionPath(level.dimension()).toFile(), "region");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[updateblocks] could not resolve the region folder: " + t);
            return null;
        }
    }

    @Override
    public boolean tick()
    {
        ticksRun++;
        if (cancelled)
        {
            report(String.format("Area block update cancelled after %d chunk(s), %d block(s) changed.",
                    chunksVisited, changed));
            finish();
            return true;
        }

        int budget = UpdateBlocksSettings.blocksPerTick();
        int worked = 0;
        int loads = 0;
        while (worked < budget && loads < CHUNK_LOADS_PER_TICK)
        {
            if (curChunkX > maxChunkX)
            {
                reportSummary();
                finish();
                return true;
            }

            int cx = curChunkX;
            int cz = curChunkZ;
            advanceCursor();

            if (!chunkExistsOnDisk(cx, cz))
            {
                chunksAbsent++;
                continue;
            }
            loads++;
            worked += processChunk(cx, cz);
        }

        if (ticksRun % PROGRESS_INTERVAL_TICKS == 0)
        {
            long seen = chunksVisited + chunksAbsent;
            long pct = totalChunks == 0 ? 100 : (seen * 100L) / totalChunks;
            report(String.format("Area block update %d%% done (%d block(s) changed so far).", pct, changed));
        }
        return false;
    }

    private void advanceCursor()
    {
        curChunkZ++;
        if (curChunkZ > maxChunkZ)
        {
            curChunkZ = minChunkZ;
            curChunkX++;
        }
    }

    /**
     * Walk one chunk's sections, skipping any whose palette cannot connect to anything.
     *
     * @return roughly how many positions were spent, so the caller's per-tick budget stays meaningful
     */
    private int processChunk(int cx, int cz)
    {
        LevelChunk chunk;
        try
        {
            chunk = level.getChunk(cx, cz);
        }
        catch (Throwable t)
        {
            // a chunk that fails to load is not worth ending the run over: count it and move on.
            LoggingHandler.sulog.warn("[updateblocks] skipping chunk " + cx + "," + cz + ": " + t);
            return 1;
        }
        chunksVisited++;

        int spent = 0;
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        for (int i = 0; i < sections.length; i++)
        {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir())
            {
                sectionsSkipped++;
                continue;
            }
            // the whole point: reject on the palette, before touching any of the 4096 positions behind it.
            if (!section.maybeHas(ShapeSensitiveBlocks::isSensitive))
            {
                sectionsSkipped++;
                continue;
            }
            sectionsScanned++;
            int baseY = (minSection + i) << 4;
            int baseX = cx << 4;
            int baseZ = cz << 4;
            for (int y = 0; y < 16; y++)
            {
                for (int x = 0; x < 16; x++)
                {
                    for (int z = 0; z < 16; z++)
                    {
                        processBlock(baseX + x, baseY + y, baseZ + z);
                    }
                }
            }
            spent += 4096;
        }
        // a chunk whose sections were all skipped still cost a load, so charge something for it or an all-skipped
        // region would spin the budget without ever yielding.
        return Math.max(spent, 64);
    }

    // Recompute one block's shape from its neighbours, then propagate. The recompute is deliberately identical to
    // TickTaskBlockUpdater.processBlock so the two modes cannot drift into disagreeing about what an update means.
    //
    // ONE DELIBERATE DIFFERENCE: an UNCHANGED block is not resent to clients. The cube sweep resends because it covers
    // a radius someone is standing in and the resync is cheap there. Here the sweep can cover a whole map, and every
    // client already holds the correct state for a block that did not change (it came off disk through the normal
    // chunk path), so resending would be a packet storm that buys nothing. Changed blocks still go out: setBlock with
    // UPDATE_ALL carries the client flag.
    private void processBlock(int x, int y, int z)
    {
        cursor.set(x, y, z);
        BlockState state = level.getBlockState(cursor);
        if (state.isAir())
            return;
        visited++;
        BlockPos pos = cursor.immutable();

        BlockState newState = state;
        boolean allNeighborsLoaded = true;
        for (Direction dir : Direction.values())
        {
            BlockPos neighbor = pos.relative(dir);
            // a neighbour in a different, unloaded chunk is left out: reading it would force-load or generate it, and
            // at the edge of the swept rectangle that is exactly the world-growth this task is built to avoid.
            if (((neighbor.getX() >> 4) != (pos.getX() >> 4) || (neighbor.getZ() >> 4) != (pos.getZ() >> 4))
                    && !level.hasChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4))
            {
                allNeighborsLoaded = false;
                continue;
            }
            newState = newState.updateShape(dir, level.getBlockState(neighbor), level, pos, neighbor);
        }

        if (newState != state)
        {
            level.setBlock(pos, newState, Block.UPDATE_ALL);
            changed++;
        }
        if (allNeighborsLoaded)
            level.updateNeighborsAt(pos, newState.getBlock());
    }

    /**
     * Whether this chunk is already present in its region file.
     *
     * <p>Read from the region's location table rather than by asking the level, because asking the level is what
     * CREATES the chunk. A region file that does not exist, or cannot be read, reports every chunk absent: refusing to
     * touch what we cannot verify is the safe direction, since the cost is an unrepaired chunk rather than a generated
     * one.
     */
    private boolean chunkExistsOnDisk(int cx, int cz)
    {
        int rx = cx >> 5;
        int rz = cz >> 5;
        long key = (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL);
        boolean[] table = regionPresence.get(key);
        if (table == null)
        {
            table = readRegionPresence(rx, rz);
            regionPresence.put(key, table);
        }
        int local = (cx & 31) + (cz & 31) * CHUNKS_PER_REGION_AXIS;
        return table[local];
    }

    // parse one region file's 4 KiB location table into a presence flag per chunk. A zero entry means the chunk has
    // never been written.
    private boolean[] readRegionPresence(int rx, int rz)
    {
        boolean[] table = new boolean[CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS];
        File file = new File(regionDir, "r." + rx + "." + rz + ".mca");
        if (!file.isFile() || file.length() < REGION_HEADER_BYTES)
            return table;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r"))
        {
            byte[] header = new byte[REGION_HEADER_BYTES];
            raf.readFully(header);
            for (int i = 0; i < table.length; i++)
            {
                int off = i * 4;
                // three offset bytes plus a sector count; any non-zero pair means a chunk is stored there.
                int sectorOffset = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
                int sectorCount = header[off + 3] & 0xFF;
                table[i] = sectorOffset != 0 && sectorCount != 0;
            }
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("[updateblocks] could not read region r." + rx + "." + rz + ".mca: " + e);
        }
        return table;
    }

    private void reportSummary()
    {
        double seconds = (System.currentTimeMillis() - startMillis) / 1000.0;
        report(String.format(label + ": area block update complete: %d chunk(s) swept (%d never generated), %d section(s) "
                        + "scanned and %d skipped on palette, %d block(s) examined, %d changed, %.1fs.",
                chunksVisited, chunksAbsent, sectionsScanned, sectionsSkipped, visited, changed, seconds));
    }

    private void finish()
    {
        if (playerId != null)
            ACTIVE.remove(playerId, this);
        HEADLESS_ACTIVE.remove(level.dimension().location().toString());
        regionPresence.clear();
    }

    // a player who logged out mid-run is not worth reporting to, but the run itself continues: the repair is to the
    // world, not to them.
    private void report(String message)
    {
        if (player != null && player.hasDisconnected())
            return;
        try
        {
            reporter.line(message);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[updateblocks] could not report progress: " + t);
        }
    }

    @Override
    public boolean editsBlocks()
    {
        return true;
    }
}
