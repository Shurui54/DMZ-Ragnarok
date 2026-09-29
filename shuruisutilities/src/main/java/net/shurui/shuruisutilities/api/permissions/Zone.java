package net.shurui.shuruisutilities.api.permissions;

import java.util.*;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.UserIdent.UserIdentInvalidatedEvent;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;

import net.minecraft.world.entity.player.Player;

// permissions live in a fixed-level tree; priority follows the level.
// RootZone > ServerZone > WorldZone > AreaZone
public abstract class Zone
{

    public static final String GROUP_DEFAULT = "_ALL_";
    public static final String GROUP_GUESTS = "_GUESTS_";
    public static final String GROUP_PLAYERS = "_PLAYERS_";
    public static final String GROUP_NPC = "_NPC_";
    public static final String GROUP_OPERATORS = "_OPS_";
    public static final String GROUP_FAKEPLAYERS = "_FAKEPLAYERS_";
    public static final String GROUP_CREATIVE = "_CREATIVE_";
    public static final String GROUP_ADVENTURE = "_ADVENTURE_";

    // _NAME_ groups (_ALL_, _OPS_, ...) are internal infra; hidden from the GUIs so admins only see real groups
    public static boolean isSystemGroup(String group)
    {
        return group != null && group.length() >= 2 && group.startsWith("_") && group.endsWith("_");
    }

    // hidden = every _NAME_ group except _ALL_ (everyone) and _OPS_ (operators), which admins actually edit
    public static boolean isHiddenGroup(String group)
    {
        return isSystemGroup(group) && !GROUP_DEFAULT.equals(group) && !GROUP_OPERATORS.equals(group);
    }

    public static final String PERMISSION_ASTERIX = "*";
    public static final String PERMISSION_FALSE = "false";
    public static final String PERMISSION_TRUE = "true";
    public static final String ALL_PERMS = '.' + PERMISSION_ASTERIX;

    public static class PermissionList extends HashMap<String, String>
    {
        private static final long serialVersionUID = 1L;

        public List<String> toList()
        {
            List<String> list = new ArrayList<>();
            for (Map.Entry<String, String> perm : this.entrySet())
            {
                if (perm.getValue() == null)
                    continue;
                if (perm.getValue().equals(PERMISSION_TRUE))
                {
                    list.add(perm.getKey());
                }
                else if (perm.getValue().equals(PERMISSION_FALSE))
                {
                    list.add("-" + perm.getKey());
                }
                else
                {
                    list.add(perm.getKey() + "=" + perm.getValue());
                }
            }
            Collections.sort(list);
            return list;
        }

        public static PermissionList fromList(List<String> fromList)
        {
            PermissionList list = new PermissionList();
            for (String permission : fromList)
            {
                String[] permParts = permission.split("=");
                if (permParts.length == 2)
                    list.put(permParts[0], permParts[1]);
                else if (permParts.length == 1)
                {
                    if (permission.startsWith("-"))
                        list.put(permission.substring(1, permission.length()), PERMISSION_FALSE);
                    else
                        list.put(permission, PERMISSION_TRUE);
                }
            }
            return list;
        }

        public PermissionList()
        {
        }

        public PermissionList(Map<? extends String, ? extends String> clone)
        {
            super(clone);
        }
    }

    public static final Comparator<Object> permissionComparator = new Comparator<Object>() {
        @Override
        public int compare(Object o1, Object o2)
        {
            if (!(o1 instanceof String && o2 instanceof String))
                return 0;
            String s1 = (String) o1;
            String s2 = (String) o2;

            if (s1.startsWith(SUPermissions.PLAYER))
            {
                if (s2.startsWith(SUPermissions.PLAYER))
                    return s1.compareTo(s2);
                else
                    return -1;
            }
            else
            {
                if (s2.startsWith(SUPermissions.PLAYER))
                    return 1;
            }

            if (s1.startsWith(SUPermissions.GROUP))
            {
                if (s2.startsWith(SUPermissions.GROUP))
                    return s1.compareTo(s2);
                else
                    return -1;
            }
            else
            {
                if (s2.startsWith(SUPermissions.GROUP))
                    return 1;
            }

            if (s1.startsWith(SUPermissions.SU_INTERNAL))
            {
                if (s2.startsWith(SUPermissions.SU_INTERNAL))
                    return s1.compareTo(s2);
                else
                    return -1;
            }
            else
            {
                if (s2.startsWith(SUPermissions.SU_INTERNAL))
                    return 1;
                else
                    return s1.compareTo(s2);
            }
        }
    };

    private int id;

    protected Map<UserIdent, PermissionList> playerPermissions = new HashMap<>();

    protected Map<String, PermissionList> groupPermissions = new HashMap<>();

    public Zone(int id)
    {
        this.id = id;
    }

    public int getId()
    {
        return id;
    }

    @Override
    public int hashCode()
    {
        return id;
    }

