package net.shurui.shuruisutilities.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.commons.selections.Point;
import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.data.v2.Loadable;
import net.shurui.shuruisutilities.util.events.player.SUPlayerEvent.ClientHandshakeEstablished;
import net.shurui.shuruisutilities.util.events.player.SUPlayerEvent.InventoryGroupChange;
import net.shurui.shuruisutilities.util.events.player.SUPlayerEvent.NoPlayerInfoEvent;
import com.google.gson.annotations.Expose;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

public class PlayerInfo implements Loadable
{

    private static HashMap<UUID, PlayerInfo> playerInfoMap = new HashMap<>();

    /* General */

    public final UserIdent ident;

    @Expose(serialize = false)
    private boolean hasSUClient = false;

    /* Teleport */

    private WarpPoint home;

    private WarpPoint lastTeleportOrigin;

    private WarpPoint lastDeathLocation;

    /*
     * WHICH SERVER the two points above were taken on, or null for "this one".
     *
     * A WarpPoint carries a dimension and coordinates and nothing else, which is a complete description of a place
     * on ONE server and an ambiguous one on a network: every shard loads every dimension the modpack registers, so
     * the same dimension exists, empty, on all of them. This record travels with the player in ShardPayload (see
     * toTransferJson), so a point picked up on one shard is read on the next, and without a server id /back could
     * only hand it to TeleportHelper, whose cross-dimension move is caught by ShardDimensions and routed to whoever
     * OWNS that dimension. That is how /back was warping people to the SMP shard instead of returning them to where
     * they actually were (reported 2026-09-20 to 21; see CommandBack).
     *
     * Null is deliberate and means two different, compatible things: a record written before this field existed
     * (Gson allocates PlayerInfo with Unsafe, so no field initializer runs and an absent field deserializes null),
     * and a point taken on a server that is not part of a shard network at all (ShardConfig.selfId() is null there).
     * Both are answered the same way by /back: treat it as local, and refuse rather than guess when the dimension
     * is one this server does not host. An absent id must never degrade to a default server id, which is exactly
     * the mistake that would re-create the bug, because ShardConfig.Values.serverId defaults to "smp".
     *
     * Captured by the SETTERS rather than at each call site on purpose: six different things write a back location
     * (death, every TeleportHelper teleport, /tp, /bed, /spawn and the SU portals) and any one of them forgetting
     * to stamp the server would reproduce the bug for that path alone, silently.
     */
    private String lastTeleportOriginServer;

    private String lastDeathServer;

    private long lastTeleportTime = 0;

    /* MultiWorld Location Fixer */

    private WarpPoint actualLogOutPoint;

    /**
     * The server {@link #getActualLogOutPoint()} was parked on, or null off a shard network. This record TRAVELS in
     * the vault payload with the rest of PlayerInfo, so without the stamp a point parked on one shard is replayed on
     * the next shard the player logs into, teleporting them to coordinates that belong to a different server. That is
     * the replay RespawnHandler documents as the "spawned on Vegeta" bug. Null must be read as "this server", never
     * as a default.
     */
    private String actualLogOutServer;

    /* Selection */

    private Point sel1;

    private Point sel2;

    private String selDim;

    /* Selection wand */

    @Expose(serialize = false)
    private boolean wandEnabled = false;

    @Expose(serialize = false)
    private String wandID;

    /* PvP toggle (consensual PvP; persisted, opt-out so default = true) */

    // Nullable on purpose: records saved before this field existed deserialize as null (Gson uses
    // Unsafe allocation, so the field initializer never runs). afterLoad() normalizes null -> true,
    // preserving the opt-out default for existing players while still persisting an explicit choice.
    private Boolean pvpEnabled = Boolean.TRUE;

    /* Chat channel and social spy (see ChatChannels) */

    // Which channel this player TYPES into: null or absent means public, which is what every existing record
    // deserializes as and the right default for somebody who has never touched a staff command. Held here
    // rather than in a map because PlayerInfo travels with the player in ShardPayload: a map would drop
    // somebody out of staff chat on every shard hop, silently, and send their next line to public chat.
    private String chatChannel;

    // Same reasoning. Nullable, so absent reads as "not spying" for every record written before this existed.
    private Boolean socialSpy;

    // The last person this player exchanged a private message with, remembered so /reply knows who to answer.
    // Held here rather than in a static map for the same reason as the fields above: PlayerInfo travels with the
    // player in ShardPayload, so a reply target survives a relog, a respawn AND a shard hop. A static map lost it
    // on every hop, which is not an edge case on a shard network. The id is what matters (a name can change or be
    // reused); the name rides alongside only so a lookup by presence has something to search and a message can
    // still be addressed by the name the correspondent last used. Both null for a record written before this
    // existed, which reads as "no reply target", exactly as it should.
    private String replyTargetUuid;
    private String replyTargetName;

