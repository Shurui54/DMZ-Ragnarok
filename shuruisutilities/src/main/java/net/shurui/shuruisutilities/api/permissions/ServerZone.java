package net.shurui.shuruisutilities.api.permissions;

import java.util.*;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.commons.selections.AreaBase;
import net.shurui.shuruisutilities.commons.selections.Point;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.data.v2.Loadable;
import com.google.gson.annotations.Expose;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;

// every player on the server. second-lowest priority, above RootZone.
public class ServerZone extends Zone implements Loadable
{

    @Expose(serialize = false)
    private RootZone rootZone;

    @Expose(serialize = false)
    private Map<Integer, Zone> zones = new HashMap<>();

    private Map<String, WorldZone> worldZones = new HashMap<>();

    private Map<UserIdent, Set<String>> playerGroups = new HashMap<>();

    private int maxZoneID;

    @Expose(serialize = false)
    private Set<UserIdent> knownPlayers = new HashSet<>();

    // ---- getZonesAt spatial index (runtime only, never serialized) ----------------------------------------
    // getZonesAt is called twice per player move (ZonedPermissionHelper.playerMoveEvent) and used to scan EVERY
    // area zone in the dimension, running shape.contains (a sqrt for ellipsoid/cylinder) per zone. This coarse
    // per-chunk index narrows the scan to the area zones whose enclosing bounds cover the point's chunk, exactly
    // like RegionManager. Results, including ORDER, are byte-for-byte what the old full scan produced: buckets are
    // filled by iterating getAreaZones() in order (so each bucket keeps that order) and shape.contains(point) can
    // only be true inside the zone's AABB, which is inside its bucketed chunk range, so the bucket is a superset
    // of every zone the full scan would have kept.
    //
    // zonesVersion is bumped on every structural change routed through addZone / removeZone / rebuildZonesMap /
    // addWorldZone. Geometry changes (setArea replaces the AreaBase, setShape, setPriority, and any reorder from
    // sortAreaZones) do NOT route through those, so the per-dimension index is ALSO validated against a snapshot
    // of each area zone's identity, AreaBase reference, shape and priority; AreaBase is only ever replaced (never
    // mutated in place: redefine() has no callers), so a reference compare detects a resize. Any mismatch rebuilds.
    @Expose(serialize = false)
    private transient long zonesVersion = 0;

    @Expose(serialize = false)
    private transient Map<String, AreaIndex> areaIndexByDim = new HashMap<>();

    // An area zone spanning more than this many chunks is not bucketed (it would flood the index); instead the
    // whole dimension falls back to the old full scan, which is never worse than the pre-index behaviour.
    private static final long MAX_BUCKETS_PER_ZONE = 4096;

    private static final class AreaIndex
    {
        long version;
        AreaZone[] snapZones;
        Object[] snapAreas;
        Object[] snapShapes;
        int[] snapPriorities;
        boolean fullScan;
        Map<Long, List<AreaZone>> buckets;

        boolean matches(Collection<AreaZone> areas)
        {
            if (snapZones.length != areas.size())
                return false;
            int i = 0;
            for (AreaZone z : areas)
            {
                if (snapZones[i] != z || snapAreas[i] != z.getArea() || snapShapes[i] != z.getShape()
                        || snapPriorities[i] != z.getPriority())
                    return false;
                i++;
            }
            return true;
        }
    }

