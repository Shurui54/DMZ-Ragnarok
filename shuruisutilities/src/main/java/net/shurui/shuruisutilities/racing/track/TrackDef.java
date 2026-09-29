package net.shurui.shuruisutilities.racing.track;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.shurui.shuruisutilities.racing.physics.RaceDriveParams;
import net.shurui.shuruisutilities.racing.tuning.RaceTuningDto;

/**
 * A full race-track definition: the node graph plus every build and race parameter an operator can set. Pure common
 * data with a stable {@code formatVersion} ({@link #FORMAT_VERSION}); it round-trips byte-identically through
 * {@link TrackCodec} (the JSON on disk is {@code <world>/SUData/Racing/Tracks/<id>.json}, LOCAL, never shard-synced).
 *
 * <p>The surface set (blocks a racer may drive on without the off-road penalty) ALWAYS contains
 * {@code race_boost_pad} and {@code race_finish_line}, because those blocks are placed as part of the track itself;
 * {@link #surfaceIds()} guarantees that regardless of what an operator adds or removes.
 *
 * <p>R1 exercises the node graph, {@link #startNode}, {@link #defaultWidth}, {@link #autoGateSpacing} and the surface
 * set (via {@link TrackGeometry} and {@link TrackValidator}). The build fields (surface / edge / wall blocks, grid,
 * boost pads, item points) are the wire contract the R2 builder and the wand fill in; they carry sane defaults now.
 */
public final class TrackDef
{
    /** The one on-disk format this codec reads and writes. Bump only with a migration. */
    public static final int FORMAT_VERSION = 1;

    /** The two blocks a track always accepts as surface, because the track places them itself. */
    public static final String ALWAYS_SURFACE_BOOST_PAD = "dmz_ragnarok:race_boost_pad";
    public static final String ALWAYS_SURFACE_FINISH_LINE = "dmz_ragnarok:race_finish_line";

    public int formatVersion = FORMAT_VERSION;

    /** Track id (the file base name) and human-facing name. */
    public String id = "";
    public String name = "";

    /** The shard that owns this track's terrain (a race lives on that shard). Empty until the track is built. */
    public String serverId = "";

    /** The dimension the track lives in. */
    public String dimension = "minecraft:overworld";

    /** Laps in a race on this track. */
    public int laps = 3;

    /** Default road half-to-half width, in blocks, for nodes that do not set their own. */
    public double defaultWidth = 8.0;

    /** Extra blocks that count as on-track surface (the two always-surface blocks are added on read). */
    public final List<String> surfaceBlocks = new ArrayList<>();

    /** Top-speed multiplier when the block under the bike is not in the surface set (unless boosting). */
    public double offroadMult = 0.55;

    /** Rescue when the racer strays more than this past the road edge (blocks) for the rescue window. */
    public double rescueDistance = 6.0;

    /** Rescue when the racer falls this far below the nearest sample's y. */
    public int fallDepth = 8;

    /** Blocks the R2 builder lays: the driving surface, the edge curb (+ optional alternating), the wall. */
    public String buildSurfaceBlock = "minecraft:smooth_stone";
    public String edgeBlock = "minecraft:polished_andesite";
    public String edgeBlockAlt = "";
    public String wallBlock = "minecraft:smooth_stone";

    /** Wall height in blocks and headroom cleared above the surface. */
    public int wallHeight = 2;
    public int headroom = 3;

    /** How a projectile / racer bounces: off the DEFINED road edge, off solid surface, or both. */
    public String bounceMode = "BOTH";

    /** Auto-gate spacing along the centreline arc length, in blocks (in addition to checkpoint-node gates). */
    public int autoGateSpacing = 24;

    /** The start / finish node id (must be on the main cycle). {@code -1} until the first node is added. */
    public int startNode = -1;

    /** Grid layout: AUTO derives slots from the start gate, MANUAL uses {@link #gridSlots}. */
    public String gridMode = "AUTO";
    public double gridSpacing = 3.0;

