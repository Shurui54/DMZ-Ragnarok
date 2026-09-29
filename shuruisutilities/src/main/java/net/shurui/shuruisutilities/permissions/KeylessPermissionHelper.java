package net.shurui.shuruisutilities.permissions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.api.permissions.GroupEntry;
import net.shurui.shuruisutilities.api.permissions.IPermissionsHelper;
import net.shurui.shuruisutilities.api.permissions.RootZone;
import net.shurui.shuruisutilities.api.permissions.SUPermissions;
import net.shurui.shuruisutilities.api.permissions.ServerZone;
import net.shurui.shuruisutilities.api.permissions.Zone;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.core.misc.CommandPermissionManager;
import net.shurui.shuruisutilities.permissions.core.ZonePersistenceProvider;

/**
 * The KEYLESS {@code APIRegistry.perms}: permission CHECKS answer from the default level a node was REGISTERED with,
 * and nothing else. The real engine (zone and group evaluation, grants, the commands and the editor) lives in the
 * Ragnarok Key and replaces this through {@link net.shurui.shuruisutilities.api.key.PermissionHooks#install}.
 *
 * <h2>How a check answers</h2>
 * Registration is mirrored from the engine's own rule, so a node means the same thing either way: a node registered
 * ALL is allowed, NONE is denied, OP is denied to the default group and allowed to an operator. A node nobody
 * registered answers "not set", which {@link #checkBooleanPermission} reads as allowed, exactly as the engine does
 * with no data. The wildcard parents ({@code a.b.*}, {@code a.*}, {@code *}) are walked the same way. The server,
 * rcon and command block idents hold {@code *}, as the engine seeds them. Grants stored in the permission files are
 * NOT consulted, so nothing private leaks keyless.
 *
 * <p>An operator is judged by the VANILLA op level of the player's profile (the ops list, not the command source,
 * which SU elevates to 4 at parse time). A {@code command.<node>} needs the command's own vanilla level (2, 3 or 4,
 * handed over by {@link CommandPermissionManager}); every other OP node needs level 4
 * ({@link CommandPermissionManager#fromDefaultPermissionLevel}).
 *
 * <h2>Public player data</h2>
 * Some PUBLIC features keep per-player data as player permission PROPERTIES in the permission files (race unlocks,
 * the release boost wish, the wish rituals, the character slot settings, see {@link #PUBLIC_DATA_PREFIXES}). For those
 * nodes only, reads and writes go to the player's own entry in the real permission tree, loaded through the same core
 * persistence provider the engine uses ({@link #loadPublicData}) and saved through it after a write, so the data is
 * the same file, format and location keyed and keyless. Nothing else in the tree is ever changed: every other setter
 * is a no-op, and the tree is only written after a public data write, so a keyless server that writes none never
 * touches the files and a fresh keyless install creates none until then. A tree that failed to load is never saved.
 *
 * <h2>The hand-over</h2>
 * Every registration is also recorded. When the key installs its engine, {@link #handOver} replays them into it and
 * from then on forwards any late registration there, all under one lock, so construction order (mod construction is
 * parallel) can never lose a registered node or leave anyone holding this helper.
 */
public final class KeylessPermissionHelper implements IPermissionsHelper
{
    private static final Object LOCK = new Object();

    /**
     * The registered defaults, held exactly where the engine holds them: the root zone's default group
     * ({@code _ALL_}) and operators group ({@code _OPS_}). Callers that read a registered default straight from
     * {@code getServerZone().getRootZone()} (the /rgsettings command) therefore see the same values keyless.
     */
    private final RootZone root = new RootZone(this);

    /**
     * Player permission-property prefixes that PUBLIC features use as per-player data storage. Keyless, only these
     * are read from and written to the loaded permission tree. Keep this list to public data: a node that grants a
     * private power must never be added here.
     */
    public static final List<String> PUBLIC_DATA_PREFIXES = List.of(
            "su.raceunlock.",        // RaceUnlocks: shadow dragon race and sub-race unlocks, pending notices
            "su.wish.releaseboost",  // ReleaseBoostStore: the Super ball ki release boost wish
            "su.wishritual.",        // WishRitualStore: per-set ritual counts and granted flags
            "su.ssg.",               // WishRitualStore: SSG knowledge, purchase right, temporary ritual grant
            "su.character.");        // CharacterSlots: slot limit, swap cooldown and combat lock (per player)