    private static long chunkKey(int chunkX, int chunkZ)
    {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    private void markZonesChanged()
    {
        zonesVersion++;
    }

    private AreaIndex buildAreaIndex(Collection<AreaZone> areas)
    {
        AreaIndex idx = new AreaIndex();
        idx.version = zonesVersion;
        int n = areas.size();
        idx.snapZones = new AreaZone[n];
        idx.snapAreas = new Object[n];
        idx.snapShapes = new Object[n];
        idx.snapPriorities = new int[n];
        Map<Long, List<AreaZone>> buckets = new HashMap<>();
        boolean fullScan = false;
        int i = 0;
        for (AreaZone z : areas)
        {
            idx.snapZones[i] = z;
            AreaBase a = z.getArea();
            idx.snapAreas[i] = a;
            idx.snapShapes[i] = z.getShape();
            idx.snapPriorities[i] = z.getPriority();
            i++;
            if (fullScan)
                continue;
            Point low = a.getLowPoint();
            Point high = a.getHighPoint();
            int cMinX = low.getX() >> 4, cMaxX = high.getX() >> 4;
            int cMinZ = low.getZ() >> 4, cMaxZ = high.getZ() >> 4;
            long span = (long) (cMaxX - cMinX + 1) * (long) (cMaxZ - cMinZ + 1);
            if (span > MAX_BUCKETS_PER_ZONE)
            {
                fullScan = true;
                continue;
            }
            for (int cx = cMinX; cx <= cMaxX; cx++)
                for (int cz = cMinZ; cz <= cMaxZ; cz++)
                    buckets.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(z);
        }
        idx.fullScan = fullScan;
        idx.buckets = fullScan ? null : buckets;
        return idx;
    }

    // Area zones that could contain this point, in getAreaZones() order. A superset of what isInZone will keep,
    // so the caller still filters by isInZone and gets an identical result.
    private Collection<AreaZone> candidateAreaZones(WorldZone w, WorldPoint point)
    {
        Collection<AreaZone> areas = w.getAreaZones();
        if (areas.isEmpty())
            return areas;
        if (areaIndexByDim == null)
            areaIndexByDim = new HashMap<>();
        String dim = w.getDimensionID();
        AreaIndex idx = areaIndexByDim.get(dim);
        if (idx == null || idx.version != zonesVersion || !idx.matches(areas))
        {
            idx = buildAreaIndex(areas);
            areaIndexByDim.put(dim, idx);
        }
        if (idx.fullScan)
            return areas;
        List<AreaZone> bucket = idx.buckets.get(chunkKey(point.getX() >> 4, point.getZ() >> 4));
        return bucket == null ? Collections.emptyList() : bucket;
    }

    public ServerZone()
    {
        super(1);
        APIRegistry.getSUEventBus().post(new PermissionEvent.Initialize(this));
        addZone(this);
    }

    public ServerZone(RootZone rootZone)
    {
        this();
        this.maxZoneID = 1;
        this.rootZone = rootZone;
        this.rootZone.setServerZone(this);
        addZone(this.rootZone);
    }

    @Override
    public void afterLoad()
    {
        for (WorldZone zone : worldZones.values())
        {
            zone.serverZone = this;
            zone.afterLoad();
        }
    }

    @Override
    public boolean isInZone(WorldPoint point)
    {
        return true;
    }

    @Override
    public boolean isInZone(WorldArea point)
    {
        return true;
    }

    @Override
    public boolean isPartOfZone(WorldArea point)
    {
        return true;
    }

    @Override
    public String getName()
    {
        return "_SERVER_";
    }

    @Override
    public Zone getParent()
    {
        return rootZone;
    }

    @Override
    public ServerZone getServerZone()
    {
        return this;
    }

    void setRootZone(RootZone rootZone)
    {
        this.rootZone = rootZone;
        addZone(this.rootZone);
    }

    public RootZone getRootZone()
    {
        return rootZone;
    }

    public int getMaxZoneID()
    {
        return maxZoneID;
    }

    public int nextZoneID()
    {
        return ++maxZoneID;
    }

    public void setMaxZoneId(int maxId)
    {
        this.maxZoneID = maxId;
    }

    public Map<String, WorldZone> getWorldZones()
    {
        return worldZones;
    }

    public void addWorldZone(WorldZone zone)
    {
        worldZones.put(zone.getDimensionID(), zone);
        addZone(zone);
        setDirty();
    }

    public WorldZone getWorldZone(String registryKey)
    {
        WorldZone zone = getWorldZones().get(registryKey);
        if (zone == null)
        {
            zone = new WorldZone(getServerZone(), registryKey);
        }
        return zone;
    }

    public WorldZone getWorldZone(Level world)
    {
        return getWorldZone(world.dimension().location().toString());
    }

    public WorldZone getWorldZone(LevelAccessor world)
    {
        return getWorldZone((ServerLevel) world);

    }

    public Set<String> getGroups()
    {
        return getGroupPermissions().keySet();
    }

    public boolean groupExists(String name)
    {
        return getGroupPermissions().containsKey(name);
    }

    public boolean createGroup(String name)
    {
        if (APIRegistry.getSUEventBus().post(new PermissionEvent.Group.Create(this, name)))
            return false;
        setGroupPermission(name, SUPermissions.GROUP, true);
        setGroupPermissionProperty(name, SUPermissions.GROUP_PRIORITY,
                Integer.toString(SUPermissions.GROUP_PRIORITY_DEFAULT));
        setDirty();
        return true;
    }

    // drops the group's perms from every zone and its membership from every player (server-level playerGroups
    // + per-zone PLAYER_GROUPS). system groups can't be deleted. false if missing/system/event-cancelled.
    public boolean deleteGroup(String name)
    {
        if (name == null || !groupExists(name) || isSystemGroup(name))
            return false;
        if (APIRegistry.getSUEventBus().post(new PermissionEvent.Group.Delete(this, name)))
            return false;

        // Remove the group's permissions from every zone (server, world, area)
        for (Zone zone : getZones())
            zone.groupPermissions.remove(name);

        // Strip membership from every player, server-wide and per-zone
        for (Set<String> groups : playerGroups.values())
            groups.remove(name);
        for (Zone zone : getZones())
            for (UserIdent ident : new ArrayList<>(zone.playerPermissions.keySet()))
            {
                Set<String> stored = zone.getStoredPlayerGroups(ident);
                if (stored.remove(name))
                {
                    if (stored.isEmpty())
                        zone.clearPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);
                    else
                        zone.setPlayerPermissionProperty(ident, SUPermissions.PLAYER_GROUPS,
                                StringUtils.join(stored, ","));
                }
            }

        setDirty();
        return true;
    }

