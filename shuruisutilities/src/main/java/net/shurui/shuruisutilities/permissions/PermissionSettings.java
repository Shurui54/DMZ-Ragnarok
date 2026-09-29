package net.shurui.shuruisutilities.permissions;

/**
 * The few permission settings and node names that CORE code reads. The Permissions module (and its engine) live in
 * the Ragnarok Key; its config bake writes the two switches below, so core readers (UserIdent, the command node walk)
 * see the configured value keyed and these defaults keyless. The node strings are fixed: they are stored in
 * permission files.
 */
public final class PermissionSettings
{
    private PermissionSettings() {}

    /** Join even when the server is at its player limit (read offline by UUID in the PlayerList mixin). */
    public static final String PERM_PLAYERLIMIT_BYPASS = "su.playerlimit.bypass";

    /** Use teleport commands issued by the world map (tp/teleport/execute). */
    public static final String PERM_MAP_TELEPORT = "su.maptp";

    /** The group the engine gives a brand-new player on first login (and the shard bridge on a shard). */
    public static final String DEFAULT_PLAYER_GROUP = "player";

    /**
     * Force a fixed UUID for fake players (mods generate a random one each boot). Written by the key's
     * ModulePermissions config bake ({@code fakePlayerIsSpecialBunny}); true keyless, the config default.
     */
    public static volatile boolean fakePlayerIsSpecialBunny = true;

    /**
     * Give command ARGUMENT nodes their own permission nodes. Written by the key's ModulePermissions config bake
     * ({@code useEntireCommandNode}); false keyless, the config default.
     */
    public static volatile boolean fullcommandNode = false;
}