    /**
     * The server zone: the loaded permission tree once {@link #loadPublicData} ran (keyless server start), otherwise
     * an empty in-memory one over {@link #root}. Only player properties under {@link #PUBLIC_DATA_PREFIXES} are ever
     * read from it or written to it.
     */
    private volatile ServerZone shell = new ServerZone(root);

    /** How the tree is read and saved keyless; null until a server starts. Guarded by {@link #DATA}. */
    private ZonePersistenceProvider provider;

    /** True when the permission file exists but could not be read: nothing may be saved over it. */
    private boolean saveBlocked;

    private static final Object DATA = new Object();

    /** Exact vanilla op level per command node (see {@link #setRequiredOpLevel}); other OP nodes need level 4. */
    private final Map<String, Integer> requiredOpLevels = new java.util.concurrent.ConcurrentHashMap<>();

    /** Every registration call, in order, for the replay into the key's engine. Guarded by {@link #LOCK}. */
    private final List<Consumer<IPermissionsHelper>> registrations = new ArrayList<>();

    /** The engine this helper handed over to, once it has. Guarded by {@link #LOCK}. */
    private IPermissionsHelper forward;

    private KeylessPermissionHelper()
    {
    }

    /**
     * Set the keyless helper as {@code APIRegistry.perms} unless something (the key's engine) is already there. Called
     * from the SU constructor before the module launcher runs, so the field is never null later than it used to be.
     */
    public static void installDefault()
    {
        synchronized (LOCK)
        {
            if (APIRegistry.perms == null)
                APIRegistry.perms = new KeylessPermissionHelper();
        }
    }

    /**
     * Replace {@code APIRegistry.perms} with the key's engine, replaying every registration this helper took first.
     * Only {@link net.shurui.shuruisutilities.api.key.PermissionHooks#install} calls this.
     */
    public static void handOver(IPermissionsHelper engine)
    {
        synchronized (LOCK)
        {
            if (APIRegistry.perms instanceof KeylessPermissionHelper keyless && keyless.forward == null)
            {
                for (Consumer<IPermissionsHelper> registration : keyless.registrations)
                    registration.accept(engine);
                keyless.registrations.clear();
                keyless.forward = engine;
            }
            APIRegistry.perms = engine;
        }
    }

    // ---- registration ----------------------------------------------------------------------------------------

    /**
     * Apply a registration here and record it for the replay, or forward it when the engine has already taken over.
     * Both happen under {@link #LOCK}, so a registration can never fall between the replay and the swap.
     */
    private void register(Consumer<IPermissionsHelper> registration, Runnable local)
    {
        synchronized (LOCK)
        {
            if (forward != null)
            {
                registration.accept(forward);
                return;
            }
            registrations.add(registration);
            local.run();
        }
    }

    private void put(String group, String node, String value)
    {
        if (node != null)
            root.setGroupPermissionProperty(group, node, value);
    }

    @Override
    public void registerPermission(String permissionNode, DefaultPermissionLevel level, String description)
    {
        register(h -> h.registerPermission(permissionNode, level, description), () -> {
            if (level == DefaultPermissionLevel.NONE)
                put(Zone.GROUP_DEFAULT, permissionNode, Zone.PERMISSION_FALSE);
            else if (level == DefaultPermissionLevel.ALL)
                put(Zone.GROUP_DEFAULT, permissionNode, Zone.PERMISSION_TRUE);
            else
            {
                put(Zone.GROUP_DEFAULT, permissionNode, Zone.PERMISSION_FALSE);
                put(Zone.GROUP_OPERATORS, permissionNode, Zone.PERMISSION_TRUE);
            }
            put(Zone.GROUP_DEFAULT, permissionNode + SUPermissions.DESCRIPTION_PROPERTY, description);
        });
    }

