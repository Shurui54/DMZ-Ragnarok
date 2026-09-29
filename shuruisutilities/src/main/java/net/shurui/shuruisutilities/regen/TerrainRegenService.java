package net.shurui.shuruisutilities.regen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import net.shurui.shuruisutilities.grave.GraveManager;
import net.shurui.shuruisutilities.grave.GraveStorage;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The world repair engine: remembers what a block was before combat removed it, and puts it back.
 *
 * <p>The shape here is deliberate. Repair is NOT done in one pass when the fight ends, because a single large fight can
 * touch tens of thousands of blocks and rewriting them all in one tick stalls the server. Instead every level keeps a
 * queue of owed blocks and drains a bounded number of them per tick, resuming next tick exactly where it stopped. The
 * work is therefore spread over as many ticks as it needs and the tick cost is flat no matter how big the crater was.
 *
 * <p>What we restore toward is a SNAPSHOT taken at the moment of destruction, not an authored template. That is the
 * only thing that can work globally: there is no reference copy of the open world to diff against, so the reference has
 * to be captured on the way out.
 *
 * <p>Two rules keep this from fighting the player:
 * <ul>
 *   <li>A block is only put back if what stands there NOW is air or otherwise replaceable. If someone has built on the
 *       crater in the meantime, their build wins and we drop the debt. Regeneration must never eat player work.</li>
 *   <li>Restores are written without neighbour shape updates, so putting a wall back does not pop the torches and
 *       gravel around it while the rest of the wall is still owed. The wall reassembles quietly, then behaves normally.</li>
 * </ul>
 *
 * <p><b>Reconnecting afterwards.</b> Suppressing shape updates is what keeps a half restored structure from shedding its
 * attachments, but it also means a block comes back wearing the connection flags it had at capture time and nothing ever
 * recomputes them. Glass panes, iron bars, fences, walls, stairs and redstone therefore reassembled as isolated posts:
 * visually the crater was repaired, but the railing through it was in pieces. So restoring is now two phases. Blocks are
 * still written with shape updates suppressed, and every restored position is remembered; once a dimension's debt is
 * fully paid, a second bounded pass walks those positions and recomputes each one against the neighbours that are now
 * actually there. Doing it only at the END is the whole point, because that is the first moment the neighbours are back.
 *
 * <p>Blocks owed inside an unloaded chunk are deferred rather than force loaded. Force loading would let one fight in
 * the wilderness hold chunks resident for as long as the queue lives, which is a much worse cost than the crater. Nobody
 * is looking at an unloaded chunk anyway, so the debt simply waits and pays itself the moment the area loads again.
 */
public final class TerrainRegenService
{
    private TerrainRegenService() {}

    // Blocks restored per level per tick. This is the whole safety margin of the system: it bounds the work regardless
    // of how much was destroyed, which is what makes an arena sized crater survivable.
    public static final int RESTORE_BUDGET_PER_TICK = 6000;

    // How long a crater is allowed to stand before it starts filling in. Short on purpose: the ground closing back up
    // while a fight is still moving reads as the world being alive, and waiting half a minute for it read as nothing
    // happening at all.
    public static final int DEFAULT_RESTORE_DELAY_TICKS = 5 * 20;

    // The live value, settable from /terrainregen.
    private static int restoreDelayTicks = DEFAULT_RESTORE_DELAY_TICKS;

    public static int restoreDelayTicks()
    {
        return restoreDelayTicks;
    }

    public static void setRestoreDelayTicks(int ticks)
    {
        restoreDelayTicks = Math.max(0, ticks);
    }

    // Hard ceiling on owed blocks per level. Reached only by sustained destruction with the queue unable to drain (for
    // example everything owed sits in unloaded chunks). Oldest debt is forgotten first: a partly repaired world is a far
    // better failure than an unbounded map.
    public static final int MAX_PENDING_PER_LEVEL = 400_000;

    // Owed blocks per dimension, in insertion order. LinkedHashMap semantics matter twice over: the cursor drains
    // oldest first so the earliest damage heals first, and the overflow trim above drops oldest first.
    private static final Map<ResourceKey<Level>, Deque<Owed>> PENDING = new HashMap<>();

    // Membership mirror of PENDING, purely so "have we already captured this position" is a hash lookup. Scanning the
    // deque instead would make one explosion cost thousands of walks over a queue that can hold hundreds of thousands
    // of entries, which is quadratic and stalls the server exactly when the system is most needed.
    private static final Map<ResourceKey<Level>, Set<BlockPos>> PENDING_POS = new HashMap<>();