    /* Inventory groups */

    private Map<String, List<ItemStack>> inventoryGroups = new HashMap<>();

    // New inventory groups, with support for modded custom inventories
    private Map<String, Map<String, List<ItemStack>>> modInventoryGroups = new HashMap<>();

    private String activeInventoryGroup = "default";

    /* Stats / time */

    private long timePlayed = 0;

    @Expose(serialize = false)
    private long timePlayedRef = 0;

    private Date firstLogin = new Date();

    private Date lastLogin = new Date();

    private Date lastLogout;

    @Expose(serialize = false)
    private long lastActivity = System.currentTimeMillis();

    private HashMap<String, Date> namedTimeout = new HashMap<>();

    // one-time kits permanently spent by this player (separate from namedTimeout cooldowns). persisted with the
    // rest of PlayerInfo. deserializes null on pre-field records (afterLoad handles it) = nothing claimed.
    private Set<String> claimedOneTimeKits = new HashSet<>();

    // kits this player has EARNED standing access to, currently only by crossing a prestige level that awards one.
    // An unlock is not a claim: it says the kit is theirs to take from now on, and every claim after it still goes
    // through the kit's own cooldown. Kept apart from claimedOneTimeKits because that set means the exact opposite
    // (spent, never again). deserializes null on pre-field records (afterLoad handles it) = nothing unlocked.
    private Set<String> unlockedKits = new HashSet<>();

    @Expose(serialize = false)
    private boolean noClip = false;

    private PlayerInfo(UUID uuid)
    {
        this.ident = UserIdent.get(uuid);
    }

    @Override
    public void afterLoad()
    {
        if (namedTimeout == null)
            namedTimeout = new HashMap<>();
        if (claimedOneTimeKits == null)
            claimedOneTimeKits = new HashSet<>();
        if (unlockedKits == null)
            unlockedKits = new HashSet<>();
        lastActivity = System.currentTimeMillis();
        if (activeInventoryGroup == null || activeInventoryGroup.isEmpty())
            activeInventoryGroup = "default";

        if (modInventoryGroups == null)
            modInventoryGroups = new HashMap<>();

        // Opt-out default: legacy records (field absent) come back null -> treat as PvP enabled.
        if (pvpEnabled == null)
            pvpEnabled = Boolean.TRUE;

        if (!inventoryGroups.isEmpty())
        {
            // See if we have an inventory to port
            Set<String> groupsToRemove = new HashSet<>();
            for (String name : inventoryGroups.keySet())
            {
                List<ItemStack> portInv = inventoryGroups.get(name);
                if (portInv != null)
                {
                    Map<String, List<ItemStack>> ig = modInventoryGroups.getOrDefault(name, new HashMap<>());
                    if (ig.get("vanilla") == null)
                    {
                        ig.put("vanilla", portInv);
                        groupsToRemove.add(name);
                        modInventoryGroups.put(name, ig);
                    }
                }
            }
            for (String name : groupsToRemove)
            {

                inventoryGroups.remove(name);
            }
            this.save();
        }

    }

    public void save()
    {
        DataManager.getInstance().save(this, ident.getUuid().toString());
    }

    /**
     * This player's SU record, as the same json the local store holds, so it can travel with them.
     *
     * <p>Everything in here is per player and none of it is per world: kit and teleport cooldowns, tempban
     * expiry, one time kit claims, kit unlocks, home, playtime, first and last login, the PvP toggle and the
     * inventory groups. Without it a player hops to another server and finds every cooldown reset, which turns a
     * kit cooldown into "claim it once per server".
     *
     * <p>The {@link #getTimePlayed()} call is not a read, it is the point of the first line: it folds the session
     * so far into the total. {@code timePlayedRef}, the moment the session started, is
     * {@code @Expose(serialize = false)} and so never travels at all, which is what we want (a reference from
     * another server would have this one count the gap between them as time played) but which also means the
     * running session is only carried if it has been folded in FIRST. Without that call a player loses the whole
     * of their current session's playtime every time they change server.
     */
    public synchronized String toTransferJson()
    {
        getTimePlayed();
        return DataManager.toJson(this);
    }