    @Override
    public void registerPermissionDescription(String permissionNode, String description)
    {
        register(h -> h.registerPermissionDescription(permissionNode, description),
                () -> put(Zone.GROUP_DEFAULT, permissionNode + SUPermissions.DESCRIPTION_PROPERTY, description));
    }

    @Override
    public void registerPermissionProperty(String permissionNode, String defaultValue)
    {
        register(h -> h.registerPermissionProperty(permissionNode, defaultValue),
                () -> put(Zone.GROUP_DEFAULT, permissionNode, defaultValue));
    }

    @Override
    public void registerPermissionProperty(String permissionNode, String defaultValue, String description)
    {
        register(h -> h.registerPermissionProperty(permissionNode, defaultValue, description), () -> {
            put(Zone.GROUP_DEFAULT, permissionNode, defaultValue);
            put(Zone.GROUP_DEFAULT, permissionNode + SUPermissions.DESCRIPTION_PROPERTY, description);
        });
    }

    @Override
    public void registerPermissionPropertyOp(String permissionNode, String defaultValue)
    {
        register(h -> h.registerPermissionPropertyOp(permissionNode, defaultValue),
                () -> put(Zone.GROUP_OPERATORS, permissionNode, defaultValue));
    }

    @Override
    public void registerPermissionPropertyOp(String permissionNode, String defaultValue, String description)
    {
        register(h -> h.registerPermissionPropertyOp(permissionNode, defaultValue, description), () -> {
            put(Zone.GROUP_OPERATORS, permissionNode, defaultValue);
            put(Zone.GROUP_DEFAULT, permissionNode + SUPermissions.DESCRIPTION_PROPERTY, description);
        });
    }

    @Override
    public String getPermissionDescription(String permissionNode)
    {
        return root.getGroupPermission(Zone.GROUP_DEFAULT, permissionNode + SUPermissions.DESCRIPTION_PROPERTY);
    }

    @Override
    public void setDirty(boolean registeredPermission)
    {
        // the tree is only saved right after a public data write (see setPlayerPermissionProperty)
    }

    /**
     * The vanilla op level (0-4) a command node asks for, from the command's own requires() and its parents'. Only
     * {@link CommandPermissionManager} calls this, keyless, while it registers the command nodes.
     */
    public void setRequiredOpLevel(String permissionNode, int level)
    {
        if (permissionNode != null)
            requiredOpLevels.put(Zone.fixPerms(permissionNode), Math.max(0, Math.min(4, level)));
    }

    // ---- public player data ----------------------------------------------------------------------------------

    /** Whether a node is public per-player data (read and written keyless). */
    public static boolean isPublicDataNode(String permissionNode)
    {
        if (permissionNode == null)
            return false;
        for (String prefix : PUBLIC_DATA_PREFIXES)
            if (permissionNode.startsWith(prefix))
                return true;
        return false;
    }

    /**
     * Load the permission tree through the configured core persistence provider (keyless server start). Nothing is
     * written. An absent file leaves the empty tree (created on disk only by a later public data write); an
     * unreadable one blocks every save so it is never overwritten.
     */
    public void loadPublicData()
    {
        synchronized (DATA)
        {
            provider = PermissionsBootstrap.configuredProvider();
            ServerZone loaded = null;
            try
            {
                loaded = provider.load();
            }
            catch (RuntimeException e)
            {
                net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.error(
                        "[Permissions] Could not read the permission tree keyless; public player data is read-only this run.", e);
            }
            saveBlocked = loaded == null && provider.getLastLoadOutcome() != ZonePersistenceProvider.LoadOutcome.ABSENT;
            ServerZone zone = loaded != null ? loaded : new ServerZone(root);
            if (loaded != null)
            {
                root.setServerZone(loaded);
                loaded.rebuildZonesMap();
            }
            shell = zone;
        }
    }

    /** Drop the loaded tree at server stop (a later server in this JVM loads its own). Writes nothing. */
    public void unloadPublicData()
    {
        synchronized (DATA)
        {
            provider = null;
            saveBlocked = false;
            shell = new ServerZone(root);
        }
    }

