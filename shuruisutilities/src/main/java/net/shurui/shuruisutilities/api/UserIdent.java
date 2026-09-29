package net.shurui.shuruisutilities.api;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.shurui.shuruisutilities.permissions.PermissionSettings;
import net.shurui.shuruisutilities.util.CommandUtils;
import net.shurui.shuruisutilities.util.DoAsCommandSender;
import net.shurui.shuruisutilities.util.ServerUtil;
import net.shurui.shuruisutilities.util.UserIdentUtils;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.google.gson.annotations.Expose;
import com.mojang.authlib.GameProfile;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.server.ServerLifecycleHooks;

public class UserIdent
{

    public static class ServerUserIdent extends UserIdent
    {

        private ServerUserIdent(UUID uuid, String username)
        {
            super(uuid, username, null);
        }

        @Override
        public boolean isPlayer()
        {
            return false;
        }

    }

    public static class NpcUserIdent extends UserIdent
    {

        private NpcUserIdent(UUID uuid, String username)
        {
            super(uuid, username, null);
        }

        @Override
        public boolean isPlayer()
        {
            return false;
        }

        @Override
        public boolean isNpc()
        {
            return true;
        }

    }

    public static class UserIdentInvalidatedEvent extends Event
    {

        public final UserIdent oldValue;

        public final UserIdent newValue;

        public UserIdentInvalidatedEvent(UserIdent oldValue, UserIdent newValue)
        {
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

    }

    private static final Map<UUID, UserIdent> byUuid = new HashMap<>();

    private static final Map<String, UserIdent> byUsername = new HashMap<>();

    protected UUID uuid;

    protected String username;

    @Expose(serialize = false)
    protected int hashCode;

    @Expose(serialize = false)
    protected WeakReference<Player> player;

    private UserIdent(Player player)
    {
        this(null, null, player);
    }

    private UserIdent(UUID identUuid, String identUsername, Player identPlayer)
    {
        if (identUsername != null && identUsername.isEmpty())
            identUsername = null;

        UserIdent oldIdent = null;
        player = identPlayer == null ? null : new WeakReference<>(identPlayer);
        if (identPlayer != null)
        {
            uuid = identPlayer.getGameProfile().getId();
            username = identPlayer.getGameProfile().getName();
            if (byUuid.containsKey(uuid))
            {
                oldIdent = byUuid.get(uuid);
            }
            byUuid.put(uuid, this);
            byUsername.put(username.toLowerCase(), this);
        }
        else
        {
            uuid = identUuid;
            username = identUsername;

            if (byUuid.containsKey(uuid))
            {
                oldIdent = byUuid.get(uuid);
            }

            if (uuid != null)
                byUuid.put(this.uuid, this);
            if (identUsername != null && identUsername.charAt(0) != '@')
                byUsername.put(identUsername.toLowerCase(), this);

            if (identUsername == null || identUsername.charAt(0) != '$' || identUsername.charAt(0) != '@')
            {
                if (uuid == null && username != null)
                    uuid = UserIdentUtils.resolveMissingUUID(username);
                else if (uuid != null && username == null)
                    username = UserIdentUtils.resolveMissingUsername(uuid);
            }
        }

        if (oldIdent != null && oldIdent.username != null && !oldIdent.username.equals(username))
        {
            byUsername.remove(oldIdent.username);
            APIRegistry.getSUEventBus().post(new UserIdentInvalidatedEvent(oldIdent, this));
            LoggingHandler.sulog.warn("Old Username: {} for uuid {}, was replaced with {}!", oldIdent.username, uuid,
                    username);
        }
    }

    public static synchronized UserIdent get(GameProfile profile)
    {
        return get(profile.getId(), profile.getName());
    }

