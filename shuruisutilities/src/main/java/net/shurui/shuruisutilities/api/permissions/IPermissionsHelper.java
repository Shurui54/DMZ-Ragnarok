package net.shurui.shuruisutilities.api.permissions;

import java.util.Collection;
import java.util.List;
import java.util.SortedSet;

import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;

import net.minecraft.world.entity.player.Player;

// primary access-point to the permissions system
public interface IPermissionsHelper
{

    // mark storage dirty so it gets persisted asap
    void setDirty(boolean registeredPermission);

    public boolean checkBooleanPermission(String permissionValue);

    String getPermission(UserIdent ident, WorldPoint point, WorldArea area, List<String> groups, String permissionNode,
            boolean isProperty);

    boolean checkPermission(Player player, String permissionNode);

    // null if no such property
    String getPermissionProperty(Player player, String permissionNode);

    // description stored as the "permissionNode.$desc" property
    void registerPermissionDescription(String permissionNode, String description);

    String getPermissionDescription(String permissionNode);

    // register a permission with its default level (used to decide default-allow vs op-only) and description
    void registerPermission(String permissionNode, DefaultPermissionLevel level, String description);

    void registerPermissionProperty(String permissionNode, String defaultValue);

    void registerPermissionProperty(String permissionNode, String defaultValue, String description);

    void registerPermissionPropertyOp(String permissionNode, String defaultValue);

    void registerPermissionPropertyOp(String permissionNode, String defaultValue, String description);

    boolean checkUserPermission(UserIdent ident, String permissionNode);

    // null if no such property
    String getUserPermissionProperty(UserIdent ident, String permissionNode);

    // null if no such property
    Integer getUserPermissionPropertyInt(UserIdent ident, String permissionNode);

    boolean checkUserPermission(UserIdent ident, WorldPoint targetPoint, String permissionNode);

    String getUserPermissionProperty(UserIdent ident, WorldPoint targetPoint, String permissionNode);

    boolean checkUserPermission(UserIdent ident, WorldArea targetArea, String permissionNode);

    String getUserPermissionProperty(UserIdent ident, WorldArea targetArea, String permissionNode);

    boolean checkUserPermission(UserIdent ident, Zone zone, String permissionNode);

    String getUserPermissionProperty(UserIdent ident, Zone zone, String permissionNode);

    String getGroupPermissionProperty(String group, String permissionNode);

    String getGroupPermissionProperty(String group, Zone zone, String permissionNode);

    boolean checkGroupPermission(String group, String permissionNode);

    boolean checkGroupPermission(String group, Zone zone, String permissionNode);

    String getGroupPermissionProperty(String group, WorldPoint point, String permissionNode);

    boolean checkGroupPermission(String group, WorldPoint point, String permissionNode);

    // reads from the _ALL_ group
    String getGlobalPermissionProperty(String permissionNode);

    String getGlobalPermissionProperty(Zone zone, String permissionNode);

    boolean checkGlobalPermission(String permissionNode);

    boolean checkGlobalPermission(Zone zone, String permissionNode);

    void setPlayerPermission(UserIdent ident, String permissionNode, boolean value);

    void setPlayerPermissionProperty(UserIdent ident, String permissionNode, String value);

    void setGroupPermission(String group, String permissionNode, boolean value);

    void setGroupPermissionProperty(String group, String permissionNode, String value);

    Collection<Zone> getZones();

    // null if not found
    Zone getZoneById(int id);

    // null if id isn't a valid int or not found
    Zone getZoneById(String id);

    ServerZone getServerZone();

    boolean isSystemGroup(String group);

    boolean groupExists(String groupName);

    boolean createGroup(String groupName);

    // system groups can't be deleted
    boolean deleteGroup(String groupName);

    // keeps permissions + memberships. system groups can't be renamed; new name must be free.
    boolean renameGroup(String oldName, String newName);

    void addPlayerToGroup(UserIdent ident, String group);

    void removePlayerFromGroup(UserIdent ident, String group);

    // highest-priority group the player belongs to
    String getPrimaryGroup(UserIdent ident);

    // all groups incl. system + included, ordered by priority
    SortedSet<GroupEntry> getPlayerGroups(UserIdent ident);

    // only stored groups, ordered by priority
    SortedSet<GroupEntry> getStoredPlayerGroups(UserIdent ident);

}