    private String publicData(UserIdent ident, String permissionNode)
    {
        if (ident == null)
            return null;
        synchronized (DATA)
        {
            return shell.getPlayerPermission(ident, permissionNode);
        }
    }

    // ---- resolution ------------------------------------------------------------------------------------------

    /** The node and its wildcard parents, in the engine's order (a.b.c, a.b.c.*, a.b.*, a.*, *). */
    private static List<String> nodes(String permissionNode)
    {
        List<String> nodes = new ArrayList<>();
        nodes.add(permissionNode);
        String[] parts = permissionNode.split("\\.");
        for (int i = parts.length; i > 0; i--)
        {
            StringBuilder node = new StringBuilder();
            for (int j = 0; j < i; j++)
                node.append(parts[j]).append('.');
            nodes.add(node + Zone.PERMISSION_ASTERIX);
        }
        nodes.add(Zone.PERMISSION_ASTERIX);
        return nodes;
    }

    private Map<String, String> mapFor(String group)
    {
        if (Zone.GROUP_OPERATORS.equals(group) || Zone.GROUP_DEFAULT.equals(group))
            return root.getGroupPermissions(group);
        return null;
    }

    private String lookup(List<String> groups, String permissionNode, boolean isProperty)
    {
        if (permissionNode == null || groups == null)
            return null;
        List<String> nodes = isProperty ? Collections.singletonList(permissionNode) : nodes(permissionNode);
        for (String group : groups)
        {
            Map<String, String> map = mapFor(group);
            if (map == null)
                continue;
            for (String node : nodes)
            {
                String value = map.get(Zone.fixPerms(node));
                if (value != null)
                    return value;
            }
        }
        return null;
    }

    /** The console, rcon and command blocks hold every node, as the engine seeds them. */
    private static boolean holdsEverything(UserIdent ident)
    {
        return ident != null && (ident.equals(APIRegistry.IDENT_SERVER) || ident.equals(APIRegistry.IDENT_RCON)
                || ident.equals(APIRegistry.IDENT_CMDBLOCK));
    }

    private static int opLevel()
    {
        return CommandPermissionManager.fromDefaultPermissionLevel(DefaultPermissionLevel.OP);
    }