    public boolean isPlayerInZone(Player player)
    {
        return isInZone(new WorldPoint(player));
    }

    public abstract boolean isInZone(WorldPoint point);

    // whole area contained in the zone
    public abstract boolean isInZone(WorldArea point);

    // area partly in the zone
    public abstract boolean isPartOfZone(WorldArea point);

    public abstract String getName();

    @Override
    public String toString()
    {
        return getName();
    }

    public abstract Zone getParent();

    public abstract ServerZone getServerZone();

    public void setDirty()
    {
        if (getServerZone() != null && getServerZone().getRootZone() != null)
            getServerZone().getRootZone().getPermissionHelper().setDirty(false);
    }

    // only AreaZones can be hidden; false everywhere else
    public boolean isHidden()
    {
        return false;
    }

    /**
     * Get all player permissions as a map
     */
    public Map<UserIdent, PermissionList> getPlayerPermissions()
    {
        return playerPermissions;
    }

    // null if none
    public PermissionList getPlayerPermissions(UserIdent ident)
    {
        return playerPermissions.get(ident);
    }

    public PermissionList getOrCreatePlayerPermissions(UserIdent ident)
    {
        PermissionList map = playerPermissions.get(ident);
        if (map == null)
        {
            map = new PermissionList();
            playerPermissions.put(ident, map);
            return map;
        }
        return playerPermissions.get(ident);
    }

    // null if not set
    public String getPlayerPermission(UserIdent ident, String permissionNode)
    {
        PermissionList map = getPlayerPermissions(ident);
        if (map != null)
        {
            return map.get(fixPerms(permissionNode));
        }
        return null;
    }

    // null if not set
    public String getPlayerPermission(Player player, String permissionNode)
    {
        return getPlayerPermission(UserIdent.get(player), permissionNode);
    }

    // true/false, or null if not set
    public Boolean checkPlayerPermission(UserIdent ident, String permissionNode)
    {
        PermissionList map = getPlayerPermissions(ident);
        if (map != null)
        {
            String permValue = map.get(fixPerms(permissionNode));
            return !PERMISSION_FALSE.equalsIgnoreCase(permValue);
        }
        return null;
    }

    public boolean setPlayerPermissionProperty(UserIdent ident, String permissionNode, String value)
    {
        if (ident != null
                && !APIRegistry.getSUEventBus().post(new PermissionEvent.User.ModifyPermission(getServerZone(), ident,
                        this, fixPerms(permissionNode), value)))
        {
            getServerZone().registerPlayer(ident);
            PermissionList map = getOrCreatePlayerPermissions(ident);
            if (value == null)
                map.remove(fixPerms(permissionNode));
            else
                map.put(fixPerms(permissionNode), value);
            setDirty();
            return true;
        }
        return false;
    }

    public boolean setPlayerPermission(UserIdent ident, String permissionNode, boolean value)
    {
        return setPlayerPermissionProperty(ident, permissionNode, value ? PERMISSION_TRUE : PERMISSION_FALSE);
    }

    public boolean clearPlayerPermission(UserIdent ident, String permissionNode)
    {
        if (ident != null)
        {
            PermissionList map = getPlayerPermissions(ident);
            if (map != null
                    && !APIRegistry.getSUEventBus().post(new PermissionEvent.User.ModifyPermission(getServerZone(),
                            ident, this, fixPerms(permissionNode), null)))
            {
                map.remove(fixPerms(permissionNode));
                return true;
            }
        }
        return false;
    }

