package net.shurui.shuruisutilities.regen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import net.shurui.shuruisutilities.regen.TerrainRegenService.Owed;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The on-disk home of a level's unpaid terrain-repair debt, so a crater survives a server restart and heals after
 * it rather than standing open forever.
 *
 * <p>This is the fix for the one way the repair engine could leave a permanent hole. The live queues in
 * {@link TerrainRegenService} are memory only, and every level unloads on shutdown, so anything not yet drained, and
 * everything deferred inside an unloaded chunk, was lost the moment the process ended. Stored here, keyed to the
 * level's own data storage, the debt is written out with the world and read back on the next load.
 *
 * <p>Stored per level, not globally, for the obvious reason that a {@link SavedData} lives in one dimension's data
 * folder; the service keys its live maps by dimension, so the two line up one to one. The service owns the working
 * copy and this owns the durable one; they are synced at load, at save, and at unload by
 * {@code TerrainRegenPersistence}.
 */
public final class TerrainRegenSaved extends SavedData
{
    /** File name under {@code <dim>/data/}. Pinned literal so a rename of the mod id cannot orphan saved debt. */
    public static final String NAME = "shuruisutilities_terrain_regen";

    private static final String TAG_OWED = "owed";
    private static final String TAG_RECONNECT = "reconnect";
    private static final String TAG_POS = "pos";
    private static final String TAG_STATE = "state";
    private static final String TAG_BE = "be";
    private static final String TAG_DUE = "due";

    /**
     * Format 2: a palette and primitive arrays instead of one compound per block.
     *
     * <h2>Why the format changed</h2>
     * Format 1 wrote a {@code CompoundTag} per owed block, each carrying its own position compound and its own
     * {@code NbtUtils.writeBlockState} result (a {@code Name} string plus a {@code Properties} compound). At the
     * {@code MAX_PENDING_PER_LEVEL} ceiling of 400,000 that is well over a million live objects built at once,
     * for a save that has to hold them all in memory before a byte reaches disk, and it took a 12 GB server down
     * with an OutOfMemoryError inside {@code writeBlockState} on 2026-09-01.
     *
     * <p>The waste was almost entirely duplication. A crater is made of a handful of DISTINCT states (stone,
     * dirt, grass, whatever was there) repeated hundreds of thousands of times, so the palette collapses that to
     * one compound per distinct state and an int per block. Position becomes one long, due time one long. An
     * entry costs 20 bytes of primitives in a shared array rather than a tree of objects, which is what makes
     * the existing cap affordable rather than fatal.
     */
    private static final int FORMAT_VERSION = 2;

    private static final String TAG_VERSION = "fmt";
    private static final String TAG_PALETTE = "palette";
    private static final String TAG_POSITIONS = "positions";
    private static final String TAG_STATE_IDS = "stateIds";
    private static final String TAG_DUE_ARRAY = "dueAt";
    private static final String TAG_BE_AT = "beAt";
    private static final String TAG_BE_TAGS = "beTags";
    private static final String TAG_RECONNECT_ARRAY = "reconnectAt";

    /**
     * How many block entities one level will persist.
     *
     * <p>The palette fixes the states but a block entity tag is arbitrary: a captured chest carries its whole
     * inventory, and a crater through a storage room would rebuild the same wall of memory the palette just tore
     * down. Past this, the block still comes back, just empty, which is a far better outcome than the save
     * failing and the whole debt being lost with it.
     */
    private static final int MAX_BLOCK_ENTITIES = 8192;

    /**
     * How many entries this will WRITE, per level, whatever the live queues hold.
     *
     * <h2>Why a second, smaller limit than the service's</h2>
     * {@code MAX_PENDING_PER_LEVEL} bounds what the SERVICE tracks in memory, which is a different question from
     * what is affordable to serialise. A SavedData is gzipped on the SERVER THREAD, and on 2026-09-01 that write
     * ran for 60 seconds and the watchdog killed the server mid-gzip, leaving a truncated file that then failed
     * to load with {@code EOFException: Unexpected end of ZLIB input stream}. The palette made the tag small; it
     * did not make an unbounded amount of work bounded.
     *
     * <p>Terrain debt is TRANSIENT by nature: it exists to heal a crater over the next few seconds or minutes.
     * Dropping the tail of an enormous one costs some blocks staying broken, and that is plainly better than a
     * minute-long freeze, a forced shutdown, and a corrupt file that loses the whole debt anyway.
     */
    private static final int MAX_PERSISTED = 50_000;

    /** The owed blocks, mirrored from the service on save. */
    Deque<Owed> owed = new ArrayDeque<>();

    /** Positions still awaiting the reconnect pass, mirrored from the service on save. */
    Deque<BlockPos> reconnect = new ArrayDeque<>();

    public TerrainRegenSaved() {}

    /** Attach to a level, loading any debt saved before the last shutdown. */
    public static TerrainRegenSaved get(ServerLevel level)
    {
        return level.getDataStorage().computeIfAbsent(
                tag -> load(level, tag), TerrainRegenSaved::new, NAME);
    }

    /**
     * Read the debt back.
     *
     * <p>A block state cannot be parsed without the block registry to resolve its id, so this reads through the
     * level's own holder lookup. A snapshot whose block no longer exists (a mod removed since the save) resolves to
     * air through {@code readBlockState} and is dropped rather than restored, which is the safe direction: better a
     * missing block than a crash or a wrong one.
     */
    public static TerrainRegenSaved load(ServerLevel level, CompoundTag tag)
    {
        TerrainRegenSaved data = new TerrainRegenSaved();
        HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);