    /** The vanilla op level of a profile (0 for a non-op), read from the ops list. */
    private static int level(GameProfile profile)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || profile == null || profile.getId() == null)
            return 0;
        try
        {
            return server.getProfilePermissions(profile);
        }
        catch (RuntimeException e)
        {
            return 0;
        }
    }

    private static int level(UserIdent ident)
    {
        if (ident == null || holdsEverything(ident) || ident.isFakePlayer())
            return 0;
        Player player = ident.hasPlayer() ? ident.getPlayer() : null;
        return level(player != null ? player.getGameProfile() : ident.getGameProfile());
    }

    private static int level(Player player)
    {
        return player == null ? 0 : level(player.getGameProfile());
    }

    private static boolean isOp(UserIdent ident)
    {
        return level(ident) > 0;
    }

    private int requiredLevel(String node)
    {
        Integer level = requiredOpLevels.get(Zone.fixPerms(node));
        return level == null ? opLevel() : level;
    }

    /**
     * A player's answer: the operators group first (each OP node only once the player's vanilla level reaches that
     * node's level), then the default group, both over the node and its wildcard parents, like the engine.
     */
    private String userLookup(int level, String permissionNode, boolean isProperty)
    {
        if (permissionNode == null)
            return null;
        List<String> nodes = isProperty ? Collections.singletonList(permissionNode) : nodes(permissionNode);
        if (level > 0)
        {
            Map<String, String> ops = mapFor(Zone.GROUP_OPERATORS);
            if (ops != null)
                for (String node : nodes)
                {
                    String value = ops.get(Zone.fixPerms(node));
                    if (value != null && level >= requiredLevel(node))
                        return value;
                }
        }
        return lookup(Collections.singletonList(Zone.GROUP_DEFAULT), permissionNode, isProperty);
    }

    private String userValue(UserIdent ident, String permissionNode, boolean isProperty)
    {
        if (!isProperty && holdsEverything(ident))
            return Zone.PERMISSION_TRUE;
        if (isProperty && isPublicDataNode(permissionNode))
        {
            String stored = publicData(ident, permissionNode);
            if (stored != null)
                return stored;
        }
        return userLookup(level(ident), permissionNode, isProperty);
    }

    @Override
    public String getPermission(UserIdent ident, WorldPoint point, WorldArea area, List<String> groups,
            String permissionNode, boolean isProperty)
    {
        if (!isProperty && holdsEverything(ident))
            return Zone.PERMISSION_TRUE;
        if (groups == null)
            return userValue(ident, permissionNode, isProperty);
        return lookup(groups, permissionNode, isProperty);
    }

    @Override
    public boolean checkBooleanPermission(String permissionValue)
    {
        return permissionValue == null || !permissionValue.equals(Zone.PERMISSION_FALSE);
    }

    @Override
    public boolean checkPermission(Player player, String permissionNode)
    {
        return checkBooleanPermission(userLookup(level(player), permissionNode, false));
    }

    @Override
    public String getPermissionProperty(Player player, String permissionNode)
    {
        if (player != null && isPublicDataNode(permissionNode))
            return userValue(UserIdent.get(player), permissionNode, true);
        return userLookup(level(player), permissionNode, true);
    }

    @Override
    public boolean checkUserPermission(UserIdent ident, String permissionNode)
    {
        return checkBooleanPermission(userValue(ident, permissionNode, false));
    }

    @Override
    public String getUserPermissionProperty(UserIdent ident, String permissionNode)
    {
        return userValue(ident, permissionNode, true);
    }

    @Override
    public Integer getUserPermissionPropertyInt(UserIdent ident, String permissionNode)
    {
        String value = getUserPermissionProperty(ident, permissionNode);
        try
        {
            return value == null ? null : Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    @Override
    public boolean checkUserPermission(UserIdent ident, WorldPoint targetPoint, String permissionNode)
    {
        return checkUserPermission(ident, permissionNode);
    }

    @Override
    public String getUserPermissionProperty(UserIdent ident, WorldPoint targetPoint, String permissionNode)
    {
        return getUserPermissionProperty(ident, permissionNode);
    }

    @Override
    public boolean checkUserPermission(UserIdent ident, WorldArea targetArea, String permissionNode)
    {
        return checkUserPermission(ident, permissionNode);
    }

    @Override
    public String getUserPermissionProperty(UserIdent ident, WorldArea targetArea, String permissionNode)
    {
        return getUserPermissionProperty(ident, permissionNode);
    }

    @Override
    public boolean checkUserPermission(UserIdent ident, Zone zone, String permissionNode)
    {
        return checkUserPermission(ident, permissionNode);
    }

    @Override
    public String getUserPermissionProperty(UserIdent ident, Zone zone, String permissionNode)
    {
        return getUserPermissionProperty(ident, permissionNode);
    }

    @Override
    public String getGroupPermissionProperty(String group, String permissionNode)
    {
        return lookup(Collections.singletonList(group), permissionNode, true);
    }

    @Override
    public String getGroupPermissionProperty(String group, Zone zone, String permissionNode)
    {
        return getGroupPermissionProperty(group, permissionNode);
    }

    @Override
    public boolean checkGroupPermission(String group, String permissionNode)
    {
        return checkBooleanPermission(lookup(Collections.singletonList(group), permissionNode, false));
    }

    @Override
    public boolean checkGroupPermission(String group, Zone zone, String permissionNode)
    {
        return checkGroupPermission(group, permissionNode);
    }

    @Override
    public String getGroupPermissionProperty(String group, WorldPoint point, String permissionNode)
    {
        return getGroupPermissionProperty(group, permissionNode);
    }

    @Override
    public boolean checkGroupPermission(String group, WorldPoint point, String permissionNode)
    {
        return checkGroupPermission(group, permissionNode);
    }

    @Override
    public String getGlobalPermissionProperty(String permissionNode)
    {
        return getGroupPermissionProperty(Zone.GROUP_DEFAULT, permissionNode);
    }

    @Override
    public String getGlobalPermissionProperty(Zone zone, String permissionNode)
    {
        return getGlobalPermissionProperty(permissionNode);
    }

    @Override
    public boolean checkGlobalPermission(String permissionNode)
    {
        return checkGroupPermission(Zone.GROUP_DEFAULT, permissionNode);
    }

    @Override
    public boolean checkGlobalPermission(Zone zone, String permissionNode)
    {
        return checkGlobalPermission(permissionNode);
    }

    // ---- writes: none keyless --------------------------------------------------------------------------------

    @Override
    public void setPlayerPermission(UserIdent ident, String permissionNode, boolean value)
    {
    }

    /**
     * Public player data only ({@link #PUBLIC_DATA_PREFIXES}): written into the player's own entry of the loaded tree
     * and saved at once through the core provider. Every other node is a no-op keyless.
     */
    @Override
    public void setPlayerPermissionProperty(UserIdent ident, String permissionNode, String value)
    {
        if (ident == null || !isPublicDataNode(permissionNode))
            return;
        synchronized (DATA)
        {
            if (provider == null || saveBlocked)
            {
                net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                        "[Permissions] Not storing {} for {}: the permission tree is {}.", permissionNode,
                        ident.getUsernameOrUuid(), provider == null ? "not loaded (no server)" : "unreadable (saving blocked)");
                return;
            }
            if (value == null)
                shell.clearPlayerPermission(ident, permissionNode);
            else
                shell.setPlayerPermissionProperty(ident, permissionNode, value);
            provider.save(shell);
        }
    }

    @Override
    public void setGroupPermission(String group, String permissionNode, boolean value)
    {
    }

    @Override
    public void setGroupPermissionProperty(String group, String permissionNode, String value)
    {
    }

    // ---- zones and groups: none keyless ----------------------------------------------------------------------

    @Override
    public Collection<Zone> getZones()
    {
        return getServerZone().getZones();
    }

    @Override
    public Zone getZoneById(int id)
    {
        return null;
    }

    @Override
    public Zone getZoneById(String id)
    {
        return null;
    }

    /**
     * The server zone for callers that walk zones: the loaded tree keyless (empty before a server starts). Its root
     * holds the registered defaults, as the engine's does. Only public player data in it is ever consulted for an
     * answer, and callers must not write to it directly (nothing but a public data write saves it).
     */
    @Override
    public ServerZone getServerZone()
    {
        return shell;
    }

    @Override
    public boolean isSystemGroup(String group)
    {
        return Zone.GROUP_DEFAULT.equals(group) || Zone.GROUP_GUESTS.equals(group)
                || Zone.GROUP_OPERATORS.equals(group) || Zone.GROUP_PLAYERS.equals(group)
                || Zone.GROUP_FAKEPLAYERS.equals(group);
    }

    @Override
    public boolean groupExists(String groupName)
    {
        return Zone.GROUP_DEFAULT.equals(groupName) || Zone.GROUP_OPERATORS.equals(groupName);
    }

    @Override
    public boolean createGroup(String groupName)
    {
        return false;
    }

    @Override
    public boolean deleteGroup(String groupName)
    {
        return false;
    }

    @Override
    public boolean renameGroup(String oldName, String newName)
    {
        return false;
    }

    @Override
    public void addPlayerToGroup(UserIdent ident, String group)
    {
    }

    @Override
    public void removePlayerFromGroup(UserIdent ident, String group)
    {
    }

    @Override
    public String getPrimaryGroup(UserIdent ident)
    {
        return getPlayerGroups(ident).first().getGroup();
    }

    @Override
    public SortedSet<GroupEntry> getPlayerGroups(UserIdent ident)
    {
        SortedSet<GroupEntry> result = new TreeSet<>();
        if (isOp(ident))
            result.add(new GroupEntry(Zone.GROUP_OPERATORS, 50));
        result.add(new GroupEntry(Zone.GROUP_DEFAULT, 0));
        return result;
    }

    @Override
    public SortedSet<GroupEntry> getStoredPlayerGroups(UserIdent ident)
    {
        return new TreeSet<>();
    }
}