    /**
     * Replace this player's local record with the one that arrived with them.
     *
     * <p>The local file is a cache of the last time they were here; the copy travelling with the player is the
     * real one. Written to disk as well as held in memory, so a crash before their next quit leaves the newer
     * record behind rather than the older one.
     */
    public static void adopt(UUID uuid, String json)
    {
        PlayerInfo incoming = DataManager.fromJson(json, PlayerInfo.class);
        if (incoming == null || incoming.ident == null || !incoming.ident.hasUuid())
            return;
        incoming.afterLoad();
        incoming.timePlayedRef = System.currentTimeMillis();
        incoming.lastActivity = System.currentTimeMillis();
        playerInfoMap.put(uuid, incoming);
        incoming.save();
    }

    public boolean isLoggedIn()
    {
        return ident.hasPlayer();
    }

    public static PlayerInfo get(UUID uuid)
    {
        return get(uuid, null);
    }

    public static PlayerInfo get(UUID uuid, String username)
    {
        PlayerInfo info = playerInfoMap.get(uuid);
        if (info != null)
            return info;

        // Attempt to populate this info with some data from our storage
        info = DataManager.getInstance().load(PlayerInfo.class, uuid.toString());
        if (info != null)
        {
            playerInfoMap.put(uuid, info);
            return info;
        }

        // Create new player info data
        Player player = UserIdent.get(uuid, username).getPlayerMP();
        info = new PlayerInfo(uuid);
        playerInfoMap.put(uuid, info);
        if (player != null)
            APIRegistry.getSUEventBus().post(new NoPlayerInfoEvent(player));
        return info;
    }

    public static PlayerInfo get(Player player)
    {
        return get(player.getGameProfile().getId(), player.getDisplayName().getString());
    }

    public static PlayerInfo get(UserIdent ident)
    {
        if (!ident.hasUuid())
            return null;
        return get(ident.getUuid());
    }

    public static Collection<PlayerInfo> getAll()
    {
        return playerInfoMap.values();
    }

    public static void login(UUID uuid)
    {
        PlayerInfo pi = get(uuid);
        pi.lastActivity = System.currentTimeMillis();
        pi.timePlayedRef = System.currentTimeMillis();
        pi.lastLogin = new Date();
    }

    public static void logout(UUID uuid)
    {
        if (!playerInfoMap.containsKey(uuid))
            return;
        PlayerInfo pi = playerInfoMap.remove(uuid);
        pi.getTimePlayed();
        pi.lastLogout = new Date();
        pi.timePlayedRef = 0;
        pi.save();
    }

    public static boolean exists(UUID uuid)
    {
        if (playerInfoMap.containsKey(uuid))
            return true;
        return DataManager.getInstance().exists(PlayerInfo.class, uuid.toString());
    }

    // unload + save
    public static void discard(UUID uuid)
    {
        PlayerInfo info = playerInfoMap.remove(uuid);
        if (info != null)
            info.save();
    }

    public static void discardAll()
    {
        for (PlayerInfo info : playerInfoMap.values())
            info.save();
        playerInfoMap.clear();
    }

    public Date getFirstLogin()
    {
        return firstLogin;
    }

    public Date getLastLogin()
    {
        return lastLogin;
    }

    public Date getLastLogout()
    {
        return lastLogout;
    }

    public long getTimePlayed()
    {
        if (isLoggedIn() && timePlayedRef != 0)
        {
            timePlayed += System.currentTimeMillis() - timePlayedRef;
            timePlayedRef = System.currentTimeMillis();
        }
        return timePlayed;
    }

    public void setActive()
    {
        lastActivity = System.currentTimeMillis();
    }

    public void setActive(long delta)
    {
        lastActivity = System.currentTimeMillis() - delta;
    }

    public long getInactiveTime()
    {
        return System.currentTimeMillis() - lastActivity;
    }

    /* Timeouts */

    public void removeTimeout(String name)
    {
        namedTimeout.remove(name);
    }

    // true if the named timeout has passed (or was never set)
    public boolean checkTimeout(String name)
    {
        Date timeout = namedTimeout.get(name);
        if (timeout == null)
            return true;
        if (timeout.after(new Date()))
            return false;
        namedTimeout.remove(name);
        return true;
    }

    // remaining ms
    public long getRemainingTimeout(String name)
    {
        Date timeout = namedTimeout.get(name);
        if (timeout == null)
            return 0;
        return timeout.getTime() - new Date().getTime();
    }

    // start a named timeout; poll with checkTimeout
    public void startTimeout(String name, long milliseconds)
    {
        Date date = new Date();
        date.setTime(date.getTime() + milliseconds);
        namedTimeout.put(name, date);
    }

