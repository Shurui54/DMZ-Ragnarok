package net.shurui.shuruisutilities.npcregion;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.AABB;

// named spawn zone of one or more Boxes (cuboids, or freeform outlines clicked point by point) in a single
// dimension, periodically spawns customizable NPCs (each an NpcSpawnConfig). selections drawn on Xaero's World Map
// (right-drag for a box, draw mode for an outline), can be added
// to an existing region or start a new one so shapes are composite. no protection flags (that's Region),
// purely a spawn zone. persisted as JSON by NpcRegionManager (plain fields for Gson).
public class NpcRegion
{
    // one selection. plain fields for Gson. a plain cuboid, or (with outline set) a freeform shape drawn point by
    // point on the map: then min/max X/Z are the outline's bounding box (so every coarse reader still gets a safe
    // superset) and only containsColumn/contains are exact.
    public static class Box
    {
        public int minX, minY, minZ, maxX, maxY, maxZ;

        /**
         * Optional freeform outline, interleaved {@code [x0, z0, x1, z1, ...]} block columns in drawing order (see
         * {@link net.shurui.shuruisutilities.util.PolygonXZ}). NULL on every cuboid selection, and Gson omits a null
         * field, so a save without freeform shapes stays byte-identical and an older build reading a newer save simply
         * sees the bounding box. Y stays {@code minY..maxY} as for a cuboid.
         */
        public int[] outline;

        public Box() {}

        /** A freeform selection from a validated outline, spanning {@code minY..maxY}. */
        public static Box outline(int[] pts, int minY, int maxY)
        {
            int[] b = net.shurui.shuruisutilities.util.PolygonXZ.bounds(pts);
            Box box = new Box(b[0], minY, b[1], b[2], maxY, b[3]);
            box.outline = pts.clone();
            return box;
        }

        public boolean isOutline()
        {
            return outline != null && net.shurui.shuruisutilities.util.PolygonXZ.count(outline) >= 3;
        }

        /** Whether the block column belongs to this selection (bounding box first, then the outline). */
        public boolean containsColumn(int x, int z)
        {
            if (x < minX || x > maxX || z < minZ || z > maxZ)
                return false;
            return !isOutline() || net.shurui.shuruisutilities.util.PolygonXZ.containsColumn(outline, x, z);
        }

        /**
         * A random block column inside this selection: uniform over the bounding box, then (for an outline)
         * rejection sampled with a retry cap. Null only when an outline rejects every try, which needs a sliver of a
         * shape; callers treat it like any other failed attempt.
         */
        public int[] randomColumn(net.minecraft.util.RandomSource random)
        {
            for (int i = 0; i < 24; i++)
            {
                int x = minX + random.nextInt(Math.max(1, maxX - minX + 1));
                int z = minZ + random.nextInt(Math.max(1, maxZ - minZ + 1));
                if (!isOutline() || net.shurui.shuruisutilities.util.PolygonXZ.containsColumn(outline, x, z))
                    return new int[] {x, z};
                if (!isOutline())
                    break;
            }
            return null;
        }

        /** A fixed point inside the selection (its middle for a cuboid), for teleports and last-resort spots. */
        public double[] interiorPoint()
        {
            if (isOutline())
                return net.shurui.shuruisutilities.util.PolygonXZ.interiorPoint(outline);
            return new double[] {(minX + maxX) / 2.0 + 0.5, (minZ + maxZ) / 2.0 + 0.5};
        }

        public Box(int x1, int y1, int z1, int x2, int y2, int z2)
        {
            minX = Math.min(x1, x2);
            minY = Math.min(y1, y2);
            minZ = Math.min(z1, z2);
            maxX = Math.max(x1, x2);
            maxY = Math.max(y1, y2);
            maxZ = Math.max(z1, z2);
        }

        public boolean contains(double x, double y, double z)
        {
            if (!(x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1))
                return false;
            return !isOutline() || net.shurui.shuruisutilities.util.PolygonXZ.contains(outline, x, z);
        }

        // world-space AABB, inclusive of the max block
        public AABB aabb()
        {
            return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        }

        public long volume()
        {
            return areaXZ() * (maxY - minY + 1);
        }