    private Set<String> getPlayerGroups(UserIdent ident)
    {
        Set<String> result = new HashSet<>();
        String groupsStr = getPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);
        if (groupsStr != null && !groupsStr.isEmpty())
            for (String g : groupsStr.replaceAll(" ", "").split(","))
                if (!g.isEmpty())
                    result.add(g);
        return result;
    }

    public boolean addPlayerToGroup(UserIdent ident, String group)
    {
        if (APIRegistry.getSUEventBus().post(new PermissionEvent.User.ModifyGroups(getServerZone(), ident,
                PermissionEvent.User.ModifyGroups.Action.ADD, group)))
            return false;
        Set<String> groups = getPlayerGroups(ident);
        groups.add(group);
        setPlayerPermissionProperty(ident, SUPermissions.PLAYER_GROUPS, StringUtils.join(groups, ","));
        return true;
    }

    public boolean removePlayerFromGroup(UserIdent ident, String group)
    {
        if (APIRegistry.getSUEventBus().post(new PermissionEvent.User.ModifyGroups(getServerZone(), ident,
                PermissionEvent.User.ModifyGroups.Action.REMOVE, group)))
            return false;
        Set<String> groups = getPlayerGroups(ident);
        groups.remove(group);
        if (!groups.isEmpty())
            setPlayerPermissionProperty(ident, SUPermissions.PLAYER_GROUPS, StringUtils.join(groups, ","));
        else
            clearPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);
        return true;
    }

    // user's groups in this zone
    public Set<String> getStoredPlayerGroups(UserIdent ident)
    {
        Set<String> result = new HashSet<>();
        String groupsStr = getPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);
        if (groupsStr != null && !groupsStr.isEmpty())
            result.addAll(Arrays.asList(groupsStr.replace(" ", "").split(",")));
        return result;
    }

    // user's groups in this zone
    public SortedSet<GroupEntry> getStoredPlayerGroupEntries(UserIdent ident)
    {
        SortedSet<GroupEntry> result = new TreeSet<>();
        String groupsStr = getPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);
        if (groupsStr != null && !groupsStr.isEmpty())
            for (String group : groupsStr.replace(" ", "").split(","))
                result.add(new GroupEntry(getServerZone(), group));
        return result;
    }

    public Map<String, PermissionList> getGroupPermissions()
    {
        return groupPermissions;
    }

    // null if none
    public PermissionList getGroupPermissions(String group)
    {
        return groupPermissions.get(group);
    }

    public PermissionList getOrCreateGroupPermissions(String group)
    {
        PermissionList map = groupPermissions.get(group);
        if (map == null)
        {
            map = new PermissionList();
            groupPermissions.put(group, map);
        }
        return groupPermissions.get(group);
    }

    // null if not set
    public String getGroupPermission(String group, String permissionNode)
    {
        PermissionList map = getGroupPermissions(group);
        if (map != null)
        {
            return map.get(fixPerms(permissionNode));
        }
        return null;
    }

    // true/false, or null if not set
    public Boolean checkGroupPermission(String group, String permissionNode)
    {
        PermissionList map = getGroupPermissions(group);
        if (map != null)
        {
            String permValue = map.get(fixPerms(permissionNode));
            return !PERMISSION_FALSE.equalsIgnoreCase(permValue);
        }
        return null;
    }

    public boolean setGroupPermissionProperty(String group, String permissionNode, String value)
    {
        if (group != null
                && !APIRegistry.getSUEventBus().post(new PermissionEvent.Group.ModifyPermission(getServerZone(), group,
                        this, fixPerms(permissionNode), value)))
        {
            PermissionList map = getOrCreateGroupPermissions(group);
            if (value == null)
                map.remove(fixPerms(permissionNode));
            else
                map.put(fixPerms(permissionNode), value);
            setDirty();
            return true;
        }
        return false;
    }

    public boolean setGroupPermission(String group, String permissionNode, boolean value)
    {
        return setGroupPermissionProperty(group, permissionNode, value ? PERMISSION_TRUE : PERMISSION_FALSE);
    }

    public boolean clearGroupPermission(String group, String permissionNode)
    {
        if (group != null)
        {
            PermissionList map = getGroupPermissions(group);
            if (map != null
                    && !APIRegistry.getSUEventBus().post(new PermissionEvent.Group.ModifyPermission(getServerZone(),
                            group, this, fixPerms(permissionNode), null)))
            {
                map.remove(fixPerms(permissionNode));
                return true;
            }
        }
        return false;
    }

    public void userIdentInvalidated(UserIdentInvalidatedEvent event)
    {
        PermissionList oldPerms = playerPermissions.remove(event.oldValue);
        if (oldPerms == null)
            return;
        setDirty();

        PermissionList newPerms = playerPermissions.get(event.newValue);
        if (newPerms == null)
            playerPermissions.put(event.newValue, oldPerms);
        else
            newPerms.putAll(oldPerms);
    }

    // swap this zone's permissions with another's
    public void swapPermissions(Zone zone)
    {
        Map<String, PermissionList> swapGroupPerms = zone.groupPermissions;
        zone.groupPermissions = groupPermissions;
        groupPermissions = swapGroupPerms;

        Map<UserIdent, PermissionList> swapPlayerPermissions = zone.playerPermissions;
        zone.playerPermissions = playerPermissions;
        playerPermissions = swapPlayerPermissions;
    }

    // every permission node with any config in this zone
    public Set<String> enumRegisteredPermissions()
    {
        Set<String> perms = new TreeSet<>();
        for (Entry<UserIdent, PermissionList> permList : playerPermissions.entrySet())
            for (String perm : permList.getValue().keySet())
            {
                if (perm.endsWith(SUPermissions.DESCRIPTION_PROPERTY))
                    perm = perm.substring(0, perm.length() - SUPermissions.DESCRIPTION_PROPERTY.length());
                perms.add(perm);
            }
        for (Entry<String, PermissionList> permList : groupPermissions.entrySet())
            for (String perm : permList.getValue().keySet())
            {
                if (perm.endsWith(SUPermissions.DESCRIPTION_PROPERTY))
                    perm = perm.substring(0, perm.length() - SUPermissions.DESCRIPTION_PROPERTY.length());
                perms.add(perm);
            }
        return perms;
    }

    public static String fixPerms(String perm)
    {
        if (perm.contains("+"))
        {
            perm = perm.replace("+", "*");
        }
        return perm;
    }
}