    /* One-time kit claims */

    // permanent claim, ignores cooldowns
    public boolean hasClaimedOneTimeKit(String kitName)
    {
        return claimedOneTimeKits != null && claimedOneTimeKits.contains(kitName);
    }

    // claim forever + persist now (atomic temp+rename), so it survives an unclean logout
    public void markOneTimeKitClaimed(String kitName)
    {
        if (claimedOneTimeKits == null)
            claimedOneTimeKits = new HashSet<>();
        if (claimedOneTimeKits.add(kitName))
            save();
    }

    /* Earned kit unlocks */

    /**
     * Whether this player has earned standing access to a kit.
     *
     * <p>An unlock stands in for the two gates that decide who may ask for a kit at all, the per-kit
     * {@code su.command.kit.<name>} node and the kit's group lock, and for nothing else. It deliberately does not
     * touch the cooldown: the whole point of earning a kit is that you may keep coming back for it on the kit's own
     * schedule, not that you may take it whenever you like.
     */
    public boolean hasUnlockedKit(String kitName)
    {
        return unlockedKits != null && unlockedKits.contains(kitName);
    }

    /** Grant standing access forever + persist now, so an unclean logout cannot cost a player a kit they earned. */
    public void markKitUnlocked(String kitName)
    {
        if (unlockedKits == null)
            unlockedKits = new HashSet<>();
        if (unlockedKits.add(kitName))
            save();
    }

    /* Wand */

    public boolean isWandEnabled()
    {
        return wandEnabled;
    }

    public void setWandEnabled(boolean wandEnabled)
    {
        this.wandEnabled = wandEnabled;
    }

    /* PvP toggle */

    // default + legacy records = true (opt-out)
    public boolean isPvpEnabled()
    {
        return pvpEnabled == null || pvpEnabled;
    }

    // persist immediately
    public void setPvpEnabled(boolean v)
    {
        this.pvpEnabled = v;
        save();
    }

    /* Chat channel and social spy */

    /** Null means public chat, for a record written before this existed as much as for a normal player. */
    public String getChatChannel()
    {
        return chatChannel;
    }

    public void setChatChannel(String v)
    {
        this.chatChannel = v;
        save();
    }

    public boolean isSocialSpy()
    {
        return socialSpy != null && socialSpy;
    }

    public void setSocialSpy(boolean v)
    {
        this.socialSpy = v;
        save();
    }

    /** The id of the last person this player messaged, or null when there has been nobody. */
    public String getReplyTargetUuid()
    {
        return replyTargetUuid;
    }

    /** The name the reply target last went by, kept only so a presence lookup and a display line have one. */
    public String getReplyTargetName()
    {
        return replyTargetName;
    }

    /** Remember who to reply to. Persisted so it outlives a relog, a respawn and a shard hop (see the field). */
    public void setReplyTarget(String uuid, String name)
    {
        this.replyTargetUuid = uuid;
        this.replyTargetName = name;
        save();
    }

    public String getWandID()
    {
        return wandID;
    }

    public void setWandID(String wandID)
    {
        this.wandID = wandID;
    }

    /* Selection */

    public Point getSel1()
    {
        return sel1;
    }

    public Point getSel2()
    {
        return sel2;
    }

    public String getSelDim()
    {
        return selDim;
    }

    public void setSel1(Point point)
    {
        sel1 = point;
    }

    public void setSel2(Point point)
    {
        sel2 = point;
    }

    public void setSelDim(String dim)
    {
        selDim = dim;
    }

    /* Inventory groups */

    public Map<String, Map<String, List<ItemStack>>> getModInventoryGroups()
    {
        return modInventoryGroups;
    }

    public List<ItemStack> getInventoryGroupItems(String name, String mod)
    {
        return modInventoryGroups.get(name).get(mod);
    }

    public String getInventoryGroup()
    {
        return activeInventoryGroup;
    }