    // keeps perms/properties/memberships. system groups can't be renamed; new name must be free.
    public boolean renameGroup(String oldName, String newName)
    {
        if (oldName == null || newName == null)
            return false;
        newName = newName.trim();
        if (newName.isEmpty() || oldName.equals(newName))
            return false;
        if (!groupExists(oldName) || isSystemGroup(oldName) || isSystemGroup(newName) || groupExists(newName))
            return false;

        // Move the permission map under the new key in every zone
        for (Zone zone : getZones())
        {
            PermissionList perms = zone.groupPermissions.remove(oldName);
            if (perms != null)
                zone.groupPermissions.put(newName, perms);
        }

        // Update membership references, server-wide and per-zone
        for (Set<String> groups : playerGroups.values())
            if (groups.remove(oldName))
                groups.add(newName);
        for (Zone zone : getZones())
            for (UserIdent ident : new ArrayList<>(zone.playerPermissions.keySet()))
            {
                Set<String> stored = zone.getStoredPlayerGroups(ident);
                if (stored.remove(oldName))
                {
                    stored.add(newName);
                    zone.setPlayerPermissionProperty(ident, SUPermissions.PLAYER_GROUPS,
                            StringUtils.join(stored, ","));
                }
            }

        setDirty();
        return true;
    }

    public Set<String> getIncludedGroups(String group)
    {
        Set<String> result = new HashSet<>();
        String groupsStr = getGroupPermission(group, SUPermissions.GROUP_INCLUDES);
        if (groupsStr != null && !groupsStr.isEmpty())
            for (String g : groupsStr.replaceAll(" ", "").split(","))
                if (!g.isEmpty())
                    result.add(g);
        return result;
    }

    public void groupIncludeAdd(String group, String otherGroup)
    {
        Set<String> groups = getIncludedGroups(group);
        groups.add(otherGroup);
        APIRegistry.perms.setGroupPermissionProperty(group, SUPermissions.GROUP_INCLUDES,
                StringUtils.join(groups, ","));
    }

    public void groupIncludeRemove(String group, String otherGroup)
    {
        Set<String> groups = getIncludedGroups(group);
        groups.remove(otherGroup);
        APIRegistry.perms.setGroupPermissionProperty(group, SUPermissions.GROUP_INCLUDES,
                StringUtils.join(groups, ","));
    }

    public Set<String> getParentedGroups(String group)
    {
        Set<String> result = new HashSet<>();
        String groupsStr = getGroupPermission(group, SUPermissions.GROUP_PARENTS);
        if (groupsStr != null && !groupsStr.isEmpty())
            for (String g : groupsStr.replaceAll(" ", "").split(","))
                if (!g.isEmpty())
                    result.add(g);
        return result;
    }

    public void groupParentAdd(String group, String otherGroup)
    {
        Set<String> groups = getIncludedGroups(group);
        groups.add(otherGroup);
        APIRegistry.perms.setGroupPermissionProperty(group, SUPermissions.GROUP_PARENTS, StringUtils.join(groups, ","));
    }

    public void groupParentRemove(String group, String otherGroup)
    {
        Set<String> groups = getIncludedGroups(group);
        groups.remove(otherGroup);
        APIRegistry.perms.setGroupPermissionProperty(group, SUPermissions.GROUP_PARENTS, StringUtils.join(groups, ","));
    }