    // Running total for the current crater, so the "restored" line reports the whole repair rather than the last tick's
    // slice of it. Cleared as each dimension's queue empties.
    private static final Map<ResourceKey<Level>, Integer> RESTORED_THIS_BATCH = new HashMap<>();

    // Positions written by this repair, awaiting the reconnect pass. Filled as blocks go back and drained only once the
    // dimension's owed queue is empty, because a block's correct connection state cannot be known until the blocks it
    // should connect TO have also returned.
    private static final Map<ResourceKey<Level>, Deque<BlockPos>> RECONNECT = new HashMap<>();

    // The box this repair has written into, per dimension: {minX, minY, minZ, maxX, maxY, maxZ}. Grown one restored
    // block at a time and used exactly once, at the end, to sweep the entities the repair has buried. A box rather
    // than a per block check because a crater is thousands of blocks and asking "is anything inside this one" for
    // each of them would be thousands of area queries in a tick, which is the cost NpcAnchors was written to avoid.
    private static final Map<ResourceKey<Level>, int[]> REPAIRED_BOUNDS = new HashMap<>();

    // Reconnects attempted per level per tick. Each one recomputes a position and its six neighbours, so the real block
    // touch count is up to seven times this; kept well under the restore budget for that reason.
    public static final int RECONNECT_BUDGET_PER_TICK = 800;

    // Lifetime tallies, per dimension, for /terrainregen. Captured minus restored minus declined is the honest account
    // of where a crater went: if captured is far smaller than the hole you are standing in, the gap is in the CAPTURE
    // hooks; if declined is large, something took the space back before the repair reached it.
    private static final Map<ResourceKey<Level>, long[]> TALLY = new HashMap<>();
    private static final int CAPTURED = 0;
    private static final int RESTORED = 1;
    private static final int DECLINED = 2;
    // Blocks whose connection state the reconnect pass actually REWROTE. Reported by /terrainregen next to the others
    // because it is the only way to tell the three failure modes apart from in game: the pass never ran (this stays 0
    // while blocks are being restored), the pass ran and found nothing to fix (this stays 0 but the queue drains), or
    // the pass ran and did its job (this climbs). Without it, "panes came back unconnected" is unfalsifiable.
    private static final int RECONNECTED = 3;

    private static long[] tally(ServerLevel level)
    {
        return TALLY.computeIfAbsent(level.dimension(), k -> new long[4]);
    }

    /** {captured, restored, declined} since the server started, for this dimension. */
    public static long[] tallyOf(ServerLevel level)
    {
        return level == null ? new long[4] : tally(level).clone();
    }

    // Package-private so TerrainRegenSaved can serialise it. First capture wins, so restoreAtTick is
    // absolute game time and survives a restart unchanged (game time is persisted in level.dat).
    record Owed(BlockPos pos, BlockState state, CompoundTag blockEntity, long restoreAtTick) {}

    /**
     * How deeply nested we are inside a destruction whose CASCADES should be captured.
     *
     * <h2>Why a window and not simply "always"</h2>
     * A block that dies because its support went is destroyed by vanilla, through {@code Block.updateOrDestroy} into
     * {@code Level.destroyBlock}, and never passes through any of DMZ's ki hooks. That is why doors were lost:
     * blasting the ground out from under one removes both halves as a shape reaction, so nothing ever captured
     * either, and it is why cascaded blocks dropped their items, since that path drops unless flag 32 is set.
     *
     * <p>Capturing every such destroy on the server would be wrong in the other direction: mine one dirt block under
     * a torch and the torch would be remembered and put back a few seconds later, which is precisely the "regen
     * undoes player work" the whole system is written to avoid. So capture is armed only for the span of a
     * destruction the repair engine is already responsible for.
     *
     * <p>A counter rather than a flag because one destroy cascades into the next, and main-thread only, which world
     * mutation is. {@link #resetCascadeCapture()} clears a window leaked by an exception on the way out.
     */
    private static int cascadeDepth;

    /** Arm cascade capture for the destruction about to happen. Always pair with {@link #endCascadeCapture()}. */
    public static void beginCascadeCapture()
    {
        cascadeDepth++;
    }

    public static void endCascadeCapture()
    {
        if (cascadeDepth > 0)
            cascadeDepth--;
    }

    /** True while the blocks a destruction knocks over should be remembered. */
    public static boolean capturingCascades()
    {
        return cascadeDepth > 0;
    }

    /**
     * Drop any window left open. Called once a tick: a destruction never spans a tick boundary, so anything still
     * open at that point escaped through an exception, and leaving it would quietly start capturing ordinary mining.
     */
    public static void resetCascadeCapture()
    {
        cascadeDepth = 0;
    }