    public static synchronized UserIdent get(UUID uuid, String username)
    {
        if (uuid == null && (username == null || username.isEmpty()))
            throw new IllegalArgumentException();
        if (username != null && username.isEmpty())
            username = null;

        if (uuid != null)
        {
            UserIdent ident = byUuid.get(uuid);
            if (ident != null)
                return ident;
        }

        if (username != null)
        {
            UserIdent ident = byUsername.get(username.toLowerCase());
            if (ident != null)
            {
                // Only adopt the incoming UUID onto the cached (name-keyed) ident when that ident has no UUID
                // yet. That is the intended reconciliation: an ident first created by username later gains its
                // real UUID. If the cached ident already has a DIFFERENT non-null UUID, it is a genuinely
                // distinct identity that merely shares this username (for example an offline name-derived UUID
                // versus the real Mojang UUID). Rebinding it here would silently collapse the two into one
                // instance and, because UserIdent caches its hashCode from the original UUID, make a HashMap
                // treat two legitimately different keys as the same one. That is what made Gson report a bogus
                // "duplicate key" and refuse to load permissions.json. So do not mutate a non-null UUID; fall
                // through and build a separate ident for this UUID instead.
                if (uuid == null || (uuid.equals(ident.uuid)))
                    return ident;
                if (ident.uuid == null)
                {
                    ident.uuid = uuid;
                    byUuid.put(uuid, ident);
                    return ident;
                }
                // differing non-null UUIDs: leave the cached ident alone and resolve a distinct one below.
            }
            if (username.startsWith("$NPC"))
            {
                return new NpcUserIdent(uuid, username);
            }
            else if (username.startsWith("$"))
            {
                return new ServerUserIdent(uuid, username);
            }
        }

        return new UserIdent(uuid, username, UserIdent.getPlayerByUuid(uuid));
    }

    public static synchronized UserIdent get(String uuid, String username)
    {
        return get(uuid != null && !uuid.isEmpty() ? UUID.fromString(uuid) : null, username);
    }

    public static synchronized UserIdent get(UUID uuid)
    {
        if (uuid == null)
            throw new IllegalArgumentException();

        UserIdent ident = byUuid.get(uuid);
        if (ident != null)
            return ident;

        return new UserIdent(uuid, null, UserIdent.getPlayerByUuid(uuid));
    }

    public static synchronized UserIdent get(CommandSourceStack sender)
    {
        if (sender.getEntity() instanceof Player)
        {
            return get((ServerPlayer) sender.getEntity());
        }
        CommandSource source = CommandUtils.GetSource(sender);
        if (source instanceof DoAsCommandSender)
        {
            return ((DoAsCommandSender) source).getIdent();
        }
        else if (source instanceof MinecraftServer)
        {
            return APIRegistry.IDENT_SERVER;
        }
        else if (source instanceof RconConsoleSource)
        {
            return APIRegistry.IDENT_RCON;
        }
        else if (source instanceof BaseCommandBlock)
        {
            return APIRegistry.IDENT_CMDBLOCK;
        }
        else
        {
            return UserIdent.getNpc(sender.getTextName());
        }
    }

