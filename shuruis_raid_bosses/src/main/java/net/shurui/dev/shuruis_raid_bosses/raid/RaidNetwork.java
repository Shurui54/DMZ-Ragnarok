package net.shurui.dev.shuruis_raid_bosses.raid;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.shard.ShardEvents;

/**
 * Makes raids NETWORK events on a shard network, leaving a single server exactly as it was. Once
 * {@link ShardEvents#active()}:
 * <ul>
 *   <li>A scheduled raid elects one host: every open world enters the election, the winner opens
 *       sign-up ({@link #handleScheduledOpen}). Manual {@code /rg raid open} still opens on the server
 *       it ran on and records itself host via {@link RaidInstance#openSignups}.</li>
 *   <li>Joining routes to the host ({@link #routeJoin}, {@link #onArrival}).</li>
 *   <li>Announcements cross the network, handled in {@code Announcer}, not here.</li>
 * </ul>
 *
 * <p>Registered under {@link ShardEvents} kind {@code "raid"}. Boss LOCATIONS travel on
 * {@code RaidStateSync}, and the open worlds hold copies of the same terrain, so one agreed arena is
 * valid on all of them.
 */
public final class RaidNetwork
{
    private RaidNetwork() {}

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static final String KIND = "raid";

    /**
     * Handle a scheduled sign-up opening. Returns true when the slot is CONSUMED (scheduler stamps its
     * per-server last-run clock and does not fire it again).
     *
     * <ul>
     *   <li>Shard off: opens locally, consumes only on success.</li>
     *   <li>Shard on, not an eligible host (SMP or hub): consumes without opening; those never run raids.</li>
     *   <li>Shard on, eligible open world: enters the election and consumes regardless of outcome, since
     *       the election is now the authority; exactly one open world wins and opens it.</li>
     * </ul>
     */
    public static boolean handleScheduledOpen(RaidManager manager, MinecraftServer server, String defId, int minutes,
                                              long slotMillis)
    {
        if (!ShardEvents.active())
            return manager.openSignups(defId, minutes);
        if (!ShardEvents.eligibleHost())
            return true; // an SMP or hub does not host raids; spend the slot and move on
        int players = server.getPlayerList().getPlayerCount();
        ShardEvents.submitElection(KIND, defId, slotMillis, players,
                () -> manager.openSignups(defId, minutes));
        return true;
    }

    /**
     * If this raid runs on another server, send the player there and report a hop started. False means
     * join here as normal.
     */
    public static boolean routeJoin(ServerPlayer player, String defId)
    {
        if (!ShardEvents.active() || defId == null)
            return false;
        ShardEvents.Host host = ShardEvents.hostOf(KIND, defId);
        if (host == null)
        {
            // No host in the directory. Either no open world has opened this raid yet, or the host row aged out
            // of the directory. When this raid is not running on THIS shard either, the player is about to be
            // refused with a bare "closed" and no other trace, so record why.
            LOGGER.info("[raid-route] {} asked to join '{}' but no shard is listed as its host; "
                    + "falling back to a local join.", player.getGameProfile().getName(), defId);
            return false;
        }
        if (host.isSelf())
            return false;
        // only route while sign-ups are open there; an in-progress raid is not joinable, so a local join
        // returns CLOSED instead of hopping the player pointlessly
        if (!"SIGNUP".equals(host.phase()))
        {
            LOGGER.info("[raid-route] {} asked to join '{}' hosted on {}, but its phase is {} not SIGNUP; "
                    + "not hopping.", player.getGameProfile().getName(), defId, host.server(), host.phase());
            return false;
        }
        LOGGER.info("[raid-route] Routing {} to '{}' hosted on {}.",
                player.getGameProfile().getName(), defId, host.server());
        return ShardEvents.routeToEvent(player, KIND, defId, host.server());
    }

    /** record THIS server as the raid's host, gated on being an eligible open world */
    public static void setHost(String defId, String phase)
    {
        ShardEvents.setHost(KIND, defId, phase);
    }

    public static void clearHost(String defId)
    {
        ShardEvents.clearHost(KIND, defId);
    }

    /**
     * A player arrived carrying a pending raid join (clicked Join on a non-host server). Join them locally
     * and place them in the arena. Called from the login handler after the vault payload is in place.
     */
    public static void onArrival(ServerPlayer player)
    {
        if (!ShardEvents.active())
            return;
        ShardEvents.Pending pending = ShardEvents.consumePending(player);
        if (pending == null || !KIND.equals(pending.kind()))
            return;
        RaidManager manager = RaidManager.get();
        if (manager == null)
            return;
        String defId = pending.defId();
        switch (manager.join(defId, player))
        {
            case OK ->
            {
                RaidInstance inst = manager.instance(defId);
                if (inst != null)
                    inst.placeInArena(player);
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "signup.dmz_ragnarok.raid.joined"));
            }
            case ALREADY ->
            {
                RaidInstance inst = manager.instance(defId);
                if (inst != null)
                    inst.placeInArena(player);
            }
            case CLOSED, FULL -> player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "signup.dmz_ragnarok.raid.closed"));
            case NO_CHARACTER -> player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "command.dmz_ragnarok.raid.no_character"));
            case NO_SUCH_RAID -> { /* the raid ended between clicking and arriving; nothing to say */ }
        }
    }
}