    @Override
    public boolean addPlayerToGroup(UserIdent ident, String group)
    {
        registerPlayer(ident);
        Set<String> groupSet = playerGroups.computeIfAbsent(ident, k -> new HashSet<>());
        if (!groupSet.contains(group))
        {
            if (APIRegistry.getSUEventBus().post(new PermissionEvent.User.ModifyGroups(this, ident,
                    PermissionEvent.User.ModifyGroups.Action.ADD, group)))
                return false;
            groupSet.add(group);
            // Membership lives in this map, not in a permission, so nothing else marks it. Without this a rank
            // given to somebody sat unwritten until an unrelated change happened to trigger the next save, and
            // a restart in between simply lost it. The remove path has always done this.
            setDirty();
        }
        return true;
    }

    @Override
    public boolean removePlayerFromGroup(UserIdent ident, String group)
    {
        registerPlayer(ident);
        if (APIRegistry.getSUEventBus().post(new PermissionEvent.User.ModifyGroups(this, ident,
                PermissionEvent.User.ModifyGroups.Action.REMOVE, group)))
            return false;
        Set<String> groupSet = playerGroups.get(ident);
        if (groupSet != null)
            groupSet.remove(group);
        setDirty();
        return true;
    }

    public Map<UserIdent, Set<String>> getPlayerGroups()
    {
        return playerGroups;
    }

    public Map<String, Set<UserIdent>> getGroupPlayers()
    {
        Map<String, Set<UserIdent>> groupPlayers = new HashMap<>();
        for (Entry<UserIdent, Set<String>> player : playerGroups.entrySet())
        {
            for (String group : player.getValue())
            {
                Set<UserIdent> players = groupPlayers.computeIfAbsent(group, k -> new HashSet<>());
                players.add(player.getKey());
            }
        }
        return groupPlayers;
    }

    @Override
    public SortedSet<GroupEntry> getStoredPlayerGroupEntries(UserIdent ident)
    {
        registerPlayer(ident);
        Set<String> pgs = playerGroups.get(ident);
        SortedSet<GroupEntry> result = new TreeSet<>();
        if (pgs != null)
            for (String group : pgs)
                result.add(new GroupEntry(this, group));
        return result;
    }

    public SortedSet<GroupEntry> getAdditionalPlayerGroups(UserIdent ident, WorldPoint point)
    {
        SortedSet<GroupEntry> result = getStoredPlayerGroupEntries(ident);
        if (ident != null)
        {
            // Include special groups
            if (ServerLifecycleHooks.getCurrentServer().getPlayerList().isOp(ident.getGameProfile()))
            {
                result.add(new GroupEntry(this, GROUP_OPERATORS));
            }
            if (ident.isFakePlayer())
            {
                result.add(new GroupEntry(this, GROUP_FAKEPLAYERS));
            }
            if (result.isEmpty() && ident.isPlayer())
                result.add(new GroupEntry(this, GROUP_GUESTS));
            if (!ident.isFakePlayer())
                result.add(new GroupEntry(GROUP_PLAYERS, 1, 1));
            if (ident.isNpc())
                result.add(new GroupEntry(GROUP_NPC, 1, 1));

            ServerPlayer player = ident.getPlayerMP();
            if (player != null && player.getAbilities() != null)
                switch (player.gameMode.getGameModeForPlayer())
                {
                case ADVENTURE:
                    result.add(new GroupEntry(this, GROUP_ADVENTURE));
                    break;
                case CREATIVE:
                    result.add(new GroupEntry(this, GROUP_CREATIVE));
                    break;
                default:
                    break;
                }
        }
        // Check groups added through zones
        if (point == null && ident != null && ident.hasPlayer())
            point = new WorldPoint(ident.getPlayer());
        if (ident != null && point != null)
            for (Zone z : getZonesAt(point))
                if (!(z instanceof ServerZone))
                    result.addAll(z.getStoredPlayerGroupEntries(ident));
        result.add(new GroupEntry(GROUP_DEFAULT, -1, -1));
        return result;
    }

