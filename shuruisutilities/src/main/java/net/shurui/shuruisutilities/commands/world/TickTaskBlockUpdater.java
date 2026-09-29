package net.shurui.shuruisutilities.commands.world;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.shurui.shuruisutilities.core.misc.TaskRegistry;
import net.shurui.shuruisutilities.core.misc.TaskRegistry.TickTask;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The resumable worker behind /updateblocks. Walks a cube of the given radius around the player and, per non-air
 * block, recomputes its shape from every loaded neighbour (updateShape), then fires a neighbour update + client
 * resync, exactly like the old synchronous command did. The one change is scheduling: instead of doing the whole
 * cube in a single server tick, it processes a fixed budget of positions per tick (see {@link UpdateBlocksSettings})
 * and yields, so the per-tick cost is flat regardless of radius.
 *
 * <p>Safety. It never force-loads or generates a chunk. A position whose own chunk is not already loaded is skipped
 * and counted (never read, so worldgen is never triggered). Within a block's own loaded chunk, a neighbour read for
 * updateShape and the follow-up neighbour propagation are guarded the same way: a neighbour in an unloaded chunk is
 * left out, so nothing at the loaded/unloaded boundary can pull an ungenerated chunk into existence. In practice
 * every interior block has all six neighbours loaded, so its handling is identical to the old command.
 *
 * <p>One run per player at a time, tracked in {@link #ACTIVE}; a second attempt is rejected until the first finishes
 * or is cancelled with {@code /updateblocks cancel}. Being a block task, it counts against the registry's
 * {@code MAX_BLOCK_TASKS} throttle.
 */
public class TickTaskBlockUpdater implements TickTask
{
    private static final ConcurrentHashMap<UUID, TickTaskBlockUpdater> ACTIVE = new ConcurrentHashMap<>();

    // report progress roughly every this many ticks while a long run is going (200 ticks ~ 10s).
    private static final int PROGRESS_INTERVAL_TICKS = 200;

    private final ServerPlayer player;
    private final UUID playerId;
    private final ServerLevel level;
    private final BlockPos center;
    private final int radius;
    private final int minY;
    private final int maxY;
    private final long totalPositions;
    private final long startMillis;

    // sweep cursor: dx/dz are the column offsets from the centre (dx outer, dz middle, y inner, matching the old
    // loop order); curY walks the current column bottom to top.
    private int dx;
    private int dz;
    private int curY;
    private boolean columnReady;
    private int colX;
    private int colZ;
    private boolean columnLoaded;

    // tallies
    private long examined;      // positions stepped over, loaded or not
    private long visited;       // non-air blocks actually processed
    private long changed;       // blocks whose shape changed and were re-set
    private long skippedUnloaded;

    private int ticksRun;
    private volatile boolean cancelled;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private TickTaskBlockUpdater(ServerPlayer player, int radius)
    {
        this.player = player;
        this.playerId = player.getUUID();
        this.level = player.serverLevel();
        this.center = player.blockPosition();
        this.radius = radius;
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight() - 1;
        long span = (long) (maxY - minY + 1);
        long width = (long) (2 * radius + 1);
        this.totalPositions = width * width * span;
        this.startMillis = System.currentTimeMillis();

        this.dx = -radius;
        this.dz = -radius;
        this.columnReady = false;
    }

    /** Begin a run for this player. Assumes {@link #isRunning} was already checked by the caller. */
    public static void start(ServerPlayer player, int radius)
    {
        TickTaskBlockUpdater task = new TickTaskBlockUpdater(player, radius);
        ACTIVE.put(task.playerId, task);
        task.msgConfirm(String.format("Updating blocks within radius %d (up to %d positions). This runs in the "
                + "background; use /updateblocks cancel to stop it.", radius, task.totalPositions));
        TaskRegistry.schedule(task);
    }

    public static boolean isRunning(UUID id)
    {
        return ACTIVE.containsKey(id);
    }

    /** Stop this player's run if one exists. Clears the slot immediately so a fresh run can start right away. */
    public static boolean cancel(UUID id)
    {
        TickTaskBlockUpdater task = ACTIVE.get(id);
        if (task == null)
            return false;
        task.cancelled = true;
        ACTIVE.remove(id, task);
        return true;
    }

    @Override
    public boolean tick()
    {
        ticksRun++;
        if (cancelled)
        {
            msgConfirm(String.format("Block update cancelled after visiting %d block(s), %d changed, %d position(s) "
                    + "skipped in unloaded chunks.", visited, changed, skippedUnloaded));
            finish();
            return true;
        }

        int budget = UpdateBlocksSettings.blocksPerTick();
        int worked = 0;
        while (worked < budget)
        {
            if (dx > radius)
            {
                reportSummary();
                finish();
                return true;
            }

            if (!columnReady)
            {
                colX = center.getX() + dx;
                colZ = center.getZ() + dz;
                // guard: never touch a column whose chunk is not already loaded, so a large radius cannot trigger
                // synchronous worldgen. A skipped column's positions are still counted so the run reads honestly.
                columnLoaded = level.hasChunk(colX >> 4, colZ >> 4);
                curY = minY;
                columnReady = true;
            }

            while (curY <= maxY && worked < budget)
            {
                if (columnLoaded)
                    processBlock(colX, curY, colZ);
                else
                    skippedUnloaded++;
                curY++;
                worked++;
                examined++;
            }

            if (curY > maxY)
            {
                columnReady = false;
                dz++;
                if (dz > radius)
                {
                    dz = -radius;
                    dx++;
                }
            }
        }

        if (ticksRun % PROGRESS_INTERVAL_TICKS == 0)
        {
            long pct = totalPositions == 0 ? 100 : (examined * 100L) / totalPositions;
            msgNotify(String.format("Block update %d%% done (%d block(s) changed so far).", pct, changed));
        }
        return false;
    }

    // recompute one block's shape from its loaded neighbours, then resync/propagate, matching the old per-block
    // behaviour. Neighbour reads and the neighbour update are gated on the neighbour's chunk being loaded so the
    // walk never generates a chunk.
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
            // a neighbour in a different, unloaded chunk is left out: reading it would force-load/generate it.
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
        else
        {
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
        // only propagate a neighbour update when every neighbour chunk is loaded, so the propagation cannot pull an
        // ungenerated chunk into existence at the boundary. Interior blocks always take this path.
        if (allNeighborsLoaded)
            level.updateNeighborsAt(pos, newState.getBlock());
    }

    private void reportSummary()
    {
        double seconds = (System.currentTimeMillis() - startMillis) / 1000.0;
        msgConfirm(String.format("Block update complete for radius %d: visited %d block(s), %d changed, %d "
                + "position(s) skipped in unloaded chunks, %.1fs.", radius, visited, changed, skippedUnloaded, seconds));
    }

    private void finish()
    {
        ACTIVE.remove(playerId, this);
    }

    private void msgConfirm(String message)
    {
        if (player.hasDisconnected())
            return;
        ChatOutputHandler.chatConfirmation(player.createCommandSourceStack(), message);
    }

    private void msgNotify(String message)
    {
        if (player.hasDisconnected())
            return;
        ChatOutputHandler.chatNotification(player.createCommandSourceStack(), message);
    }

    @Override
    public boolean editsBlocks()
    {
        return true;
    }
}
