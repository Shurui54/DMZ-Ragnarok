package net.shurui.shuruisutilities.regions;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Stores the {@link Region}s and answers spatial queries used by {@link RegionEventHandler}: which regions
 * cover a point (highest priority first), the effective value of a flag there, and whether a player is a
 * member of the governing region. Regions persist to {@code <SUdir>/regions.json}.
 */
public class RegionManager
{
    /**
     * The one region store. It lives in core (S12) because public terrain regen and the world-flag mixins read it on
     * every server; core's {@link RegionEngine} loads it at each server start, and only the Ragnarok Key's Regions
     * module edits it.
     */
    private static final RegionManager INSTANCE = new RegionManager();

    public static RegionManager instance()
    {
        return INSTANCE;
    }

    public static class RegionData
    {
        public Map<String, Region> regions = new LinkedHashMap<>();
    }

    private final Map<String, Region> regions = new LinkedHashMap<>();

    /**
     * Coarse spatial index: {@code dim -> (chunkKey -> regions overlapping that chunk)}. Built lazily from the
     * regions' enclosing bounds (a region spanning N chunks appears in all N buckets). Rebuilt from scratch on
     * any mutation ({@link #put}/{@link #delete}/{@link #load}). {@code null} means "not built yet"; queries fall
     * back to a full scan when it's null. Bucketing is by enclosing AABB, so false positives are possible (the
     * exact {@link Region#contains} check still runs per candidate) but false negatives never occur.
     */
    private Map<String, Map<Long, List<Region>>> chunkIndex = null;

    private static File saveFile()
    {
        return new File(ShuruisUtilities.getSUDirectory(), "regions.json");
    }

    public void load()
    {
        regions.clear();
        RegionData data = DataManager.load(RegionData.class, saveFile());
        if (data != null && data.regions != null)
            data.regions.forEach((k, v) -> { if (v != null) { v.name = k; regions.put(k.toLowerCase(Locale.ROOT), v); } });
        chunkIndex = null; // rebuilt lazily on next query
        LoggingHandler.sulog.info("[Regions] Loaded {} region(s)", regions.size());
    }

    public void save()
    {
        RegionData data = new RegionData();
        data.regions.putAll(regions);
        DataManager.save(data, saveFile());
        // Every mutation (put / delete / rename and the several commands that edit a Region in place then call
        // save()) funnels through here, so this is the one place cross shard publish has to hook. It is a no op
        // unless the shard system is on, and it never fires for a change this server is itself applying FROM the
        // network. See ShardRegions / ShardRegionSync.
        net.shurui.shuruisutilities.api.key.ShardHooks.get().regionsSavedLocally();
    }

    public Region get(String name)
    {
        return name == null ? null : regions.get(name.toLowerCase(Locale.ROOT));
    }

    public java.util.Collection<Region> all()
    {
        return new ArrayList<>(regions.values());
    }

    public List<String> getNames()
    {
        List<String> names = new ArrayList<>();
        for (Region r : regions.values())
            names.add(r.name);
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public boolean exists(String name)
    {
        return get(name) != null;
    }

    // lets hot world-tick hooks bail before any allocation/lookup
    public boolean isEmpty()
    {
        return regions.isEmpty();
    }

    public void put(Region region)
    {
        regions.put(region.name.toLowerCase(Locale.ROOT), region);
        chunkIndex = null; // bounds set / replaced: invalidate the spatial index
        save();
    }

    public boolean delete(String name)
    {
        boolean removed = regions.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed)
        {
            chunkIndex = null; // invalidate the spatial index
            save();
        }
        return removed;
    }

    /**
     * Rename a region in place, preserving everything attached to it (bounds, owners, members, flags,
     * priority, extra areas). The name is BOTH this store's map key (lower-cased) and the region's {@code name}
     * field, so both move together. Any CHILD region whose {@code parent} pointed at the old name is repointed
     * to the new one in the same pass, so no region is left inheriting from a name that no longer exists.
     *
     * <p>Everything happens on the in-memory map and is then written by a single {@link #save()}, so a crash
     * cannot leave the store holding two copies or none: either the old JSON is on disk (nothing changed) or the
     * new JSON is (rename complete). Returns {@code false} when the source is missing or the target key is
     * already taken; a case-only change (same key) is allowed. The caller validates blank / invalid names.
     */
    public boolean rename(String oldName, String newName)
    {
        String oldKey = oldName == null ? "" : oldName.toLowerCase(Locale.ROOT);
        String newKey = newName == null ? "" : newName.toLowerCase(Locale.ROOT);
        Region r = regions.get(oldKey);
        if (r == null)
            return false;
        if (!newKey.equals(oldKey) && regions.containsKey(newKey))
            return false;
        regions.remove(oldKey);
        r.name = newName;
        regions.put(newKey, r);
        // Repoint every child that inherited from the old name (case-insensitive, matching how parents resolve).
        for (Region other : regions.values())
            if (other.parent != null && other.parent.equalsIgnoreCase(oldName))
                other.parent = newName;
        chunkIndex = null; // the region set changed identity: invalidate the spatial index
        save();
        return true;
    }