    public SortedSet<GroupEntry> includeGroups(SortedSet<GroupEntry> groups)
    {
        // Get included groups
        Set<String> checkedGroups = new HashSet<>();
        boolean addedGroup;
        do
        {
            addedGroup = false;
            for (GroupEntry existingGroup : new ArrayList<>(groups))
            {
                // Check if group was already checked for inclusion
                if (!checkedGroups.add(existingGroup.getGroup()))
                    continue;
                String p = getGroupPermission(existingGroup.getGroup(), SUPermissions.GROUP_INCLUDES);
                if (p != null)
                {
                    for (String group : p.replaceAll(" ", "").split(","))
                        if (!group.isEmpty())
                            addedGroup |= groups.add(new GroupEntry(this, group));
                }

                p = getGroupPermission(existingGroup.getGroup(), SUPermissions.GROUP_PARENTS);
                if (p != null)
                {
                    for (String group : p.replaceAll(" ", "").split(","))
                        if (!group.isEmpty())
                            addedGroup |= groups.add(new GroupEntry(this, group, existingGroup.getPriority()));
                }
            }
        }
        while (addedGroup);

        return groups;
    }

    public SortedSet<GroupEntry> getPlayerGroups(UserIdent ident, WorldPoint point)
    {
        return includeGroups(getAdditionalPlayerGroups(ident, point));
    }

    public SortedSet<GroupEntry> getPlayerGroups(UserIdent ident)
    {
        return getPlayerGroups(ident, null);
    }

    public String getPrimaryPlayerGroup(UserIdent ident, WorldPoint point)
    {
        Iterator<GroupEntry> it = getPlayerGroups(ident, point).iterator();
        if (it.hasNext())
            return it.next().getGroup();
        else
            return null;
    }

    public String getPrimaryPlayerGroup(UserIdent ident)
    {
        return getPrimaryPlayerGroup(ident, null);
    }

    public void addZone(Zone zone)
    {
        zones.put(zone.getId(), zone);
        markZonesChanged();
    }

    public boolean removeZone(Zone zone)
    {
        boolean removed = zones.remove(zone.getId()) != null;
        if (removed)
            markZonesChanged();
        return removed;
    }

    public void rebuildZonesMap()
    {
        markZonesChanged();
        zones.clear();
        addZone(getRootZone());
        addZone(this);
        for (WorldZone worldZone : worldZones.values())
        {
            addZone(worldZone);
            for (AreaZone areaZone : worldZone.getAreaZones())
            {
                addZone(areaZone);
            }
        }
    }

    public Map<Integer, Zone> getZoneMap()
    {
        return zones;
    }

    public Collection<Zone> getZones()
    {
        return zones.values();
    }

    public List<Zone> getZonesAt(WorldPoint worldPoint)
    {
        WorldZone w = getWorldZone(worldPoint.getDimension());
        List<Zone> result = new ArrayList<>();
        for (AreaZone zone : candidateAreaZones(w, worldPoint))
        {
            if (zone.isInZone(worldPoint))
            {
                result.add(zone);
            }
        }
        result.add(w);
        result.add(this);
        result.add(rootZone);
        return result;
    }

    public List<Zone> getZonesAt(UserIdent ident)
    {
        if (ident == null)
        {
            return new ArrayList<>();
        }
        else if (ident.hasPlayer())
        {
            return getZonesAt(new WorldPoint(ident.getPlayer()));
        }
        else
        {
            ArrayList<Zone> result = new ArrayList<>();
            result.add(this);
            return result;
        }
    }

    public Zone getZoneAt(WorldPoint worldPoint)
    {
        List<Zone> zones = getZonesAt(worldPoint);
        return zones.isEmpty() ? null : zones.get(0);
    }

    public List<AreaZone> getAreaZonesAt(WorldPoint worldPoint)
    {
        WorldZone w = getWorldZone(worldPoint.getDimension());
        List<AreaZone> result = new ArrayList<>();
        for (AreaZone zone : w.getAreaZones())
            if (zone.isInZone(worldPoint))
                result.add(zone);
        return result;
    }

    public AreaZone getAreaZoneAt(WorldPoint worldPoint)
    {
        List<AreaZone> zones = getAreaZonesAt(worldPoint);
        return zones.isEmpty() ? null : zones.get(0);
    }

    public void registerPlayer(UserIdent ident)
    {
        if (ident == null || knownPlayers.contains(ident))
            return;
        knownPlayers.add(ident);
        PermissionList map = getOrCreatePlayerPermissions(ident);
        if (map.isEmpty())
            map.put(SUPermissions.PLAYER_KNOWN, PERMISSION_TRUE);
    }

    public Set<UserIdent> getKnownPlayers()
    {
        return knownPlayers;
    }

