package net.shurui.shuruisutilities.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A WorldGuard-style cuboid protection region: a named box in one dimension with a priority, owner/member
 * lists and a set of flags. Higher-priority regions win where they overlap. Flags are {@code allow}/{@code
 * deny} (absent = unset, i.e. the region doesn't override that behaviour). Persisted as JSON by
 * {@link RegionManager}; kept as plain fields so Gson can (de)serialize it directly.
 */
public class Region
{
    public String name = "";
    /** Dimension id, e.g. {@code minecraft:overworld}. */
    public String dim = "minecraft:overworld";
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int priority = 0;

    /** Optional parent region name (WorldGuard-style inheritance): flags/members unset here fall through to it. */
    public String parent = null;

    /** Player UUIDs (as strings). Owners can manage the region; members + owners bypass the build restriction. */
    public List<String> owners = new ArrayList<>();
    public List<String> members = new ArrayList<>();

    /** flag id -> {@code "allow"} / {@code "deny"}. */
    public Map<String, String> flags = new HashMap<>();

    public Region() {}

    public Region(String name, String dim, int x1, int y1, int z1, int x2, int y2, int z2)
    {
        this.name = name;
        this.dim = dim;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    /**
     * Additional, possibly DISCONNECTED areas belonging to this same region, added with
     * {@code /serverclaim add <region>}. One claim can therefore cover several separate builds that share one
     * set of owners, members, flags and priority.
     *
     * <p>The {@code minX..maxZ} fields above stay meaningful and become the BOUNDING BOX of every area once this
     * list is non-empty. That is deliberate and is what keeps this change small: roughly seventy places read the
     * six fields directly (map highlighting, the chunk purge's protection sweep, teleport helpers, the editor
     * screens), and a bounding box is the SAFE answer for every one of them. A coarse reader now tests a
     * superset of the claim, so protection over-covers rather than under-covers, and nothing silently stops
     * protecting ground it used to. Only {@link #contains} is precise.
     *
     * <p>Empty on every region that has never had an area added, which is what makes old JSON load unchanged.
     */
    public List<Box> areas = new ArrayList<>();

    /** One cuboid. Plain fields so Gson handles it with no adapter, like the rest of this class. */
    public static class Box
    {
        public int minX, minY, minZ, maxX, maxY, maxZ;

        public Box() {}

        public Box(int x1, int y1, int z1, int x2, int y2, int z2)
        {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
        }

        public boolean contains(double x, double y, double z)
        {
            return x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1;
        }

        public long volume()
        {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    public boolean contains(String dim, double x, double y, double z)
    {
        if (!this.dim.equals(dim))
        {
            return false;
        }
        // Bounding box first: it is one comparison against fields already in cache and it rejects the common
        // case (a player nowhere near this claim) before touching the list at all.
        if (!(x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1))
        {
            return false;
        }
        if (areas == null || areas.isEmpty())
        {
            return true; // single-area claim: the bounding box IS the claim
        }
        for (Box b : areas)
        {
            if (b != null && b.contains(x, y, z))
            {
                return true;
            }
        }
        // Inside the bounding box but in none of the areas: the gap between two disconnected parts.
        return false;
    }

    /**
     * Append an area and grow the bounding box to cover it.
     *
     * <p>The FIRST call also captures the region's original box as an area, because until now that box was the
     * claim itself and was never in the list. Skipping that would make the original ground stop being protected
     * the instant a second area was added, which is the opposite of what adding one is for.
     */
    public void addArea(int x1, int y1, int z1, int x2, int y2, int z2)
    {
        if (areas == null)
        {
            areas = new ArrayList<>();
        }
        if (areas.isEmpty())
        {
            areas.add(new Box(minX, minY, minZ, maxX, maxY, maxZ));
        }
        areas.add(new Box(x1, y1, z1, x2, y2, z2));
        recomputeBounds();
    }

    /** Re-derive {@code minX..maxZ} as the bounding box of every area. No-op for a single-area claim. */
    public void recomputeBounds()
    {
        if (areas == null || areas.isEmpty())
        {
            return;
        }
        Box first = areas.get(0);
        int nMinX = first.minX, nMinY = first.minY, nMinZ = first.minZ;
        int nMaxX = first.maxX, nMaxY = first.maxY, nMaxZ = first.maxZ;
        for (Box b : areas)
        {
            if (b == null)
            {
                continue;
            }
            nMinX = Math.min(nMinX, b.minX);
            nMinY = Math.min(nMinY, b.minY);
            nMinZ = Math.min(nMinZ, b.minZ);
            nMaxX = Math.max(nMaxX, b.maxX);
            nMaxY = Math.max(nMaxY, b.maxY);
            nMaxZ = Math.max(nMaxZ, b.maxZ);
        }
        minX = nMinX; minY = nMinY; minZ = nMinZ;
        maxX = nMaxX; maxY = nMaxY; maxZ = nMaxZ;
    }

    /** How many separate areas this claim covers. One for a plain claim. */
    public int areaCount()
    {
        return areas == null || areas.isEmpty() ? 1 : areas.size();
    }

    /** {@code "allow"} / {@code "deny"} / {@code null} (unset). */
    public String getFlag(String flag)
    {
        return flags.get(flag);
    }

    public boolean isMember(UUID uuid)
    {
        String s = uuid.toString();
        return owners.contains(s) || members.contains(s);
    }

    /**
     * The volume actually claimed, which for a multi-area claim is the SUM of its areas, not its bounding box.
     * The gap between two disconnected parts is not claimed and must not be charged for or reported as if it is.
     */
    public long volume()
    {
        if (areas == null || areas.isEmpty())
        {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
        long total = 0;
        for (Box b : areas)
        {
            if (b != null)
            {
                total += b.volume();
            }
        }
        return total;
    }
}