    /** Pack a chunk XZ into a long key (same layout as {@link net.minecraft.world.level.ChunkPos#asLong}). */
    private static long chunkKey(int chunkX, int chunkZ)
    {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    /** Build (once) the coarse chunk-bucket index from every region's enclosing bounds. */
    private Map<String, Map<Long, List<Region>>> index()
    {
        Map<String, Map<Long, List<Region>>> idx = chunkIndex;
        if (idx != null)
            return idx;
        idx = new java.util.HashMap<>();
        for (Region r : regions.values())
        {
            Map<Long, List<Region>> byChunk = idx.computeIfAbsent(r.dim, d -> new java.util.HashMap<>());
            int cMinX = r.minX >> 4, cMaxX = r.maxX >> 4;
            int cMinZ = r.minZ >> 4, cMaxZ = r.maxZ >> 4;
            for (int cx = cMinX; cx <= cMaxX; cx++)
                for (int cz = cMinZ; cz <= cMaxZ; cz++)
                    byChunk.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(r);
        }
        chunkIndex = idx;
        return idx;
    }

    /** Candidate regions whose enclosing bounds cover this position's chunk (a superset; still filter by contains). */
    private List<Region> candidatesAt(String dim, double x, double z)
    {
        Map<Long, List<Region>> byChunk = index().get(dim);
        if (byChunk == null)
            return List.of();
        List<Region> bucket = byChunk.get(chunkKey(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4));
        return bucket == null ? List.of() : bucket;
    }

    /** Regions covering the point, highest priority first. */
    public List<Region> regionsAt(String dim, double x, double y, double z)
    {
        List<Region> out = new ArrayList<>();
        for (Region r : candidatesAt(dim, x, z))
            if (r.contains(dim, x, y, z))
                out.add(r);
        out.sort(Comparator.comparingInt((Region r) -> r.priority).reversed());
        return out;
    }

    /** The governing (highest-priority) region at a point, or null. */
    public Region highestAt(String dim, double x, double y, double z)
    {
        Region best = null;
        for (Region r : candidatesAt(dim, x, z))
            if (r.contains(dim, x, y, z) && (best == null || r.priority > best.priority))
                best = r;
        return best;
    }

    /**
     * Effective flag value at a point: the highest-priority region that sets it (directly or via its parent
     * chain) wins. {@code null} = unset everywhere.
     */
    public String flagAt(String dim, double x, double y, double z, String flag)
    {
        return flagFrom(regionsAt(dim, x, y, z), flag);
    }

    /**
     * Effective flag value given an already-resolved, priority-sorted region list (see {@link #regionsAt}).
     * Lets a caller do a single spatial scan and then read many flags without re-scanning. Reuses the same
     * parent-chain inheritance walk as {@link #flagAt}. {@code null} = unset in all the given regions.
     */
    public String flagFrom(List<Region> sorted, String flag)
    {
        for (Region r : sorted)
        {
            String v = inheritedFlag(r, flag);
            if (v != null)
                return v;
        }
        return null;
    }

    /** A region's flag value, falling through its {@code parent} chain (guards against cycles). */
    public String inheritedFlag(Region r, String flag)
    {
        int guard = 0;
        while (r != null && guard++ < 32)
        {
            String v = r.getFlag(flag);
            if (v != null)
                return v;
            r = (r.parent == null || r.parent.isBlank()) ? null : get(r.parent);
        }
        return null;
    }

    /** True if any region covers this point (i.e. it is inside a protected region at all). */
    public boolean isProtected(String dim, double x, double y, double z)
    {
        for (Region r : candidatesAt(dim, x, z))
            if (r.contains(dim, x, y, z))
                return true;
        return false;
    }

    /** Whether the player is a member/owner of the governing region at a point (false if unprotected). */
    public boolean isMemberAt(String dim, double x, double y, double z, UUID uuid)
    {
        Region r = highestAt(dim, x, y, z);
        return r != null && r.isMember(uuid);
    }
}