    /** Manual grid slots (each {@code {x, y, z}}), used when {@link #gridMode} is MANUAL. */
    public final List<double[]> gridSlots = new ArrayList<>();

    /** Placed boost pads (also written as blocks by the builder). */
    public final List<BoostPad> boostPads = new ArrayList<>();

    /** Item-box spawn points (also written as spawner blocks that register by track id). */
    public final List<ItemPoint> itemPoints = new ArrayList<>();

    /** The control-point graph, in insertion order. */
    public final List<TrackNode> nodes = new ArrayList<>();

    /**
     * Optional per-track PHYSICS override. {@code null} (the default) means "fall back to the server-wide tuning".
     * When set, a race on this track drives on these numbers instead of the global {@code RaceTuningStore} drive
     * params. Emitted in the track JSON only when present, so an existing track file stays byte-identical.
     */
    public RaceDriveParams driveOverride;

    /**
     * Optional per-track item-box ODDS override, shape {@code [kind][bucket]}. {@code null} (the default) means
     * "fall back to the server-wide tuning grid". Emitted only when present.
     */
    public int[][] oddsOverride;

    public TrackDef() {}

    public TrackDef(String id)
    {
        this.id = id;
    }

    /** A placed boost pad: position plus the yaw it faces (arrow direction). */
    public static final class BoostPad
    {
        public double x;
        public double y;
        public double z;
        public float facing;

        public BoostPad() {}

        public BoostPad(double x, double y, double z, float facing)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.facing = facing;
        }
    }

    /** An item-box spawn point. */
    public static final class ItemPoint
    {
        public double x;
        public double y;
        public double z;

        public ItemPoint() {}

        public ItemPoint(double x, double y, double z)
        {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * The physics this track actually races on: the per-track {@link #driveOverride} when set, otherwise the
     * server-wide {@code global} tuning (a defensive copy either way, so a race never mutates the stored params).
     */
    public RaceDriveParams effectiveDrive(RaceDriveParams global)
    {
        RaceDriveParams base = driveOverride != null ? driveOverride : (global != null ? global : new RaceDriveParams());
        return base.copy();
    }

    /**
     * The item-box odds grid this track actually rolls on: the per-track {@link #oddsOverride} when set, otherwise
     * the server-wide {@code global} grid. Always returned clamped to the canonical shape.
     */
    public int[][] effectiveOdds(int[][] global)
    {
        int[][] base = oddsOverride != null ? oddsOverride : (global != null ? global : RaceTuningDto.defaultOdds());
        return RaceTuningDto.copyOdds(base);
    }

    /** The effective surface set: the operator's blocks plus the two always-surface blocks, de-duplicated. */
    public Set<String> surfaceIds()
    {
        Set<String> out = new LinkedHashSet<>(surfaceBlocks);
        out.add(ALWAYS_SURFACE_BOOST_PAD);
        out.add(ALWAYS_SURFACE_FINISH_LINE);
        return out;
    }

    /** The node with this id, or null. */
    public TrackNode node(int nodeId)
    {
        for (TrackNode n : nodes)
            if (n.id == nodeId)
                return n;
        return null;
    }

    /** The next free node id (max existing + 1, or 0). Never reuses a removed id within a session. */
    public int nextNodeId()
    {
        int max = -1;
        for (TrackNode n : nodes)
            max = Math.max(max, n.id);
        return max + 1;
    }

    /** Remove a node and every edge that referenced it. Clears {@link #startNode} if it pointed at that node. */
    public boolean removeNode(int nodeId)
    {
        boolean removed = nodes.removeIf(n -> n.id == nodeId);
        if (!removed)
            return false;
        for (TrackNode n : nodes)
            n.next.removeIf(t -> t == nodeId);
        if (startNode == nodeId)
            startNode = nodes.isEmpty() ? -1 : nodes.get(0).id;
        return true;
    }
}
