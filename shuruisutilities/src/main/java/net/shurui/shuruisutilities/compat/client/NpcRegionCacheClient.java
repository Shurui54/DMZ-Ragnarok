package net.shurui.shuruisutilities.compat.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// client snapshot of the server's NPC regions, synced by PacketNpcRegionSync. drives the Xaero map outlines,
// the shift+hover info lookup, and the player HUD / entry titles. lightweight summary only, client thread only.
// two visibility layers: map outline/hover is manager-only (canManage()), HUD data is for everyone.
// a region can have several selections (boxes); the server sends one entry per selection sharing the region
// name, grouped back by name here.
public final class NpcRegionCacheClient
{
    // one region selection: box footprint + display info + what it spawns + reward ranges
    public static final class Entry
    {
        public final String name, dim, entity;
        public final int minX, minZ, maxX, maxZ;
        public final int count, tpMin, tpMax, balMin, balMax;
        public final int minY, maxY;
        public final String title, description, difficulty;
        public final boolean showTitle, showHud;
        // Xaero-packed 0xRRGGBBAA fill (alpha baked in); default faint green
        public int packedFill = DEFAULT_FILL;

        public static final int DEFAULT_FILL = 0x33DD3328;
        private static final int FILL_ALPHA = 0x28;

        public Entry(String name, String dim, int minX, int minZ, int maxX, int maxZ, String entity, int count,
                     int tpMin, int tpMax, int balMin, int balMax,
                     int minY, int maxY, String title, String description, String difficulty,
                     boolean showTitle, boolean showHud)
        {
            this.name = name;
            this.dim = dim;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.entity = entity;
            this.count = count;
            this.tpMin = tpMin;
            this.tpMax = tpMax;
            this.balMin = balMin;
            this.balMax = balMax;
            this.minY = minY;
            this.maxY = maxY;
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.difficulty = difficulty == null ? "" : difficulty;
            this.showTitle = showTitle;
            this.showHud = showHud;
        }

        // apply the region's #RRGGBB overlay color; blank/invalid keeps the default
        public void setColor(String hex)
        {
            if (hex == null || !hex.matches("#[0-9a-fA-F]{6}"))
                return;
            int rgb = Integer.parseInt(hex.substring(1), 16);
            packedFill = (rgb << 8) | FILL_ALPHA;
        }

        boolean contains(int bx, int bz)
        {
            return bx >= minX && bx <= maxX && bz >= minZ && bz <= maxZ;
        }

        public boolean contains(double x, double y, double z)
        {
            return x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1;
        }

        // display title when set, else the region name
        public String displayTitle()
        {
            return title.isBlank() ? name : title;
        }

        // every selection of a region shares its name, so name IS the region
        public String groupKey()
        {
            return dim + "|" + name;
        }
    }

    private static final Map<String, List<Entry>> byDim = new HashMap<>();
    private static volatile int version;
    // map outline + hover overlay shown? toggled with the region-overlay keybind
    private static volatile boolean overlayEnabled = true;
    // server-granted region-manage flag; outlines/hover only draw when set
    private static volatile boolean canManage;

    private NpcRegionCacheClient() {}

    public static int version()
    {
        return version;
    }

    public static boolean overlayEnabled()
    {
        return overlayEnabled;
    }

    // may this player manage regions (server-synced)? gates the map outline/hover
    public static boolean canManage()
    {
        return canManage;
    }

    // NPC regions are private: keyless the map outlines and hover never draw (ClientGate, the synced answer)
    public static boolean outlinesVisible()
    {
        return overlayEnabled && canManage && net.shurui.dev.sdu.api.ClientGate.key();
    }

    // flip the overlay + bump version so Xaero re-renders the affected regions; returns new state
    public static boolean toggleOverlay()
    {
        overlayEnabled = !overlayEnabled;
        version++;
        return overlayEnabled;
    }

    // replace the whole snapshot (from a full sync)
    public static synchronized void replaceAll(List<Entry> entries, boolean manage)
    {
        canManage = manage;
        byDim.clear();
        for (Entry e : entries)
            byDim.computeIfAbsent(e.dim, k -> new ArrayList<>()).add(e);
        version++;
    }

    // drop the whole snapshot on disconnect, so one server's region outlines never draw on the next
    public static synchronized void clear()
    {
        byDim.clear();
        canManage = false;
        version++;
    }

    public static boolean hasAnyIn(String dim)
    {
        List<Entry> list = byDim.get(dim);
        return list != null && !list.isEmpty();
    }

    // all selections in dim (shared read-only view, client thread only)
    public static synchronized List<Entry> entriesIn(String dim)
    {
        List<Entry> list = byDim.get(dim);
        return list == null ? List.of() : list;
    }

    // any selection in dim intersecting this chunk? (map highlighter candidate)
    public static boolean chunkIntersectsAny(String dim, int cx, int cz)
    {
        int minBX = cx << 4, minBZ = cz << 4, maxBX = minBX + 15, maxBZ = minBZ + 15;
        for (Entry e : entriesIn(dim))
            if (e.minX <= maxBX && e.maxX >= minBX && e.minZ <= maxBZ && e.maxZ >= minBZ)
                return true;
        return false;
    }

    // distinct region names in dim, sorted, for the add-selection popup
    public static synchronized List<String> namesIn(String dim)
    {
        List<Entry> list = byDim.get(dim);
        if (list == null)
            return List.of();
        return list.stream().map(e -> e.name).distinct().sorted(String::compareToIgnoreCase).toList();
    }

    // region whose box covers this block column in dim, or null. smallest area wins.
    public static Entry regionAt(String dim, int bx, int bz)
    {
        List<Entry> list = byDim.get(dim);
        if (list == null)
            return null;
        Entry best = null;
        long bestArea = Long.MAX_VALUE;
        for (Entry e : list)
            if (e.contains(bx, bz))
            {
                long area = (long) (e.maxX - e.minX + 1) * (e.maxZ - e.minZ + 1);
                if (best == null || area < bestArea) // smallest covering region wins
                {
                    best = e;
                    bestArea = area;
                }
            }
        return best;
    }

    // region containing this exact pos (3D), or null. smallest volume wins.
    public static Entry regionAt(String dim, double x, double y, double z)
    {
        List<Entry> list = byDim.get(dim);
        if (list == null)
            return null;
        Entry best = null;
        long bestVolume = Long.MAX_VALUE;
        for (Entry e : list)
            if (e.contains(x, y, z))
            {
                long volume = (long) (e.maxX - e.minX + 1) * (e.maxZ - e.minZ + 1) * (long) (e.maxY - e.minY + 1);
                if (best == null || volume < bestVolume)
                {
                    best = e;
                    bestVolume = volume;
                }
            }
        return best;
    }

}