    public void setInventoryGroup(String name)
    {
        if (!activeInventoryGroup.equals(name))
        {
            // Get the new inventory
            Map<String, List<ItemStack>> newInventory = modInventoryGroups.get(name);
            if (newInventory == null)
                newInventory = new HashMap<>();

            // ChatOutputHandler.sulog.info(String.format("Changing inventory group for %s
            // from %s to %s",
            // ident.getUsernameOrUUID(), activeInventoryGroup, name));
            /*
             * ChatOutputHandler.sulog.info("Items in old inventory:"); for (int i = 0; i < ident.getPlayer().inventory.getSizeInventory(); i++) { ItemStack itemStack =
             * ident.getPlayer().inventory.getStackInSlot(i); if (itemStack != ItemStack.EMPTY) ChatOutputHandler.sulog.info("  " + itemStack.getDisplayName()); }
             * ChatOutputHandler.sulog.info("Items in new inventory:"); for (ItemStack itemStack : newInventory) if (itemStack != ItemStack.EMPTY) ChatOutputHandler.sulog.info("  "
             * + itemStack.getDisplayName());
             */

            // Swap player inventory and store the old one
            newInventory.put("vanilla", PlayerUtil.swapInventory(this.ident.getPlayerMP(),
                    newInventory.getOrDefault("vanilla", new ArrayList<>())));
            MinecraftForge.EVENT_BUS.post(new InventoryGroupChange(ident.getPlayer(), name, newInventory));
            modInventoryGroups.put(activeInventoryGroup, newInventory);
            // Clear the inventory-group that was assigned to the player (optional)
            modInventoryGroups.put(name, null);
            // Save the new active inventory-group
            activeInventoryGroup = name;
            this.save();
        }
    }

    /* Teleportation */

    public WarpPoint getLastTeleportOrigin()
    {
        return lastTeleportOrigin;
    }

    /**
     * The server {@link #getLastTeleportOrigin()} was taken on, or null when it was taken off a shard network or
     * before the id was recorded. See the field comment: null must be read as "this server", never as a default.
     */
    public String getLastTeleportOriginServer()
    {
        return lastTeleportOriginServer;
    }

    public void setLastTeleportOrigin(WarpPoint lastTeleportStart)
    {
        this.lastTeleportOrigin = lastTeleportStart;
        // Stamped here, once, for every writer. Null point means null server, so clearing one clears the other and
        // a stale id can never outlive the point it described.
        this.lastTeleportOriginServer = lastTeleportStart == null
                ? null
                : net.shurui.shuruisutilities.shard.ShardConfig.selfId();
    }

    public WarpPoint getLastDeathLocation()
    {
        return lastDeathLocation;
    }

    /** The server {@link #getLastDeathLocation()} was taken on. Same null meaning as above. */
    public String getLastDeathServer()
    {
        return lastDeathServer;
    }

    public void setLastDeathLocation(WarpPoint lastDeathLocation)
    {
        this.lastDeathLocation = lastDeathLocation;
        this.lastDeathServer = lastDeathLocation == null
                ? null
                : net.shurui.shuruisutilities.shard.ShardConfig.selfId();
    }

    public long getLastTeleportTime()
    {
        return lastTeleportTime;
    }

    public void setLastTeleportTime(long currentTimeMillis)
    {
        this.lastTeleportTime = currentTimeMillis;
    }

    public WarpPoint getHome()
    {
        return home;
    }

    public void setHome(WarpPoint home)
    {
        this.home = home;
    }

    /* Other */

    public boolean getHasSUClient()
    {
        return hasSUClient;
    }

    public void setHasSUClient(boolean status)
    {
        this.hasSUClient = status;
        if(status) {
            APIRegistry.getSUEventBus().post(new ClientHandshakeEstablished(this.ident.getPlayer()));
        }
    }

    public boolean isNoClip()
    {
        return noClip;
    }

    public void setNoClip(boolean noClip)
    {
        this.noClip = noClip;
    }

	public WarpPoint getActualLogOutPoint() {
		return actualLogOutPoint;
	}

	public void setActualLogOutPoint(WarpPoint actualLogOutPoint) {
		this.actualLogOutPoint = actualLogOutPoint;
		// Stamped here, once, for every writer, the same discipline setLastTeleportOrigin uses. A null point clears
		// the server too, so a stale id can never outlive the point it described.
		this.actualLogOutServer = actualLogOutPoint == null
				? null
				: net.shurui.shuruisutilities.shard.ShardConfig.selfId();
	}

	/**
	 * The server the parked logout point belongs to, or null when it was parked off a shard network. Read null as
	 * "this server", never as a default: see the field comment.
	 */
	public String getActualLogOutServer() {
		return actualLogOutServer;
	}

	/**
	 * True when a parked logout point exists AND belongs to the server asking. Off a shard network both sides are
	 * null and this is simply "is there a point", which is the pre-shard behaviour unchanged.
	 */
	public boolean actualLogOutPointIsOurs() {
		if (actualLogOutPoint == null)
			return false;
		String here = net.shurui.shuruisutilities.shard.ShardConfig.selfId();
		return here == null || actualLogOutServer == null || here.equals(actualLogOutServer);
	}
}