        // footprint in blocks: the bounding box for a cuboid, the enclosed area for an outline
        public long areaXZ()
        {
            if (isOutline())
                return net.shurui.shuruisutilities.util.PolygonXZ.area(outline);
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    public String name = "";
    public String dim = "minecraft:overworld";

    // Admin flag: an eventOnly region runs ONLY while a timed event owns it (the event's eventRegions list),
    // and is dark otherwise. A Boolean, not a primitive, on purpose: Gson OMITS a null field, so every existing
    // region's JSON stays byte-identical to a pre-event save and the per-region ShardNpcRegions sync never
    // republishes a normal region. sanitize() folds an explicit false back to null so only "true" is ever
    // written. Read through isEventOnly(). Keyless the region system treats an eventOnly region as dark.
    public Boolean eventOnly;

    public boolean isEventOnly()
    {
        return Boolean.TRUE.equals(eventOnly);
    }

    // non-empty after sanitize()
    public List<Box> boxes = new ArrayList<>();

    // legacy single-box bounds pre multi-selection. only read from old JSON, migrated into boxes by
    // sanitize(). never written by new code.
    public int minX, minY, minZ, maxX, maxY, maxZ;

    public String title = ""; // blank falls back to name, supports & codes
    public String description = "";
    public String difficulty = "";
    public boolean showTitle = true;
    public boolean showHud = true;
    public String color = ""; // #RRGGBB overlay fill, blank = faint green

    public List<NpcSpawnConfig> npcs = new ArrayList<>();

    public boolean crateBossEnabled = true;
    // null = auto: region's apex NPC (highest max health) with doubled combat stats, see resolveCrateBoss()
    public NpcSpawnConfig crateBoss;
    // the ONLY loot source for this region's drops. empty = no airdrop (no global fallback).
    public List<CustomDrop> crateLoot = new ArrayList<>();

    public boolean airdropEnabled = false; // own timer, independent of other regions
    public int airdropMinIntervalMinutes = 30; // actual wait random in [min, max]
    public int airdropMaxIntervalMinutes = 60;
    public String airdropAnnouncement = ""; // blank falls back to global default
    // vanilla/modded sound id. null resets to default toast sound; "" is VALID and means silent landing.
    public String airdropSound = DEFAULT_AIRDROP_SOUND;
    // when on, a drop with no dry ground but a fluid surface may rest one block above the top fluid block
    // (still needs two clear blocks above). off = dry ground only. not in the editor UI, set in JSON.
    public boolean airdropAllowWater = false;

    // fallback when airdropSound is null
    public static final String DEFAULT_AIRDROP_SOUND = "minecraft:ui.toast.challenge_complete";

    // legacy single-config field pre multi-NPC. only in old JSON, migrated into npcs by sanitize().
    public NpcSpawnConfig config;

    // per-region TP falloff by killer level (Feature B). 0 disables it here. when set, a killer whose DMZ
    // level exceeds this earns less TP for kills in the region, falling linearly to a 20% floor over
    // tpFalloffRange levels. GSON plain field, no codec change.
    public int tpFalloffLevel = 0;

    // levels above tpFalloffLevel over which TP drops linearly to the 20% floor. at
    // tpFalloffLevel+tpFalloffRange (or more) the killer gets the floor.
    public int tpFalloffRange = 20;

    // Per-region Z orb settings (the private Z orb feature). NULL means this region was NEVER configured for Z
    // orbs, and Gson OMITS a null field, so an existing region's JSON stays byte-identical to a pre-Z-orb save and
    // a keyless server (which never writes this) leaves it null forever. Only the Z orb editor, gated by the key,
    // ever sets it. sanitize() only touches it when it is non-null.
    public net.shurui.shuruisutilities.zorb.ZOrbConfig zorbs;

    public NpcRegion() {}

    public NpcRegion(String name, String dim, int x1, int y1, int z1, int x2, int y2, int z2)
    {
        this.name = name;
        this.dim = dim;
        boxes.add(new Box(x1, y1, z1, x2, y2, z2));
    }

    public boolean contains(String dim, double x, double y, double z)
    {
        if (!this.dim.equals(dim))
            return false;
        for (Box b : boxes)
            if (b.contains(x, y, z))
                return true;
        return false;
    }

    // first selection, anchor for teleports and the editor's Y-bounds display
    public Box primary()
    {
        return boxes.get(0);
    }

    // crate-boss config for an airdrop: the configured one, else the region's apex NPC with doubled combat
    // stats. null when disabled or the region has no NPCs to derive from.
    public NpcSpawnConfig resolveCrateBoss()
    {
        if (!crateBossEnabled)
            return null;
        if (crateBoss != null)
            return crateBoss;
        return defaultCrateBoss(npcs);
    }

    // apex (highest max health) NPC with doubled combat stats, null for an empty list
    public static NpcSpawnConfig defaultCrateBoss(List<NpcSpawnConfig> npcs)
    {
        NpcSpawnConfig apex = null;
        if (npcs != null)
            for (NpcSpawnConfig c : npcs)
                if (c != null && (apex == null || c.maxHealth > apex.maxHealth))
                    apex = c;
        if (apex == null)
            return null;
        NpcSpawnConfig boss = apex.copy();
        boss.maxHealth *= 2.0f;
        boss.attackDamage *= 2.0f;
        boss.kiPower *= 2.0f;
        boss.defense *= 2.0f;
        boss.maxSpawns = 1;
        return boss;
    }

    // one AABB over every selection, coarse entity query (tag filters do the exact work)
    public AABB enclosingBox()
    {
        Box first = primary();
        int nX = first.minX, nY = first.minY, nZ = first.minZ, xX = first.maxX, xY = first.maxY, xZ = first.maxZ;
        for (Box b : boxes)
        {
            nX = Math.min(nX, b.minX);
            nY = Math.min(nY, b.minY);
            nZ = Math.min(nZ, b.minZ);
            xX = Math.max(xX, b.maxX);
            xY = Math.max(xY, b.maxY);
            xZ = Math.max(xZ, b.maxZ);
        }
        return new AABB(nX, nY, nZ, xX + 1.0, xY + 1.0, xZ + 1.0);
    }

    // total volume across selections (overlaps double-counted, informational only)
    public long volume()
    {
        long v = 0;
        for (Box b : boxes)
            v += b.volume();
        return v;
    }

    // title when set, else name
    public String displayTitle()
    {
        return title != null && !title.isBlank() ? title : name;
    }

    // migrate legacy saves (single-box bounds, single config) and normalise every spawn definition
    public void sanitize()
    {
        if (boxes == null)
            boxes = new ArrayList<>();
        if (boxes.isEmpty())
            boxes.add(new Box(minX, minY, minZ, maxX, maxY, maxZ)); // pre-multi-selection save
        boxes.removeIf(b -> b == null);
        for (Box b : boxes)
        {
            if (b.outline == null)
                continue;
            // a hand-edited outline too short to enclose anything falls back to its box; otherwise the box is
            // re-derived from the outline so the coarse readers and the exact test always agree
            if (net.shurui.shuruisutilities.util.PolygonXZ.count(b.outline) < 3)
            {
                b.outline = null;
                continue;
            }
            int[] bb = net.shurui.shuruisutilities.util.PolygonXZ.bounds(b.outline);
            b.minX = bb[0];
            b.minZ = bb[1];
            b.maxX = bb[2];
            b.maxZ = bb[3];
        }
        if (boxes.isEmpty())
            boxes.add(new Box(minX, minY, minZ, maxX, maxY, maxZ));
        if (npcs == null)
            npcs = new ArrayList<>();
        if (config != null)
        {
            npcs.add(config); // old single-NPC save -> first entry of the list
            config = null;
        }
        for (NpcSpawnConfig c : npcs)
            if (c != null)
                c.sanitize();
        npcs.removeIf(c -> c == null);
        if (crateBoss != null)
            crateBoss.sanitize();
        if (crateLoot == null)
            crateLoot = new ArrayList<>();
        crateLoot.removeIf(d -> d == null);
        airdropMinIntervalMinutes = Math.max(1, airdropMinIntervalMinutes);
        airdropMaxIntervalMinutes = Math.max(airdropMinIntervalMinutes, airdropMaxIntervalMinutes);
        if (airdropAnnouncement == null)
            airdropAnnouncement = "";
        if (airdropSound == null)
            airdropSound = DEFAULT_AIRDROP_SOUND; // "" is intentional (silent) and preserved
        if (color == null || !color.matches("#[0-9a-fA-F]{6}"))
            color = "";
        // Feature B defaults for legacy saves (absent fields deserialize as 0)
        if (tpFalloffLevel < 0)
            tpFalloffLevel = 0;
        if (tpFalloffRange <= 0)
            tpFalloffRange = 20;
        // Z orbs: only normalise a config that actually exists; leave a never-configured region's field null so
        // its JSON is unchanged.
        if (zorbs != null)
            zorbs.sanitize();
        // Fold an explicit "false" back to null so a normal region never writes the field (byte-identical JSON).
        if (Boolean.FALSE.equals(eventOnly))
            eventOnly = null;
    }
}
