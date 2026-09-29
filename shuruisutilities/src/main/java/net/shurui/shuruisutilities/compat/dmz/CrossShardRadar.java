package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.shurui.shuruisutilities.grave.GraveData;
import net.shurui.shuruisutilities.grave.GraveStorage;
import net.shurui.shuruisutilities.shard.ShardConfig;
import net.shurui.shuruisutilities.shard.ShardExecutor;
import net.shurui.shuruisutilities.shard.ShardPresence;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Makes the dragon radar account for balls network wide, not just the ones in dimensions the local shard happens to
 * host, so a player can track down their dragon balls (a loose ball, or a grave totem holding a dead or logged-out
 * player's set) wherever on the network they ended up.
 *
 * <h2>The problem</h2>
 *
 * <p>DMZ builds the radar in {@code DragonBallsHandler.buildRadarPacket} by looping a set's valid dimensions and
 * resolving {@code server.getLevel(dim)}. On a shard that does NOT host a dimension there is no level, so those
 * balls are simply absent from the packet. A player on the hub cannot see a ball sitting on a planet hosted by a
 * different shard, even though the two are one world to the player.
 *
 * <h2>What travels, and why totems are the driving case</h2>
 *
 * <p>Three things per owning shard now travel, matching exactly what the LOCAL radar sees so the two agree:
 *
 * <ul>
 *   <li><b>Loose balls in EVERY hosted dimension</b>, not only each set's home dimensions. The local sweep
 *       ({@code RadarForeignDimensionBalls}) already reads balls that ended up outside a set's scatter list, so the
 *       network view must too, or a foreign-dimension ball would be visible at home and invisible from away.</li>
 *   <li><b>Grave totems.</b> A totem is a dead or logged-out player's dragon balls, and it can be in any dimension on
 *       any shard. The whole point of "the radar works everywhere" is that a player who died on OW1 and is now on the
 *       SMP can find that grave. The right-click readout ({@code RadarDimensionReport}) is the load-bearing half: a
 *       blip is only useful once you are already in the dimension, and the readout is what tells you which SERVER and
 *       dimension to travel to first.</li>
 * </ul>
 *
 * <h2>This network is NOT one dimension, one shard</h2>
 *
 * <p>The obvious model, "a dimension is hosted by exactly one shard, so publish per dimension and let the owner win",
 * is WRONG here and cost a republish loop the first time it shipped. On this network OW1 and OW2 are COPIES OF THE
 * SAME WORLD: they host the same dimension ids ({@code minecraft:overworld}, {@code dmz_ragnarok:dungeon}, and so
 * on), each with its own physical balls at its own coordinates. A per-dimension row keyed on dimension id alone made
 * each twin refuse the other's copy of a dimension it also hosts, so the two overwrote the row with their own view
 * for ever, stable-but-different, never converging.
 *
 * <p>Two consequences follow, and the second matters more than the loop:
 *
 * <ol>
 *   <li><b>Key by OWNING SERVER plus dimension, never dimension alone.</b> Each shard writes only its OWN subtree
 *       (the dimensions it hosts, under its own server id) and carries every other shard's subtree forward verbatim.
 *       Writers are disjoint by owner, so there is no conflict to resolve: a shard never has to decide whether its
 *       copy of a shared dimension id beats a peer's. The row converges to the same union on every shard.</li>
 *   <li><b>Never show a mirror twin's balls.</b> If this shard also hosts a dimension id, the LOCAL balls are the
 *       only truth for a player standing here: the twin's copy sits at coordinates that, in this player's world, hold
 *       nothing, so pointing them there is worse than showing nothing. A carried entry therefore reaches the radar
 *       ONLY when this shard does not host that dimension id at all, i.e. the dimension genuinely lives elsewhere and
 *       travelling to it is possible.</li>
 * </ol>
 *
 * <h2>Dedup: the network view carries only OTHER servers' entries</h2>
 *
 * <p>The local paths and this one stay strictly disjoint, so a player next to a totem never sees it twice. On READ a
 * shard rebuilds only its OWN subtree and {@link #write} skips its own owner id, so a shard never adopts a peer's
 * copy of its own data. On CONSUME ({@link #addRemoteTo}, {@link #snapshotByOwner}, {@link #snapshotTotemsByOwner})
 * this shard's own owner id is skipped and any dimension id this shard hosts a live level of is skipped, so the local
 * radar and readout own everything local and the network view fills in only what genuinely lives elsewhere.
 *
 * <h2>How "lives elsewhere" is decided</h2>
 *
 * <p>The test is a LIVE one: {@code server.getLevel(dimKey) == null}, the same question DMZ itself asks when it
 * builds the packet. If this shard has no live level for that id, no copy exists here and a blip means "go there";
 * if it does, the local copy is authoritative and every remote copy of that id is ignored.
 *
 * <h2>Offline shards do not leave ghost blips</h2>
 *
 * <p>The stored union carries every owner's subtree, so without a guard a shard that crashed would keep showing its
 * last known balls and totems for ever. That is wrong for this feature: a totem is created and looted constantly, so
 * a dead shard's totem blips would send players to graves that may no longer exist. Every consume path therefore
 * filters owners against a cache of LIVE servers, derived from {@link ShardPresence} (which already ages a dead
 * shard's players out via its own heartbeat) and refreshed off the server thread on the read cadence. An offline
 * shard's data is KEPT in the view (so it returns instantly, with no resync, the moment that shard comes back) but
 * produces no blips and no readout lines while the shard is down. The one accepted gap: a shard that is UP but has
 * zero online players is not in the live set, so its balls are briefly not advertised; this self-heals the instant
 * anybody (including the searching player, once they travel there) joins it, at which point the LOCAL radar shows the
 * ball directly.
 *
 * <h2>Publishing, merging and staleness of individual totems</h2>
 *
 * <ul>
 *   <li>The read supplier rebuilds THIS shard's subtree live and leaves every other owner's subtree untouched, then
 *       publishes the whole union. Whoever writes last, the row still holds every owner's subtree.</li>
 *   <li>Each dimension entry carries a STAMP that only moves when that dimension's ball OR totem content changes. The
 *       write consumer skips its own owner id entirely and, for every other owner, adopts a dimension only when its
 *       stamp is newer than the copy held. A dimension's stamps all come from its one owning shard, so the comparison
 *       never crosses clocks and a fresh copy always beats a carried one.</li>
 *   <li>Absence is never deletion. A dimension missing from a payload is kept. A dimension that EMPTIES (its last
 *       loose ball picked up, its last ball-totem looted) is published as an explicit empty entry (a tombstone) by
 *       its owner, which is the only thing that clears it. So a looted totem stops being advertised on the owning
 *       shard's next read pass, network wide.</li>
 * </ul>
 *
 * <h2>Payload size and its bound</h2>
 *
 * <p>Growth is proportional to the number of loose balls plus ball-holding graves across every hosted dimension,
 * summed over every online shard (each carries the union). Loose balls are tiny and naturally bounded (a set is a
 * handful of blocks). Grave totems grow with player deaths and logouts holding a set that nobody has recovered yet,
 * which is normally small but is player-driven and could be hoarded, so it is capped: at most
 * {@link #MAX_POSITIONS_PER_DIM_SET} positions per (dimension, set, kind) are published, and any overflow is LOGGED
 * rather than silently dropped. Offline shards' frozen subtrees stay in the row but do not grow.
 *
 * <h2>Byte determinism</h2>
 *
 * <p>{@code ShardStateSync} republishes on a read hash change, so the serialised bytes must be identical for
 * identical logical content. Every iteration is sorted: owners by id, dimensions by id, sets by id, positions by
 * packed long. The per dimension stamp is held stable while content is unchanged. The live-server filter is applied
 * only on CONSUME, never in {@link #read}, so presence changes never perturb the published bytes.
 *
 * <h2>Threading</h2>
 *
 * <p>All touch points that read game state run on the server thread: the read supplier, the write consumer
 * ({@code server.execute}), and the radar mixin. The maps below are confined to the server thread and need no
 * locking. The only exception is the live-server cache, refreshed by an off-thread {@link ShardExecutor} task and
 * held in a {@code volatile} field, read (never mutated) on the server thread.
 */
public final class CrossShardRadar
{
    private CrossShardRadar()
    {
    }

    /** The state-sync key this feature travels under. */
    public static final String STATE_KEY = "dmz:dragonballs";

    /** At most this many positions per (dimension, set, kind) are published; overflow is logged, not silent. */
    private static final int MAX_POSITIONS_PER_DIM_SET = 64;

    /** One dimension's picture on ONE owning shard: when it last changed, its loose balls and its ball totems, per set. */
    private record OwnedDim(long stamp, TreeMap<String, long[]> loose, TreeMap<String, long[]> totems)
    {
    }

    /** A freshly rebuilt dimension's loose balls and totems, per set, before a stamp is assigned. */
    private record FreshDim(TreeMap<String, long[]> loose, TreeMap<String, long[]> totems)
    {
        static final FreshDim EMPTY = new FreshDim(new TreeMap<>(), new TreeMap<>());
    }

    /**
     * The whole network's picture: owning server id to that owner's dimensions to their ball data. Holds THIS
     * shard's subtree (rebuilt each read, under its own server id) and every remote owner's subtree (carried
     * forward). TreeMaps throughout for a deterministic serialisation order.
     */
    private static final TreeMap<String, TreeMap<String, OwnedDim>> NETWORK_VIEW = new TreeMap<>();

    /** For each hosted dimension, the content hash last seen, so a stamp only bumps on a real change. */
    private static final TreeMap<String, String> HOSTED_CONTENT_HASH = new TreeMap<>();

    /** For each hosted dimension, the stamp currently assigned, reused while content is unchanged. */
    private static final TreeMap<String, Long> HOSTED_STAMP = new TreeMap<>();

    /**
     * Hosted dimensions that have held at least one ball or totem this session. Kept so a dimension that EMPTIES
     * keeps publishing an explicit empty tombstone (rather than dropping out, which absence rules would read as "keep
     * the old balls"). Bounded by the handful of dimensions balls and totems ever appear in.
     */
    private static final TreeSet<String> HOSTED_EVER_HAD_BALLS = new TreeSet<>();

    /** Monotonic per shard, so two content changes inside one millisecond still order and never collide. */
    private static long lastStamp;

    /** How often to refresh the live-server set, in wall-clock milliseconds. */
    private static final long LIVE_REFRESH_INTERVAL_MS = 30_000L;

    /** The server ids with at least one online player, per {@link ShardPresence}. Null until the first refresh. */
    private static volatile Set<String> liveServers = null;

    /** When the live-server set was last refreshed, so the refresh is throttled. */
    private static volatile long liveServersRefreshedAt = 0L;

    /** True while an off-thread refresh is in flight, so overlapping reads do not pile up refreshes. */
    private static volatile boolean liveRefreshInFlight = false;

    // Read: rebuild this shard's own subtree (loose balls and totems, every hosted dimension), carry others.

    /**
     * Serialise the union view (this shard's subtree rebuilt live, remote owners' subtrees carried forward), or null
     * when there is nothing worth publishing. Never mutates DMZ's stored ball data.
     */
    public static CompoundTag read(MinecraftServer server)
    {
        if (server == null)
        {
            return null;
        }
        try
        {
            String thisShard = ShardConfig.get().serverId;

            // Keep the live-server set warm for the consume paths. Off-thread, throttled, never blocks this read.
            maybeRefreshLiveServers();

            // Rebuild the hosted dimensions from live data: loose balls (active + pending) AND grave totems, across
            // EVERY local level so the network view matches what the local sweeps see. Note which dims are hosted.
            TreeMap<String, FreshDim> hostedFresh = new TreeMap<>();
            TreeSet<String> hostedDims = new TreeSet<>();
            int[] dropped = { 0 };
            for (ServerLevel level : server.getAllLevels())
            {
                if (level == null)
                {
                    continue;
                }
                String dimId = level.dimension().location().toString();
                hostedDims.add(dimId);
                DragonBallSavedData data = DragonBallSavedData.get(level);

                // Loose balls, per set. Read EXACT positions straight from the active and pending maps.
                // getAllKnownPositionsForRadar is deliberately avoided here: SU's own radar fuzz mixin rewrites its
                // return, so reading it would ship a doubly fuzzed position. The consumer fuzzes once, matching the
                // hosting shard.
                TreeMap<String, long[]> loose = new TreeMap<>();
                for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets())
                {
                    String setId = set.getId();
                    TreeSet<Long> packed = new TreeSet<>();
                    collect(packed, data.getActiveBalls(setId));
                    collect(packed, data.getPendingBalls(setId));
                    if (!packed.isEmpty())
                    {
                        loose.put(setId, cap(packed, dropped));
                    }
                }

                // Grave totems, per set: one position per grave that holds at least one ball of that set.
                TreeMap<String, TreeSet<Long>> totemPacked = new TreeMap<>();
                for (GraveData grave : GraveStorage.get(level).all())
                {
                    if (grave == null)
                    {
                        continue;
                    }
                    Container container = grave.container();
                    Set<String> seen = new HashSet<>(2);
                    for (int slot = 0; slot < container.getContainerSize(); ++slot)
                    {
                        String setId = DragonBallSets.setIdOf(container.getItem(slot));
                        if (setId == null || !seen.add(setId))
                        {
                            continue;
                        }
                        totemPacked.computeIfAbsent(setId, k -> new TreeSet<>()).add(grave.pos().asLong());
                    }
                }
                TreeMap<String, long[]> totems = new TreeMap<>();
                for (Map.Entry<String, TreeSet<Long>> entry : totemPacked.entrySet())
                {
                    totems.put(entry.getKey(), cap(entry.getValue(), dropped));
                }

                if (!loose.isEmpty() || !totems.isEmpty())
                {
                    hostedFresh.put(dimId, new FreshDim(loose, totems));
                }
            }
            if (dropped[0] > 0)
            {
                LoggingHandler.sulog.warn("[shard] Cross-shard radar dropped {} ball/totem position(s) over the "
                        + "per-dimension-per-set cap of {}; some blips are missing from the network view.",
                        dropped[0], MAX_POSITIONS_PER_DIM_SET);
            }

            // Remember which hosted dimensions have ever held content, so an emptied one still ships a tombstone.
            HOSTED_EVER_HAD_BALLS.addAll(hostedFresh.keySet());

            TreeMap<String, OwnedDim> priorSubtree = NETWORK_VIEW.getOrDefault(thisShard, new TreeMap<>());
            TreeMap<String, OwnedDim> mySubtree = new TreeMap<>();
            for (String dimId : HOSTED_EVER_HAD_BALLS)
            {
                if (!hostedDims.contains(dimId))
                {
                    // Not hosted this pass (a dynamic level unloaded): carry whatever we last published for it, so a
                    // temporarily unloaded dimension's balls do not blink out of the network view.
                    OwnedDim prior = priorSubtree.get(dimId);
                    if (prior != null)
                    {
                        mySubtree.put(dimId, prior);
                    }
                    continue;
                }
                FreshDim fresh = hostedFresh.getOrDefault(dimId, FreshDim.EMPTY);
                String contentHash = hashOf(fresh);
                long stamp;
                if (!contentHash.equals(HOSTED_CONTENT_HASH.get(dimId)))
                {
                    stamp = nextStamp();
                    HOSTED_CONTENT_HASH.put(dimId, contentHash);
                    HOSTED_STAMP.put(dimId, stamp);
                }
                else
                {
                    stamp = HOSTED_STAMP.get(dimId);
                }
                mySubtree.put(dimId, new OwnedDim(stamp, fresh.loose(), fresh.totems()));
            }

            if (mySubtree.isEmpty())
            {
                NETWORK_VIEW.remove(thisShard);
            }
            else
            {
                NETWORK_VIEW.put(thisShard, mySubtree);
            }

            if (NETWORK_VIEW.isEmpty())
            {
                return null; // nothing anywhere yet: do not create an empty row
            }
            return serialise();
        }
        catch (Throwable t)
        {
            // Rule: never crash the mod for a DMZ change. Degrade to the cross-shard radar being off this pass.
            LoggingHandler.sulog.warn("[shard] Cross-shard radar read failed, skipping this pass: {}", t.toString());
            return null;
        }
    }

    // Write: adopt newer copies of every OTHER owner's dimensions.

    /**
     * Fold a peer's union into the local view. Skips this shard's own owner id (rebuilt by {@link #read}); for every
     * other owner, adopts a dimension only when its stamp is newer than the held copy; never deletes by omission.
     * Lets a genuine exception propagate, so ShardStateSync's deferred path retries rather than dropping the change.
     */
    public static void write(MinecraftServer server, CompoundTag tag)
    {
        if (server == null || tag == null)
        {
            return;
        }
        String thisShard = ShardConfig.get().serverId;
        ListTag owners = tag.getList("owners", Tag.TAG_COMPOUND);
        for (int o = 0; o < owners.size(); o++)
        {
            CompoundTag ownerTag = owners.getCompound(o);
            String owner = ownerTag.getString("owner");
            if (owner.isEmpty() || owner.equals(thisShard))
            {
                continue; // our own subtree is authoritative locally and is rebuilt on read, never adopted from a peer
            }
            TreeMap<String, OwnedDim> sub = NETWORK_VIEW.computeIfAbsent(owner, k -> new TreeMap<>());
            ListTag dims = ownerTag.getList("dims", Tag.TAG_COMPOUND);
            for (int i = 0; i < dims.size(); i++)
            {
                CompoundTag dimTag = dims.getCompound(i);
                String dimId = dimTag.getString("dim");
                if (dimId.isEmpty())
                {
                    continue;
                }
                long stamp = dimTag.getLong("stamp");
                OwnedDim existing = sub.get(dimId);
                if (existing != null && stamp <= existing.stamp())
                {
                    continue; // already hold this or a newer copy of this owner's dimension
                }
                TreeMap<String, long[]> loose = readSetMap(dimTag.getList("loose", Tag.TAG_COMPOUND));
                if (loose.isEmpty() && dimTag.contains("sets"))
                {
                    // Back-compat: a peer on an older build wrote loose balls under "sets" with no totems.
                    loose = readSetMap(dimTag.getList("sets", Tag.TAG_COMPOUND));
                }
                TreeMap<String, long[]> totems = readSetMap(dimTag.getList("totems", Tag.TAG_COMPOUND));
                sub.put(dimId, new OwnedDim(stamp, loose, totems));
            }
        }
    }

    // Consume: add only the dimensions that genuinely live on another (live) shard to a packet DMZ built locally.

    /**
     * Add every REMOTE dimension's ball and totem positions to the radar packet. A dimension is remote, and
     * travelling to it is possible, only when it is owned by another LIVE shard AND this shard has no live level of
     * that id: a mirror twin's copy of a dimension this shard also hosts is deliberately ignored, and an offline
     * shard's copy is ignored so it leaves no ghost blip. Positions are fuzzed for the normal sets exactly as local
     * balls and totems are, so a cross-shard position never leaks a precision the local one withholds.
     */
    public static void addRemoteTo(MinecraftServer server, Map<String, List<BlockPos>> positionsBySet,
            List<BlockPos> earthPositions, List<BlockPos> namekPositions)
    {
        if (server == null || positionsBySet == null)
        {
            return;
        }
        String thisShard = ShardConfig.get().serverId;
        for (Map.Entry<String, TreeMap<String, OwnedDim>> ownerEntry : NETWORK_VIEW.entrySet())
        {
            if (ownerEntry.getKey().equals(thisShard) || !isLive(ownerEntry.getKey()))
            {
                continue; // our own balls are already in the packet; an offline shard leaves no ghost blip
            }
            for (Map.Entry<String, OwnedDim> dimEntry : ownerEntry.getValue().entrySet())
            {
                ResourceLocation dimLoc = ResourceLocation.tryParse(dimEntry.getKey());
                if (dimLoc == null)
                {
                    continue;
                }
                ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
                if (server.getLevel(dimKey) != null)
                {
                    // This shard hosts this dimension id: the local copy is authoritative and the twin's balls sit at
                    // coordinates that hold nothing in this player's world, so they must not be shown.
                    continue;
                }
                addSetMap(dimEntry.getValue().loose(), positionsBySet, earthPositions, namekPositions);
                addSetMap(dimEntry.getValue().totems(), positionsBySet, earthPositions, namekPositions);
            }
        }
    }

    private static void addSetMap(TreeMap<String, long[]> bySet, Map<String, List<BlockPos>> positionsBySet,
            List<BlockPos> earthPositions, List<BlockPos> namekPositions)
    {
        for (Map.Entry<String, long[]> setEntry : bySet.entrySet())
        {
            String setId = setEntry.getKey();
            List<BlockPos> exact = unpack(setEntry.getValue());
            if (exact.isEmpty())
            {
                continue;
            }
            List<BlockPos> fuzzed = RadarFuzz.fuzzForRadar(setId, exact);
            positionsBySet.computeIfAbsent(setId, k -> new ArrayList<>()).addAll(fuzzed);
            if (earthPositions != null && "earth".equals(setId))
            {
                earthPositions.addAll(fuzzed);
            }
            if (namekPositions != null && "namek".equals(setId))
            {
                namekPositions.addAll(fuzzed);
            }
        }
    }

    // Read-only snapshots, for the right-click radar readout (dimension AND owning server per ball).

    /**
     * A read-only view of the network's known LOOSE balls: owning server id, then dimension id, then set id, then the
     * EXACT positions that owner holds. Only LIVE owners are included (an offline shard produces no readout lines).
     * The caller fuzzes for display exactly as the radar does. Empty off a live shard network.
     */
    public static Map<String, Map<String, Map<String, List<BlockPos>>>> snapshotByOwner()
    {
        return snapshot(false);
    }

    /**
     * As {@link #snapshotByOwner} but for grave TOTEMS, so the readout can say "in a totem in Overworld (OW1)" for a
     * grave that lives on another shard.
     */
    public static Map<String, Map<String, Map<String, List<BlockPos>>>> snapshotTotemsByOwner()
    {
        return snapshot(true);
    }

    private static Map<String, Map<String, Map<String, List<BlockPos>>>> snapshot(boolean totems)
    {
        Map<String, Map<String, Map<String, List<BlockPos>>>> out = new TreeMap<>();
        for (Map.Entry<String, TreeMap<String, OwnedDim>> ownerEntry : NETWORK_VIEW.entrySet())
        {
            if (!isLive(ownerEntry.getKey()))
            {
                continue; // an offline shard is not advertised in the readout either
            }
            Map<String, Map<String, List<BlockPos>>> byDim = new TreeMap<>();
            for (Map.Entry<String, OwnedDim> dimEntry : ownerEntry.getValue().entrySet())
            {
                TreeMap<String, long[]> source = totems ? dimEntry.getValue().totems() : dimEntry.getValue().loose();
                Map<String, List<BlockPos>> bySet = new TreeMap<>();
                for (Map.Entry<String, long[]> setEntry : source.entrySet())
                {
                    List<BlockPos> positions = unpack(setEntry.getValue());
                    if (!positions.isEmpty())
                    {
                        bySet.put(setEntry.getKey(), positions);
                    }
                }
                if (!bySet.isEmpty())
                {
                    byDim.put(dimEntry.getKey(), bySet);
                }
            }
            if (!byDim.isEmpty())
            {
                out.put(ownerEntry.getKey(), byDim);
            }
        }
        return out;
    }

    // Live-server cache.

    /** True when this owner should be shown: it is us, or the cache is not warm yet, or presence lists it as live. */
    private static boolean isLive(String owner)
    {
        if (owner == null)
        {
            return false;
        }
        if (owner.equals(ShardConfig.get().serverId))
        {
            return true;
        }
        Set<String> live = liveServers;
        // Null means the first refresh has not completed: treat everyone as live rather than blanking the radar; the
        // filter tightens once presence is known, which is at most LIVE_REFRESH_INTERVAL_MS after the network warms.
        return live == null || live.contains(owner);
    }

    /** Refresh the live-server set off the server thread, throttled. A failed query leaves the last good value. */
    private static void maybeRefreshLiveServers()
    {
        long now = System.currentTimeMillis();
        if (liveRefreshInFlight || now - liveServersRefreshedAt < LIVE_REFRESH_INTERVAL_MS)
        {
            return;
        }
        liveRefreshInFlight = true;
        ShardExecutor.submit(() ->
        {
            try
            {
                Set<String> live = new HashSet<>();
                for (ShardPresence.Located located : ShardPresence.all())
                {
                    if (located != null && located.serverId() != null)
                    {
                        live.add(located.serverId());
                    }
                }
                live.add(ShardConfig.get().serverId); // this shard is always live
                liveServers = live;
                liveServersRefreshedAt = System.currentTimeMillis();
            }
            catch (Throwable t)
            {
                // Leave the previous (possibly null) value: a transient query failure must not blank the radar.
                LoggingHandler.sulog.debug("[shard] Cross-shard radar could not refresh live servers: {}",
                        t.toString());
            }
            finally
            {
                liveRefreshInFlight = false;
            }
        });
    }

    // Helpers.

    private static void collect(TreeSet<Long> out, Map<Integer, List<BlockPos>> byStar)
    {
        if (byStar == null)
        {
            return;
        }
        for (List<BlockPos> positions : byStar.values())
        {
            if (positions == null)
            {
                continue;
            }
            for (BlockPos pos : positions)
            {
                if (pos != null)
                {
                    out.add(pos.asLong());
                }
            }
        }
    }

    // Cap a set of packed positions at MAX_POSITIONS_PER_DIM_SET, counting any overflow into dropped[0] so the read
    // pass can log it. The first (lowest packed) positions are kept, which is deterministic for byte stability.
    private static long[] cap(TreeSet<Long> sorted, int[] dropped)
    {
        if (sorted.size() <= MAX_POSITIONS_PER_DIM_SET)
        {
            return toArray(sorted);
        }
        long[] out = new long[MAX_POSITIONS_PER_DIM_SET];
        int i = 0;
        for (long v : sorted)
        {
            if (i >= MAX_POSITIONS_PER_DIM_SET)
            {
                break;
            }
            out[i++] = v;
        }
        dropped[0] += sorted.size() - MAX_POSITIONS_PER_DIM_SET;
        return out;
    }

    private static long[] toArray(TreeSet<Long> sorted)
    {
        long[] out = new long[sorted.size()];
        int i = 0;
        for (long v : sorted)
        {
            out[i++] = v;
        }
        return out;
    }

    private static List<BlockPos> unpack(long[] packed)
    {
        List<BlockPos> out = new ArrayList<>(packed == null ? 0 : packed.length);
        if (packed != null)
        {
            for (long v : packed)
            {
                out.add(BlockPos.of(v));
            }
        }
        return out;
    }

    /** Parse a serialised set-id to positions list ({@code [{set, pos}]}). */
    private static TreeMap<String, long[]> readSetMap(ListTag setList)
    {
        TreeMap<String, long[]> out = new TreeMap<>();
        for (int s = 0; s < setList.size(); s++)
        {
            CompoundTag setTag = setList.getCompound(s);
            String setId = setTag.getString("set");
            if (!setId.isEmpty())
            {
                out.put(setId, setTag.getLongArray("pos"));
            }
        }
        return out;
    }

    /** A deterministic hash of a dimension's loose and totem positions, used only to detect a content change. */
    private static String hashOf(FreshDim fresh)
    {
        StringBuilder sb = new StringBuilder();
        sb.append('L');
        appendSets(sb, fresh.loose());
        sb.append('T');
        appendSets(sb, fresh.totems());
        return sb.toString();
    }

    private static void appendSets(StringBuilder sb, TreeMap<String, long[]> sets)
    {
        for (Map.Entry<String, long[]> e : sets.entrySet())
        {
            sb.append(e.getKey()).append(':');
            for (long v : e.getValue())
            {
                sb.append(v).append(',');
            }
            sb.append(';');
        }
    }

    private static long nextStamp()
    {
        long now = System.currentTimeMillis();
        lastStamp = Math.max(now, lastStamp + 1);
        return lastStamp;
    }

    private static CompoundTag serialise()
    {
        CompoundTag root = new CompoundTag();
        ListTag owners = new ListTag();
        for (Map.Entry<String, TreeMap<String, OwnedDim>> ownerEntry : NETWORK_VIEW.entrySet())
        {
            CompoundTag ownerTag = new CompoundTag();
            ownerTag.putString("owner", ownerEntry.getKey());
            ListTag dims = new ListTag();
            for (Map.Entry<String, OwnedDim> dimEntry : ownerEntry.getValue().entrySet())
            {
                OwnedDim value = dimEntry.getValue();
                CompoundTag dimTag = new CompoundTag();
                dimTag.putString("dim", dimEntry.getKey());
                dimTag.putLong("stamp", value.stamp());
                dimTag.put("loose", writeSetMap(value.loose()));
                dimTag.put("totems", writeSetMap(value.totems()));
                dims.add(dimTag);
            }
            ownerTag.put("dims", dims);
            owners.add(ownerTag);
        }
        root.put("owners", owners);
        return root;
    }

    private static ListTag writeSetMap(TreeMap<String, long[]> bySet)
    {
        ListTag out = new ListTag();
        for (Map.Entry<String, long[]> set : bySet.entrySet())
        {
            CompoundTag setTag = new CompoundTag();
            setTag.putString("set", set.getKey());
            setTag.put("pos", new LongArrayTag(set.getValue()));
            out.add(setTag);
        }
        return out;
    }
}
