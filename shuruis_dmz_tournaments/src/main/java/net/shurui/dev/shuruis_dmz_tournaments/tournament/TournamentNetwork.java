package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.shard.ShardEvents;

/**
 * Makes tournaments NETWORK events on a shard network, leaving a single server unchanged. Mirror of the raid
 * bridge: a scheduled tournament elects one open world to host (weighted by player count), joining routes the
 * player there, and the host's announcements mirror to every server. The election lives in
 * {@code ShardEvents}, generic over the event "kind"; tournaments register under {@code "tournament"}.
 *
 * <p>Stand/arena locations live in the definition and are shared by {@code TournamentStateSync}, so one agreed set
 * of grounds is valid on whichever open world wins the host.
 */
public final class TournamentNetwork
{
    private TournamentNetwork() {}

    public static final String KIND = "tournament";

    /**
     * Handle a scheduled sign-up opening. Returns true when the slot is CONSUMED. Shard off: opens locally. Shard
     * on but this is the SMP or the hub: consumes without opening. Shard on and this is an open world: enters the
     * network election and consumes; exactly one open world wins and opens it.
     */
    public static boolean handleScheduledOpen(TournamentManager manager, MinecraftServer server, String defId,
                                              int minutes, long slotMillis)
    {
        if (!ShardEvents.active())
            return manager.openSignups(defId, minutes);
        if (!ShardEvents.eligibleHost())
            return true;
        int players = server.getPlayerList().getPlayerCount();
        ShardEvents.submitElection(KIND, defId, slotMillis, players,
                () -> manager.openSignups(defId, minutes));
        return true;
    }

    /** If this tournament is signing up on another server, send the player there. False means join here. */
    public static boolean routeJoin(ServerPlayer player, String defId)
    {
        if (!ShardEvents.active() || defId == null)
            return false;
        ShardEvents.Host host = ShardEvents.hostOf(KIND, defId);
        if (host == null || host.isSelf())
            return false;
        // Only route while sign-ups are open there; a tournament already under way is not joinable.
        if (!"SIGNUP".equals(host.phase()))
            return false;
        return ShardEvents.routeToEvent(player, KIND, defId, host.server());
    }

    public static void setHost(String defId, String phase)
    {
        ShardEvents.setHost(KIND, defId, phase);
    }

    public static void clearHost(String defId)
    {
        ShardEvents.clearHost(KIND, defId);
    }

    /**
     * A player arrived carrying a pending tournament join. Sign them up locally and put them in the waiting area.
     * Called from the login handler once the vault payload is in place.
     */
    public static void onArrival(ServerPlayer player)
    {
        if (!ShardEvents.active())
            return;
        ShardEvents.Pending pending = ShardEvents.consumePending(player);
        if (pending == null || !KIND.equals(pending.kind()))
            return;
        TournamentManager manager = TournamentManager.get();
        if (manager == null)
            return;
        String defId = pending.defId();
        TournamentInstance inst = manager.instance(defId);
        if (inst == null)
            return;
        switch (inst.join(player))
        {
            case OK, ALREADY ->
            {
                inst.teleportToWaiting(player);
                player.sendSystemMessage(net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil.color(
                        "&aYou have signed up!"));
            }
            case CLOSED, FULL -> player.sendSystemMessage(
                    net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil.color("&cSign-ups are not open."));
            case NO_CHARACTER -> player.sendSystemMessage(
                    net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil.color(
                            "&cCreate a DragonMineZ character first."));
            case TITLE_HOLDER -> player.sendSystemMessage(
                    net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil.color(
                            TournamentInstance.titleHolderRefusal(inst.barringTitlesFor(player.getUUID()))));
            case NO_SUCH_TOURNAMENT -> { /* it ended between clicking and arriving */ }
        }
    }
}