    /**
     * True when this position holds a block entity that carries items, and so must never be snapshotted.
     *
     * <p>The test is {@link Clearable}, not {@link net.minecraft.world.Container}, because {@code Container} misses two
     * vanilla block entities that still spill their contents the instant the block is removed: a campfire drops what is
     * cooking on it through {@code CampfireBlock.onRemove}, and a lectern pops its book. Both implement {@code Clearable}
     * and neither implements {@code Container}, so a container-only guard let them through, and every one of those items
     * came back a second time when the snapshot restored the block still holding them. {@code Container} itself extends
     * {@code Clearable}, so widening the net keeps every chest, barrel, hopper, furnace, shulker and jukebox covered and
     * adds exactly the two that were missing. In vanilla nothing else implements it, so this is the item-holding set and
     * not a blunter one.
     *
     * <p>Cheapest test first: no block entity means it cannot hold items, so plain terrain never pays for a block entity
     * fetch on a path that runs once per destroyed block.
     */
    public static boolean holdsItems(Level level, BlockPos pos)
    {
        if (level == null || pos == null)
            return false;
        return holdsItems(level, pos, level.getBlockState(pos));
    }

    /** As above, for callers that already hold the state and should not pay to look it up twice. */
    public static boolean holdsItems(Level level, BlockPos pos, BlockState state)
    {
        if (level == null || pos == null || state == null || !state.hasBlockEntity())
            return false;
        return level.getBlockEntity(pos) instanceof Clearable;
    }