    public static synchronized UserIdent getFromUuid(String uuid)
    {
        if (uuid == null)
            return null;
        try
        {
            return get(UUID.fromString(uuid));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    // public static synchronized UserIdent get(EntityPlayer player)
    // {
    // return player instanceof ServerPlayer ? get((ServerPlayer)
    // player) : null;
    // }

    public static synchronized UserIdent get(Player player)
    {
        if (player == null)
            throw new IllegalArgumentException();

        // A forwarded command runs under a stand-in whose whole purpose is to carry the ORIGINAL sender's identity
        // onto the server that holds the target. It is a FakePlayer, so without this it would collapse to an NPC
        // ident below and every permission check would answer against nothing, silently. Resolved to the real
        // sender ident first, so the command is gated exactly as if the sender were stood here. This is the only
        // FakePlayer subclass that means "act as a real, named player", which is why it is singled out. See
        // net.shurui.shuruisutilities.shard.ForwardedCommandSender.
        if (player instanceof net.shurui.shuruisutilities.shard.ForwardedCommandSender forwarded)
            return forwarded.getIdent();

        if (player instanceof FakePlayer)
        {
            return getNpc(player.getDisplayName().getString(),
                    PermissionSettings.fakePlayerIsSpecialBunny ? null : player.getGameProfile().getId());
        }

        UserIdent ident = byUuid.get(player.getGameProfile().getId());
        if (ident == null)
        {
            ident = byUsername.get(player.getDisplayName().getString());
            if (ident != null)
            {
                ident.uuid = player.getGameProfile().getId();
                byUuid.put(ident.uuid, ident);
            }
            else
                ident = new UserIdent(player);
        }
        else
        {
            String name = player.getDisplayName().getString();
            if (name != null && !name.equals(ident.username))
            {
                byUsername.remove(ident.username);
                ident.username = name;
                byUsername.put(ident.username.toLowerCase(), ident);
            }
        }
        if (ident.player == null || ident.player.get() != player)
            ident.player = new WeakReference<>(player);
        return ident;
    }

    public static synchronized UserIdent get(String uuidOrUsername, CommandSourceStack sender, boolean mustExist)
    {
        Player player = sender != null ? UserIdent.getPlayerByMatchOrUsername(sender, uuidOrUsername) : //
                UserIdent.getPlayerByUsername(uuidOrUsername);
        if (player != null)
            return get(player);

        if (uuidOrUsername == null)
            throw new IllegalArgumentException();
        try
        {
            return get(UUID.fromString(uuidOrUsername));
        }
        catch (IllegalArgumentException e)
        {
            UserIdent ident = byUsername.get(uuidOrUsername.toLowerCase());
            if (ident != null)
                return ident;

            // Resolving a NAME must never silently return a DIFFERENT player. If the name matched nothing (not an
            // online player, not a UUID, not a cached username), fail: null when the caller demanded an existing
            // player, otherwise an unresolved placeholder ident. Self targeting is handled earlier by the selector
            // path in getPlayerByMatchOrUsername (e.g. @s), not by falling back to the sender here.
            return mustExist ? null : new UserIdent(null, uuidOrUsername, null);
        }
    }

    public static synchronized UserIdent get(String uuidOrUsername, CommandSourceStack sender)
    {
        return get(uuidOrUsername, sender, false);
    }

    public static synchronized UserIdent get(String uuidOrUsername, boolean mustExist)
    {
        return get(uuidOrUsername, (CommandSourceStack) null, mustExist);
    }

    public static synchronized UserIdent get(String uuidOrUsername)
    {
        return get(uuidOrUsername, false);
    }

    public static synchronized UserIdent getVirtualPlayer(String username)
    {
        return get(UUID.nameUUIDFromBytes(username.getBytes()), username);
    }

    public static synchronized ServerUserIdent getServer(String uuid, String username)
    {

        UUID _uuid = null;
        if (uuid != null)
            try
            {
                _uuid = UUID.fromString(uuid);
            }
            catch (IllegalArgumentException e)
            {
                // If UUID is invalid, lookup by username
            }

        UserIdent ident = byUuid.get(_uuid);
        if (ident == null)
            ident = byUsername.get(username);

        if (!(ident instanceof ServerUserIdent))
            ident = new ServerUserIdent(_uuid, username);

        return (ServerUserIdent) ident;
    }

    public static synchronized NpcUserIdent getNpc(String npcName)
    {
        return getNpc(npcName, null);
    }

    public static synchronized NpcUserIdent getNpc(String npcName, @Nullable UUID uuid)
    {
        String username = "$NPC" + (npcName == null ? "" : "_" + npcName.toUpperCase());
        UUID _uuid = uuid != null ? uuid : UUID.nameUUIDFromBytes(username.getBytes());

        UserIdent ident = byUuid.get(_uuid);
        if (ident == null)
        {
            ident = byUsername.get(username);
        }
        else if (ident instanceof NpcUserIdent)
        {
            if (!username.equals(ident.username))
            {
                ident.username = username;
            }
        }

        if (ident instanceof NpcUserIdent)
        {
            if (!_uuid.equals(ident.uuid))
            {
                ident.uuid = _uuid;
            }
        }

        if (!(ident instanceof NpcUserIdent))
        {
            ident = new NpcUserIdent(_uuid, username);
        }

        return (NpcUserIdent) ident;
    }

    public static synchronized void login(Player player)
    {
        UserIdent ident = byUuid.get(player.getGameProfile().getId());
        UserIdent usernameIdent = byUsername.get(player.getDisplayName().getString());

        if (ident == null)
        {
            if (usernameIdent == null)
                ident = new UserIdent(player);
            else
            {
                ident = usernameIdent;
                byUuid.put(player.getGameProfile().getId(), ident);
            }
        }
        ident.player = new WeakReference<>(player);
        ident.username = player.getDisplayName().getString();
        ident.uuid = player.getGameProfile().getId();

        if (usernameIdent != null && usernameIdent != ident)
        {
            APIRegistry.getSUEventBus().post(new UserIdentInvalidatedEvent(usernameIdent, ident));

            // Change data for already existing references to old UserIdent
            usernameIdent.player = new WeakReference<>(player);
            usernameIdent.username = player.getDisplayName().getString();

            // Replace entry in username map by the one from uuid map
            byUsername.remove(usernameIdent.username.toLowerCase());
            byUsername.put(ident.username.toLowerCase(), ident);
        }
    }

    public static synchronized void logout(Player player)
    {
        UserIdent ident = UserIdent.get(player);
        ident.player = null;
    }

    public boolean hasUsername()
    {
        return username != null;
    }

    public boolean hasUuid()
    {
        return uuid != null;
    }

    public boolean hasPlayer()
    {
        Player player = getPlayer();
        return player != null && !(player instanceof FakePlayer);
        // return ServerUtil.getPlayerList().contains(player);
    }

    public boolean isFakePlayer()
    {
        return getPlayer() instanceof FakePlayer;
    }

    // false for NPC/server idents
    public boolean isPlayer()
    {
        return true;
    }

    public boolean isNpc()
    {
        return false;
    }

    public UUID getUuid()
    {
        return uuid;
    }

    public String getUsername()
    {
        return username;
    }

    public String getUsernameOrUuid()
    {
        return username == null ? uuid.toString() : username;
    }

    public void refreshPlayer()
    {
        Player player = UserIdent.getPlayerByUuid(uuid);
        this.player = player == null ? null : new WeakReference<>(player);
    }

    public Player getPlayer()
    {
        return player == null ? null : player.get();
    }

    public ServerPlayer getPlayerMP()
    {
        return player == null ? null : (ServerPlayer) player.get();
    }

    public Player getFakePlayer()
    {
        Player player = getPlayerMP();
        if (player != null)
            return player;
        return FakePlayerFactory.get(ServerUtil.getOverworld(), getGameProfile());
    }

    public Player getFakePlayer(ServerLevel world)
    {
        Player player = getPlayerMP();
        if (player != null)
            return player;
        return FakePlayerFactory.get(world, getGameProfile());
    }

    // real UUID, or a username-derived one when null. use when you need a UUID guaranteed (e.g. map keys).
    public UUID getOrGenerateUuid()
    {
        if (uuid != null)
            return uuid;
        return getUsernameUuid();
    }

    // UUID derived from the username
    public UUID getUsernameUuid()
    {
        return UUID.nameUUIDFromBytes(username.getBytes());
    }

    public GameProfile getGameProfile()
    {
        Player player = getPlayer();
        if (player != null)
        {
            if (!player.getGameProfile().isComplete())
            {
                return new GameProfile(getOrGenerateUuid(), player.getDisplayName().getString());

                /*
                 * // Safeguard against stupid mods who set UUID to null UserIdent playerIdent = UserIdent.byUsername.get(player.getCommandSenderName()); if (playerIdent != this)
                 * return playerIdent.getGameProfile();
                 */
            }
            else
            {
                return player.getGameProfile();
            }
        }
        return new GameProfile(getOrGenerateUuid(), username);
    }

    public static UserIdent fromString(String string)
    {
        if (string.charAt(0) != '(' || string.charAt(string.length() - 1) != ')' || string.indexOf('|') < 0)
            throw new IllegalArgumentException("UserIdent string needs to be in the format \"(<uuid>|<username>)\"");
        String[] parts = string.substring(1, string.length() - 1).split("\\|", 2);
        try
        {
            return get(UUID.fromString(parts[0]), parts[1]);
        }
        catch (IllegalArgumentException e)
        {
            return get((UUID) null, parts[1]);
        }
    }

    public String toSerializeString()
    {
        return "(" + (uuid == null ? "" : uuid.toString()) + "|" + (username != null ? username : "") + ")";
    }

    @Override
    public String toString()
    {
        return toSerializeString();
    }

    @Override
    public int hashCode()
    {
        if (hashCode != 0)
            return hashCode;
        return hashCode = getOrGenerateUuid().hashCode();
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        else if (other instanceof UserIdent)
        {
            // It might happen, that one UserIdent was previously initialized by username
            // and another one by UUID, but
            // after the player in question logged in, they still become equal.
            UserIdent ident = (UserIdent) other;
            if (uuid != null && ident.uuid != null)
                return uuid.equals(ident.uuid);
            if (username != null && ident.username != null)
                return username.equalsIgnoreCase(ident.username);
            return false;
        }
        else if (other instanceof String)
        {
            if (this.uuid != null)
            {
                try
                {
                    return this.uuid.equals(UUID.fromString((String) other));
                }
                catch (IllegalArgumentException e)
                {
                    // The string was a username and not a UUID
                }
            }
            return username != null && this.username.equalsIgnoreCase((String) other);
        }
        else if (other instanceof UUID)
        {
            return other.equals(uuid);
        }
        else if (other instanceof Player)
        {
            return ((Player) other).getGameProfile().getId().equals(uuid);
        }
        else
        {
            return false;
        }
    }

    public boolean checkPermission(String permissionNode)
    {
        return APIRegistry.perms.checkUserPermission(this, permissionNode);
    }

    public String getPermissionProperty(String permissionNode)
    {
        return APIRegistry.perms.getUserPermissionProperty(this, permissionNode);
    }

    public static Player getPlayerByUsername(String username)
    {
        MinecraftServer mc = ServerLifecycleHooks.getCurrentServer();
        if (mc == null)
            return null;
        PlayerList configurationManager = mc.getPlayerList();
        return configurationManager == null ? null : configurationManager.getPlayerByName(username);
    }

    public static Player getPlayerByMatchOrUsername(CommandSourceStack sender, String match)
    {
        if (match == null)
            return null;
        try
        {
            // A target selector (@s, @p, @e[...], @a, @r) resolves relative to the sender. Only these opt the
            // sender into being a candidate result. A plain name must NEVER fall back to the sender, otherwise an
            // unresolvable name would silently act on whoever typed the command (see get(String, sender, mustExist)).
            if (match.startsWith("@"))
            {
                EntitySelectorParser parser = new EntitySelectorParser(new StringReader(match));
                EntitySelector selector = parser.parse();
                for (ServerPlayer selected : selector.findPlayers(sender))
                    return selected;
                return null;
            }
            return getPlayerByUsername(match);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static Player getPlayerByUuid(UUID uuid)
    {
        for (Player player : ServerUtil.getPlayerList())
            if (player.getGameProfile().getId().equals(uuid))
                return player;
        return null;
    }

    public static GameProfile getGameProfileByUuid(UUID uuid)
    {
        return ServerLifecycleHooks.getCurrentServer().getProfileCache().get(uuid).orElse(null);
    }

    public static String join(Iterable<UserIdent> users, String glue)
    {
        StringBuilder sb = new StringBuilder();
        Iterator<UserIdent> it = users.iterator();
        if (it.hasNext())
        {
            while (true)
            {
                UserIdent next = it.next();
                sb.append(next == null ? "server" : next.getUsernameOrUuid());
                if (it.hasNext())
                    sb.append(glue);
                else
                    break;
            }
        }
        return sb.toString();
    }

    public static String join(Iterable<UserIdent> users, String glue, String lastGlue)
    {
        StringBuilder sb = new StringBuilder();
        Iterator<UserIdent> it = users.iterator();
        if (it.hasNext())
        {
            UserIdent next = it.next();
            while (true)
            {
                sb.append(next == null ? "server" : next.getUsernameOrUuid());
                if (it.hasNext())
                {
                    next = it.next();
                    if (it.hasNext())
                        sb.append(glue);
                    else
                        sb.append(lastGlue);
                }
                else
                    break;
            }
        }
        return sb.toString();
    }

}