        // Format 2 and up. A world saved by an older jar has no version key and falls through to the original
        // reader below, so upgrading never costs somebody their outstanding debt; the next save rewrites it in
        // the new shape.
        if (tag.getInt(TAG_VERSION) >= 2)
        {
            loadPalette(data, blocks, tag);
            return data;
        }

        ListTag owedList = tag.getList(TAG_OWED, Tag.TAG_COMPOUND);
        for (int i = 0; i < owedList.size(); i++)
        {
            CompoundTag e = owedList.getCompound(i);
            try
            {
                BlockPos pos = NbtUtils.readBlockPos(e.getCompound(TAG_POS));
                BlockState state = NbtUtils.readBlockState(blocks, e.getCompound(TAG_STATE));
                if (state.isAir())
                    continue; // block no longer registered; nothing meaningful to restore
                CompoundTag be = e.contains(TAG_BE) ? e.getCompound(TAG_BE) : null;
                long due = e.getLong(TAG_DUE);
                data.owed.addLast(new Owed(pos, state, be, due));
            }
            catch (Throwable ignored)
            {
                // one malformed entry is not worth failing the whole load; skip it and keep the rest of the debt
            }
        }

        ListTag rcList = tag.getList(TAG_RECONNECT, Tag.TAG_COMPOUND);
        for (int i = 0; i < rcList.size(); i++)
        {
            try
            {
                data.reconnect.addLast(NbtUtils.readBlockPos(rcList.getCompound(i)));
            }
            catch (Throwable ignored)
            {
                // ditto
            }
        }
        return data;
    }

    /**
     * Read the palette format.
     *
     * <p>Every array is read defensively against the others' lengths rather than trusting the file: a truncated
     * or hand edited save should cost the entries it actually lost, not throw and take the whole debt with it.
     */
    private static void loadPalette(TerrainRegenSaved data, HolderGetter<Block> blocks, CompoundTag tag)
    {
        ListTag palette = tag.getList(TAG_PALETTE, Tag.TAG_COMPOUND);
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < states.length; i++)
        {
            try
            {
                states[i] = NbtUtils.readBlockState(blocks, palette.getCompound(i));
            }
            catch (Throwable ignored)
            {
                states[i] = null;   // a block the pack no longer has; entries pointing here are dropped
            }
        }

        long[] positions = tag.getLongArray(TAG_POSITIONS);
        int[] stateIds = tag.getIntArray(TAG_STATE_IDS);
        long[] dueAt = tag.getLongArray(TAG_DUE_ARRAY);

        // Block entities are sparse, so they arrive as a list of (index, tag) rather than one slot per block.
        int[] beAt = tag.getIntArray(TAG_BE_AT);
        ListTag beTags = tag.getList(TAG_BE_TAGS, Tag.TAG_COMPOUND);
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        for (int k = 0; k < beAt.length && k < beTags.size(); k++)
            blockEntities.put(beAt[k], beTags.getCompound(k));

        int n = Math.min(positions.length, Math.min(stateIds.length, dueAt.length));
        for (int i = 0; i < n; i++)
        {
            int id = stateIds[i];
            if (id < 0 || id >= states.length)
                continue;
            BlockState state = states[id];
            if (state == null || state.isAir())
                continue;   // block no longer registered; nothing meaningful to restore
            data.owed.addLast(new Owed(BlockPos.of(positions[i]), state, blockEntities.get(i), dueAt[i]));
        }

        for (long p : tag.getLongArray(TAG_RECONNECT_ARRAY))
            data.reconnect.addLast(BlockPos.of(p));
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putInt(TAG_VERSION, FORMAT_VERSION);

        // Oldest debt first, so what survives a truncation is the part closest to being due.
        int n = Math.min(owed.size(), MAX_PERSISTED);
        if (owed.size() > n)
        {
            LoggingHandler.sulog.warn("[regen] Saving only {} of {} owed blocks; the rest stay broken rather than"
                    + " stall the save.", n, owed.size());
        }
        long[] positions = new long[n];
        int[] stateIds = new int[n];
        long[] dueAt = new long[n];

        // Distinct states only. LinkedHashMap so an index is stable against the ListTag being built beside it.
        Map<BlockState, Integer> paletteIds = new LinkedHashMap<>();
        ListTag palette = new ListTag();
        List<Integer> beAt = new ArrayList<>();
        ListTag beTags = new ListTag();

        int i = 0;
        for (Owed o : owed)
        {
            if (i >= n)
                break;
            positions[i] = o.pos().asLong();
            Integer id = paletteIds.get(o.state());
            if (id == null)
            {
                id = palette.size();
                paletteIds.put(o.state(), id);
                palette.add(NbtUtils.writeBlockState(o.state()));
            }
            stateIds[i] = id;
            dueAt[i] = o.restoreAtTick();
            if (o.blockEntity() != null && beTags.size() < MAX_BLOCK_ENTITIES)
            {
                beAt.add(i);
                beTags.add(o.blockEntity());
            }
            i++;
        }

        int[] beIndices = new int[beAt.size()];
        for (int k = 0; k < beIndices.length; k++)
            beIndices[k] = beAt.get(k);

        tag.put(TAG_PALETTE, palette);
        tag.putLongArray(TAG_POSITIONS, positions);
        tag.putIntArray(TAG_STATE_IDS, stateIds);
        tag.putLongArray(TAG_DUE_ARRAY, dueAt);
        tag.putIntArray(TAG_BE_AT, beIndices);
        tag.put(TAG_BE_TAGS, beTags);

        long[] rc = new long[Math.min(reconnect.size(), MAX_PERSISTED)];
        int j = 0;
        for (BlockPos p : reconnect)
        {
            if (j >= rc.length)
                break;
            rc[j++] = p.asLong();
        }
        tag.putLongArray(TAG_RECONNECT_ARRAY, rc);
        return tag;
    }
}