    /**
     * Remember one block as it is being destroyed. Call this BEFORE the block actually goes, while the level still
     * holds the old state.
     *
     * <p>Silently does nothing when the rule is off, so no caller needs its own guard, and nothing accumulates on a
     * server that never turned this on.
     *
     * @return whether a snapshot was actually taken. Callers that CLEAR the block themselves must honour this and leave
     *         the block standing when it is false, or they destroy something the engine has not promised to put back.
     */
    public static boolean capture(ServerLevel level, BlockPos pos)
    {
        // Asked per BLOCK, not per level, so a region flag can turn repair on inside an arena in a world that has the
        // gamerule off, or off inside a build in a world that has it on.
        if (level == null || pos == null || !TerrainRegenRule.isEnabledAt(level, pos))
            return false;
        try
        {
            BlockState state = level.getBlockState(pos);
            // Air was never destroyed, so there is nothing owed. Skipping it also means an explosion that overlaps a
            // previous crater does not queue thousands of no-op restores.
            if (state.isAir())
                return false;
            // An item holder is refused HERE, centrally, and not only at the call sites that remember to ask. Every
            // caller is supposed to keep these out, but one of them did not: the ki block-destroy hook captured at the
            // head of DMZ's destroy methods, BEFORE DMZ's own gate had decided, so a chest that the grief guard then
            // refused to break was snapshotted anyway. It sat in the queue holding a copy of its inventory, restore
            // declined for as long as the chest stood, and the moment anyone broke that chest normally the debt was
            // paid into the empty space and every item in it existed twice. A snapshot that is never taken cannot be
            // spent, so the refusal belongs at the one point every path goes through.
            if (holdsItems(level, pos, state))
                return false;
            // A grave (and so a dragon ball totem) is not terrain. Its items live in GraveStorage keyed by the fence
            // position and the blocks are the only handle on them, so capturing it means removing it now and putting
            // it back only if the space is still free when the debt comes due. Decline, and every caller that honours
            // the contract leaves it standing instead.
            if (net.shurui.shuruisutilities.grave.GraveManager.isGraveBlock(level, pos, state))
                return false;
            // Diagnostic (2026-09-17): a grave is exempted just above through GraveStorage. The owner reports totems
            // turning into plain blocks when regen runs near them, and no path in the current tree reproduces that,
            // so note when a grave shaped block still reaches capture with no record behind it. This is an
            // observation to settle the cause from a production log, not a claim about what the cause is.
            warnOrphanGraveShape(level, pos, state);
            boolean firstOfBatch = !PENDING.containsKey(level.dimension()) || PENDING.get(level.dimension()).isEmpty();
            Deque<Owed> queue = PENDING.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>());
            Set<BlockPos> seen = PENDING_POS.computeIfAbsent(level.dimension(), k -> new HashSet<>());
            // First capture wins. If two blasts hit the same block, the older snapshot is the one that predates the
            // whole fight, which is what we want to restore toward. Answered as a success either way: the position IS
            // remembered, so a caller waiting on that answer before clearing the block is safe to clear it.
            if (seen.contains(pos))
                return true;
            CompoundTag beTag = null;
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null)
                beTag = be.saveWithFullMetadata();
            BlockPos key = pos.immutable();
            queue.addLast(new Owed(key, state, beTag, level.getGameTime() + restoreDelayTicks));
            seen.add(key);
            tally(level)[CAPTURED]++;
            // A double block is TWO positions that only exist as a pair, and only ever ONE of them is destroyed by
            // anything that calls this. Vanilla takes the other half away as a SHAPE REACTION to this one going:
            // Level.removeBlock writes with flag 3, so neighbour shape updates run, and DoorBlock.updateShape
            // answers AIR for a half whose partner has gone. A shape reaction passes through no capture hook at
            // all, so the partner was never owed, and by the time the loop reached its position it was already air
            // and declined for being air.
            //
            // That is why doors never came back. Not that they failed to RESTORE: the surviving half was restored
            // fine, with shape updates suppressed. It is that the reconnect pass then asked it to recompute against
            // neighbours that still had no partner, got AIR, and wrote it. The repair deleted its own work a few
            // ticks later. Captured as a pair, they come back as a pair. Beds and tall plants had it too.
            BlockPos partner = doubleBlockPartner(key, state);
            if (partner != null)
                capture(level, partner);
            // Whoever was standing on this block is owed a place to stand as well.
            NpcAnchors.note(level, key);
            // One line per crater, not per block. Three separate diagnoses of "regen does not work" have failed for
            // want of knowing which half was broken, and a capture that never happens and a restore that never runs
            // look identical from in game. This says a debt was opened; the matching line in drain says it was paid.
            if (firstOfBatch)
            {
                LoggingHandler.sulog.info("[regen] Capturing destroyed terrain in {} ; first block due back in {}s.",
                        level.dimension().location(), restoreDelayTicks / 20);
            }
            while (queue.size() > MAX_PENDING_PER_LEVEL)
            {
                Owed dropped = queue.removeFirst();
                seen.remove(dropped.pos());
            }
            return true;
        }
        catch (Throwable t)
        {
            // A capture is best effort. Never let remembering a block stop the block from breaking, so this reports a
            // failure and leaves the decision with the caller rather than throwing into a ki attack's block loop.
            return false;
        }
    }

    // Distinct positions the orphan-grave-shape probe has already warned about, and the hard cap on how many it will
    // ever warn about, so a pathological world cannot flood the log. One entry per position keeps a block that is
    // captured over and over (a repeated blast on the same spot) to a single line. Server thread only, so no locking.
    private static final Set<String> ORPHAN_GRAVE_WARNED = new HashSet<>();
    private static final int ORPHAN_GRAVE_WARN_CAP = 64;

    /**
     * Observe when a grave shaped block reaches capture with no {@link net.shurui.shuruisutilities.grave.GraveStorage}
     * record behind it: an OAK_FENCE or PLAYER_HEAD whose head carries the grave marker OR a stamped skull owner,
     * while storage has neither this position nor the fence one below it. That is the shape a totem would take if its
     * record desynced away, which is the theory behind totems reported turning into plain blocks near regen. Logged at
     * WARN as an observation, deduped per position and capped so it cannot spam. Never throws: a diagnostic must not
     * be able to break a capture.
     */
    private static void warnOrphanGraveShape(ServerLevel level, BlockPos pos, BlockState state)
    {
        try
        {
            boolean isFence = state.is(Blocks.OAK_FENCE);
            boolean isHead = state.is(Blocks.PLAYER_HEAD);
            if (!isFence && !isHead)
                return;
            // isGraveBlock already exempted anything with a live record, so reaching here means storage missed. Confirm
            // that cheaply before paying for the block-entity read below.
            GraveStorage storage = GraveStorage.get(level);
            if (storage.has(pos) || storage.has(pos.below()))
                return;
            // The head is at pos for a head, one above for a fence. No head means an ordinary fence: nothing to see.
            BlockPos headPos = isHead ? pos : pos.above();
            if (!level.getBlockState(headPos).is(Blocks.PLAYER_HEAD))
                return;
            boolean marker = GraveManager.headCarriesMarker(level, headPos);
            boolean owner = level.getBlockEntity(headPos) instanceof SkullBlockEntity skull
                    && skull.getOwnerProfile() != null;
            if (!marker && !owner)
                return;
            String key = level.dimension().location() + "@" + pos.asLong();
            if (ORPHAN_GRAVE_WARNED.contains(key) || ORPHAN_GRAVE_WARNED.size() >= ORPHAN_GRAVE_WARN_CAP)
                return;
            ORPHAN_GRAVE_WARNED.add(key);
            LoggingHandler.sulog.warn(
                    "[regen] A grave shaped block ({}) was captured by terrain regen with no grave record in {} at {}; "
                            + "head marker {}, skull owner {}. Observation only, not a confirmed cause.",
                    isHead ? "player head" : "oak fence", level.dimension().location(), pos,
                    marker ? "present" : "absent", owner ? "present" : "absent");
        }
        catch (Throwable ignored)
        {
            // a diagnostic that throws would be worse than the bug it is watching for
        }
    }

    /**
     * The other half of a two-block structure, or null for an ordinary block.
     *
     * <p>Both vanilla shapes are covered. {@code DOUBLE_BLOCK_HALF} stacks vertically and carries doors, tall
     * flowers, tall grass and large ferns; {@code BED_PART} lies horizontally, and {@code getConnectedDirection}
     * is vanilla's own answer for which way the other half of a bed is.
     *
     * <p>Reading the state we were handed rather than the level, because by the time some callers reach here the
     * block in the world may already be on its way out.
     */
    private static BlockPos doubleBlockPartner(BlockPos pos, BlockState state)
    {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
        {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                    ? pos.below() : pos.above();
        }
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BlockStateProperties.BED_PART))
        {
            return pos.relative(BedBlock.getConnectedDirection(state));
        }
        return null;
    }

    /**
     * Pay down this level's debt, up to the per tick budget. Returns how many blocks were actually put back, which is
     * only used by callers that want to log.
     */
    public static int drain(ServerLevel level)
    {
        if (level == null)
            return 0;
        Deque<Owed> queue = PENDING.get(level.dimension());
        if (queue == null || queue.isEmpty())
        {
            // Nothing owed: this is exactly when the reconnect pass is safe to run, because everything that was coming
            // back has come back. Costs nothing on a server that has never had a crater, since that queue is empty too.
            return reconnect(level);
        }
        // A rule flipped off mid life abandons the debt outright rather than leaving it to reappear if the rule is
        // flipped back on hours later, which would look like the world spontaneously healing old damage. Regions are
        // the exception: with any region defined, a block may be owed because ITS region says so rather than because
        // the world switch does, so the queue is only dumped when neither could be true.
        if (!TerrainRegenRule.isEnabled(level) && !net.shurui.shuruisutilities.regions.RegionEventHandler.hasRegions())
        {
            queue.clear();
            clearSeen(level);
            // The reconnect debt belongs to a repair that is now abandoned, so it goes with it rather than being paid
            // against blocks whose neighbours will never arrive. The repaired box goes too: there is no repair left
            // to have buried anybody, and keeping it would sweep entities for a crater that is never being filled.
            RECONNECT.remove(level.dimension());
            REPAIRED_BOUNDS.remove(level.dimension());
            return 0;
        }
        long now = level.getGameTime();
        int restored = 0;
        int examined = 0;
        int size = queue.size();
        // Bounded by the budget AND by the queue length, so a queue full of not-yet-due or deferred entries cannot spin.
        while (restored < RESTORE_BUDGET_PER_TICK && examined < size && !queue.isEmpty())
        {
            Owed owed = queue.pollFirst();
            examined++;
            if (owed.restoreAtTick() > now)
            {
                // Not due yet. Because the queue is in insertion order and the delay is constant, everything behind this
                // is also not due, so put it back and stop rather than walking the rest.
                queue.addFirst(owed);
                break;
            }
            if (!level.isLoaded(owed.pos()))
            {
                // Deferred, not dropped: re-queued at the back so it is retried once the area loads, and so it cannot
                // block the entries behind it.
                queue.addLast(owed);
                continue;
            }
            forget(level, owed.pos());
            if (restore(level, owed))
            {
                restored++;
                tally(level)[RESTORED]++;
                Deque<BlockPos> rcQueue = RECONNECT.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>());
                rcQueue.addLast(owed.pos());
                // BOUNDED, like PENDING is. Every restored block lands here and the drain runs at
                // RECONNECT_BUDGET_PER_TICK, so a restore burst that outruns the drain grows this without limit,
                // and it is PERSISTED: an unbounded deque on the save path is what turns an autosave into a
                // multi-second gzip. Oldest first, because a reconnect pass that never ran is the least useful
                // thing here: the block is already back, only its connection flags are stale.
                while (rcQueue.size() > MAX_PENDING_PER_LEVEL)
                    rcQueue.removeFirst();
            }
            else
            {
                tally(level)[DECLINED]++;
            }
        }
        // Reported once per crater, when the last owed block in this dimension has been put back, so the log carries a
        // "queued" line and a matching "restored" line per fight and nothing in between.
        if (restored > 0 && queue.isEmpty())
        {
            int total = RESTORED_THIS_BATCH.merge(level.dimension(), restored, Integer::sum);
            RESTORED_THIS_BATCH.remove(level.dimension());
            LoggingHandler.sulog.info("[regen] Restored {} blocks in {}.", total, level.dimension().location());
        }
        else if (restored > 0)
        {
            RESTORED_THIS_BATCH.merge(level.dimension(), restored, Integer::sum);
        }
        return restored;
    }

    // Put one block back, or decline to. Returns whether anything was written.
    private static boolean restore(ServerLevel level, Owed owed)
    {
        try
        {
            BlockState current = level.getBlockState(owed.pos());
            // Only fill what is still empty. Anything else means the space has been claimed since, by a player build or
            // by another system, and overwriting it would be the regeneration destroying something in its turn.
            if (!current.isAir() && !current.canBeReplaced())
                return false;
            // UPDATE_CLIENTS sends the change; UPDATE_KNOWN_SHAPE suppresses the neighbour shape reactions that would
            // otherwise make half restored structures shed their attached blocks as they come back.
            level.setBlock(owed.pos(), owed.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (owed.blockEntity() != null)
            {
                BlockEntity be = level.getBlockEntity(owed.pos());
                if (be != null)
                {
                    be.load(owed.blockEntity());
                    // Mark it dirty, or the reloaded contents are only in memory: the chunk is not flagged for save, so
                    // a restored chest's items would survive until the next restart and then be gone. load() alone does
                    // not do this, which is why the state came back and the NBT quietly did not.
                    be.setChanged();
                }
            }
            // The ground is back, so put whoever was standing on it back on top of it. After the block, never before,
            // or the NPC would be set down into a space that is still air and simply fall again.
            NpcAnchors.restore(level, owed.pos());
            growRepairedBounds(level, owed.pos());
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Recompute the connection state of blocks this repair put back, now that their neighbours are back too.
     *
     * <p>Runs only once a dimension owes nothing, and is budgeted per tick exactly like the restore is, so a crater the
     * size of an arena reconnects over several ticks instead of in one stall.
     *
     * <p>Each entry fixes the restored position AND its six neighbours. The neighbours matter as much as the position
     * itself: a pane that was never destroyed still has to be told that the pane beside it has come back, and nothing
     * else is going to tell it. Recomputing is idempotent, so a position reached twice from two different neighbours
     * simply writes the same answer.
     *
     * @return how many positions were reconnected, so the caller's "did any work" contract still holds
     */
    private static int reconnect(ServerLevel level)
    {
        Deque<BlockPos> queue = RECONNECT.get(level.dimension());
        if (queue == null || queue.isEmpty())
            return 0;
        int done = 0;
        while (done < RECONNECT_BUDGET_PER_TICK && !queue.isEmpty())
        {
            BlockPos pos = queue.pollFirst();
            done++;
            if (!level.isLoaded(pos))
                continue;   // dropped, not deferred: cosmetic repair is not worth holding a queue open for
            fixShape(level, pos);
            nudgeFluid(level, pos);
            for (Direction dir : Direction.values())
            {
                BlockPos side = pos.relative(dir);
                fixShape(level, side);
                nudgeFluid(level, side);
            }
        }
        if (queue.isEmpty())
        {
            RECONNECT.remove(level.dimension());
            // Everything is back and reconnected, so this is the first moment the world is in its final shape and
            // therefore the only honest moment to ask who the repair has buried.
            unburyEntities(level);
        }
        return done;
    }

    // Recompute one position against its neighbours and write it back only if it actually changed. Writing with
    // UPDATE_KNOWN_SHAPE still, because every position that needs correcting is already in the queue, so letting the
    // change cascade would only redo the same work through a slower path.
    private static void fixShape(ServerLevel level, BlockPos pos)
    {
        try
        {
            BlockState state = level.getBlockState(pos);
            if (state.isAir())
                return;
            BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
            if (updated == state)
                return;
            // A COSMETIC PASS MUST NEVER DELETE A BLOCK. updateFromNeighbourShapes answers AIR for anything that
            // decides it is unsupported, and this pass runs over blocks the repair has only just paid for, so one
            // absent neighbour here means destroying the very work being finished. This is about connection FLAGS;
            // "you should not exist" is not an answer this pass is entitled to act on. It is what deleted every
            // restored door half, and it would equally have taken a torch or a ladder whose support was declined
            // for standing on someone's build.
            if (updated.isAir())
                return;
            level.setBlock(pos, updated, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            tally(level)[RECONNECTED]++;
        }
        catch (Throwable t)
        {
            // One block failing to reconnect is cosmetic. Never let it stop the rest of the pass.
        }
    }

    /**
     * Tell a fluid here to think again.
     *
     * <p>Restores are written with neighbour updates OFF, which is what stops a half rebuilt wall shedding its
     * torches. The side effect is that water beside the repair is never told anything changed. Water that ran into
     * the crater while it stood open is then left sitting against the restored blocks with no reason to move, since
     * a flowing fluid only drains when something updates it. That is the "water sometimes does not go away": not
     * water that failed to be replaced, water that was never asked to re-evaluate.
     *
     * <p>Scheduling its own tick is vanilla's own mechanism for this, so the fluid drains, spreads or stays exactly
     * as it would have done if the blocks had been placed by hand.
     */
    private static void nudgeFluid(ServerLevel level, BlockPos pos)
    {
        try
        {
            FluidState fluid = level.getFluidState(pos);
            if (!fluid.isEmpty())
                level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
        }
        catch (Throwable ignored)
        {
            // A stranded puddle is cosmetic; never let it break the pass.
        }
    }

    /** Widen the box this repair has written into, so the entity sweep at the end knows where to look. */
    private static void growRepairedBounds(ServerLevel level, BlockPos pos)
    {
        int[] b = REPAIRED_BOUNDS.get(level.dimension());
        if (b == null)
        {
            REPAIRED_BOUNDS.put(level.dimension(),
                    new int[] { pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ() });
            return;
        }
        b[0] = Math.min(b[0], pos.getX());
        b[1] = Math.min(b[1], pos.getY());
        b[2] = Math.min(b[2], pos.getZ());
        b[3] = Math.max(b[3], pos.getX());
        b[4] = Math.max(b[4], pos.getY());
        b[5] = Math.max(b[5], pos.getZ());
    }

    /**
     * Get out of the ground whoever the repair has just closed in.
     *
     * <p>Restoring a crater fills space that things were standing in. {@link NpcAnchors} puts placed NPCs back on
     * top of the block they were on, but that only covers something that was standing on a captured block when the
     * blast took it: a player who flew into the hole, a mob that wandered in afterwards, a minecart that rolled in,
     * are all simply enclosed. Suffocating inside restored terrain is not a repair.
     *
     * <p>ONE query for the whole batch, over the box the repair actually wrote into. Per block would be thousands
     * of area queries for a crater, which is the cost this whole system is careful about.
     *
     * <p>Lifted straight up first, because that keeps them where they were standing and is what "put the ground
     * back under them" ought to mean. Only when there is no room within NpcUnstuck.MAX_LIFT blocks, which means
     * they are under something solid rather than in the crater, do they go to the surface instead.
     */
    private static void unburyEntities(ServerLevel level)
    {
        int[] b = REPAIRED_BOUNDS.remove(level.dimension());
        if (b == null)
            return;
        try
        {
            // Graves first, and by the same argument the entity sweep makes: the repair has just closed the ground
            // back over whatever stood in the crater. It cannot have overwritten a grave (it only fills empty space)
            // but it can seal one under the surface, and a buried totem is a dragon ball set nobody can reach. One
            // pass over the graves inside the repaired box, which is normally none at all.
            int lifted = net.shurui.shuruisutilities.grave.GraveManager.liftBuriedGraves(
                    level, b[0], b[1], b[2], b[3], b[4], b[5]);
            if (lifted > 0)
                LoggingHandler.sulog.info("[regen] Lifted {} buried grave(s) back to the surface in {}.",
                        lifted, level.dimension().location());
            // Inflated by one so an entity standing on the very edge of the repair, or half inside its top face,
            // is still considered. A block restored under someone's feet lifts them by less than a whole block.
            AABB box = new AABB(b[0], b[1], b[2], b[3] + 1.0, b[4] + 1.0, b[5] + 1.0).inflate(1.0);
            List<Entity> inside = level.getEntities((Entity) null, box, e -> e != null && e.isAlive()
                    && !e.isRemoved() && !e.isSpectator());
            for (Entity entity : inside)
            {
                unbury(level, entity);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[regen] could not sweep buried entities: {}", t.toString());
        }
    }

    private static void unbury(ServerLevel level, Entity entity)
    {
        // Shared with every NPC's own tick self-heal, in sdu so the four trees that own an NPC can reach it. Same
        // lift-in-place-then-surface behaviour that used to be inlined here (MAX_UNBURY_LIFT lives there as MAX_LIFT).
        net.shurui.dev.sdu.entity.NpcUnstuck.liftOut(level, entity);
    }

    // Drop one position from the membership mirror. Called as an entry leaves the queue for good, so that a block
    // destroyed again after it has been repaired can be captured afresh rather than being ignored forever.
    private static void forget(ServerLevel level, BlockPos pos)
    {
        Set<BlockPos> seen = PENDING_POS.get(level.dimension());
        if (seen != null)
            seen.remove(pos);
    }

    private static void clearSeen(ServerLevel level)
    {
        Set<BlockPos> seen = PENDING_POS.get(level.dimension());
        if (seen != null)
            seen.clear();
    }

    /**
     * Make everything currently owed in this level due immediately, and pay as much of it as the per tick budget
     * allows. Returns how many were put back on this call.
     *
     * <p>For testing. The thirty second wait is right in play and hopeless when you are trying to find out whether the
     * system works at all, because a failed attempt and a slow attempt look identical for half a minute.
     */
    public static int restoreNow(ServerLevel level)
    {
        if (level == null)
            return 0;
        Deque<Owed> queue = PENDING.get(level.dimension());
        if (queue == null || queue.isEmpty())
            return 0;
        long now = level.getGameTime();
        Deque<Owed> due = new ArrayDeque<>(queue.size());
        for (Owed owed : queue)
        {
            due.addLast(new Owed(owed.pos(), owed.state(), owed.blockEntity(), now));
        }
        queue.clear();
        queue.addAll(due);
        return drain(level);
    }

    /** How many NPCs are owed a place to stand in this level, for {@code /terrainregen}. */
    public static int pendingNpcCount(ServerLevel level)
    {
        return NpcAnchors.owedCount(level);
    }

    // Number of blocks currently owed in a level, for commands and logging.
    /** How many restored positions are still waiting for the reconnect pass. */
    public static int pendingReconnectCount(ServerLevel level)
    {
        if (level == null)
            return 0;
        Deque<BlockPos> queue = RECONNECT.get(level.dimension());
        return queue == null ? 0 : queue.size();
    }

    public static int pendingCount(ServerLevel level)
    {
        if (level == null)
            return 0;
        Deque<Owed> queue = PENDING.get(level.dimension());
        return queue == null ? 0 : queue.size();
    }

    // Drop a level's debt. Called when a level unloads so a removed or reloaded dimension does not keep stale positions.
    public static void forget(ServerLevel level)
    {
        if (level != null)
        {
            PENDING.remove(level.dimension());
            PENDING_POS.remove(level.dimension());
            RESTORED_THIS_BATCH.remove(level.dimension());
            RECONNECT.remove(level.dimension());
            REPAIRED_BOUNDS.remove(level.dimension());
            TALLY.remove(level.dimension());
            NpcAnchors.forget(level);
        }
    }

    /**
     * Copy this level's live debt into its on-disk store, so a restart does not lose an unfinished crater.
     *
     * <p>The whole reason a crater could stay a permanent hole: the queues here are memory only, and every level
     * unloads on shutdown. Whatever had not drained yet, and everything still deferred in an unloaded chunk, went
     * with the process. Mirrored into a {@link TerrainRegenSaved} and written with the level, the debt is picked up
     * again on the next boot and paid off then.
     */
    static void persistInto(ServerLevel level, TerrainRegenSaved data)
    {
        if (level == null || data == null)
            return;
        Deque<Owed> queue = PENDING.get(level.dimension());
        data.owed = queue == null ? new ArrayDeque<>() : new ArrayDeque<>(queue);
        Deque<BlockPos> rc = RECONNECT.get(level.dimension());
        data.reconnect = rc == null ? new ArrayDeque<>() : new ArrayDeque<>(rc);
    }

    /**
     * Rebuild this level's live debt from its on-disk store on load, so terrain owed before the restart heals now.
     *
     * <p>Additive rather than replacing: a level can, in principle, take a hit in the same tick it loads, and the
     * fresh capture must not be clobbered by the stored one. In practice the stored set is almost always the whole
     * debt, and the membership mirror is rebuilt from it so a re-destroyed block is still captured afresh.
     */
    static void hydrateFrom(ServerLevel level, TerrainRegenSaved data)
    {
        if (level == null || data == null)
            return;
        if (data.owed != null && !data.owed.isEmpty())
        {
            Deque<Owed> queue = PENDING.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>());
            Set<BlockPos> seen = PENDING_POS.computeIfAbsent(level.dimension(), k -> new HashSet<>());
            for (Owed o : data.owed)
            {
                if (seen.add(o.pos()))
                    queue.addLast(o);
            }
        }
        if (data.reconnect != null && !data.reconnect.isEmpty())
        {
            Deque<BlockPos> rc = RECONNECT.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>());
            rc.addAll(data.reconnect);
        }
    }

    public static void forgetAll()
    {
        PENDING.clear();
        PENDING_POS.clear();
        RESTORED_THIS_BATCH.clear();
        RECONNECT.clear();
        REPAIRED_BOUNDS.clear();
        TALLY.clear();
        NpcAnchors.forgetAll();
    }
}