    public String getPermission(Collection<Zone> zones, UserIdent ident, List<String> groups, String permissionNode,
            WorldPoint point)
    {
        // Build node list
        List<String> nodes = new ArrayList<>();
        nodes.add(permissionNode);
        String[] nodeParts = permissionNode.split("\\.");
        for (int i = nodeParts.length; i > 0; i--)
        {
            StringBuilder node = new StringBuilder();
            for (int j = 0; j < i; j++)
            {
                node.append(nodeParts[j]).append(".");
            }
            nodes.add(node + PERMISSION_ASTERIX);
        }
        nodes.add(PERMISSION_ASTERIX);

        PermissionCheckEvent event = postPermissionCheckEvent(zones, ident, groups, nodes, false);
        if (event.result != null)
            return event.result;

        // Check player permissions
        if (ident != null)
        {
            for (Zone zone : zones)
            {
                for (String node : nodes)
                {
                    String result = zone.getPlayerPermission(ident, node);
                    if (result != null)
                    {
                        if (rootZone.permissionDebugger != null)
                            rootZone.permissionDebugger.debugPermission(zone, ident, null, permissionNode, node, result,
                                    point, false);
                        return result;
                    }
                }
            }
        }

        // Check group permissions
        // Add default group
        if (groups != null)
        {
            // Lowest order: group hierarchy
            // (e.g. ADMIN, MEMBER, _OPS_, _ALL_)
            for (String group : groups)
            {
                // Second order: zones
                // (e.g. area, world, server, root)
                for (Zone zone : zones)
                {
                    // First order: nodes
                    // (e.g. fe.commands.time, fe.commands.time.*, fe.commands.*, fe.*, *)
                    for (String node : nodes)
                    {
                        String result = zone.getGroupPermission(group, node);
                        if (result != null)
                        {
                            if (rootZone.permissionDebugger != null)
                                rootZone.permissionDebugger.debugPermission(zone, ident, group, permissionNode, node,
                                        result, point, true);
                            return result;
                        }
                    }
                }
            }
        }
        if (rootZone.permissionDebugger != null)
            rootZone.permissionDebugger.debugPermission(null, ident, GROUP_DEFAULT, permissionNode, permissionNode,
                    PERMISSION_TRUE, point, true);
        return null;
    }

    public String getPermissionProperty(Collection<Zone> zones, UserIdent ident, List<String> groups, String node,
            WorldPoint point)
    {
        PermissionCheckEvent event = postPermissionCheckEvent(zones, ident, groups, Collections.singletonList(node), true);
        if (event.result != null)
            return event.result;

        // Check player permissions
        if (ident != null)
        {
            for (Zone zone : zones)
            {
                String result = zone.getPlayerPermission(ident, node);
                if (result != null)
                {
                    if (rootZone.permissionDebugger != null)
                        rootZone.permissionDebugger.debugPermission(zone, ident, null, node, node, result, point,
                                false);
                    return result;
                }
            }
        }

        // Check group permissions
        // Add default group
        if (groups != null)
        {
            // Lowest order: group hierarchy
            // (e.g. ADMIN, MEMBER, _OPS_, _ALL_)
            for (String group : groups)
            {
                // Second order: zones
                // (e.g. area, world, server, root)
                for (Zone zone : zones)
                {
                    // First order: nodes
                    // (e.g. fe.commands.time, fe.commands.time.*, fe.commands.*, fe.*, *)
                    String result = zone.getGroupPermission(group, node);
                    if (result != null)
                    {
                        if (rootZone.permissionDebugger != null)
                            rootZone.permissionDebugger.debugPermission(zone, ident, group, node, node, result, point,
                                    true);
                        return result;
                    }
                }
            }
        }
        if (rootZone.permissionDebugger != null)
            rootZone.permissionDebugger.debugPermission(null, ident, GROUP_DEFAULT, node, node, "null", point, true);
        return null;
    }

    public static PermissionCheckEvent postPermissionCheckEvent(Collection<Zone> zones, UserIdent ident,
            List<String> groups, List<String> nodes, boolean isProperty)
    {
        PermissionCheckEvent event = new PermissionCheckEvent(ident, zones, groups, nodes, isProperty);
        APIRegistry.SU_EVENTBUS.post(event);
        return event;
    }

    public static interface PermissionDebugger
    {

        void debugPermission(Zone zone, UserIdent ident, String group, String permissionNode, String node, String value,
                WorldPoint point, boolean isGroupPermission);

    }

}
