package net.shurui.shuruisutilities.ritual;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.compat.dmz.RitualFormsCompat;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The Super Saiyan God charge ritual: a ring of saiyans charging ki around a centre saiyan for a sustained window. How
 * many chargers the ring needs (besides the centre) is the {@code SsgRitualRequiredChargers} config value, default five.
 * When the ring holds, the centre saiyan turns Super Saiyan God temporarily, and every participant (the centre and the
 * chargers) permanently unlocks the ability to PURCHASE the form for its TP cost (the durable entitlement lives in
 * {@link WishRitualStore}). The centre's temporary form is reverted the moment they leave it, so it costs nothing to
 * keep past the ritual.
 *
 * <p>What is reverted is only ever what the ritual GAVE. A centre who already owns Super Saiyan God (bought for its
 * 150000 TP) keeps it: the grant is marked at the moment it is made ({@link WishRitualStore#SSG_TEMP_GRANT}) and the
 * cleanup revokes nothing it did not mark, because in DMZ a purchase and a grant are the same state and there is
 * nothing else to tell them apart by.
 *
 * <p>Every exit is handled: the temporary grant is reverted on detransform, on leaving the world (offline centre), on
 * death (the active form clears, read as a detransform) and never leaks the {@code godforms} skill; the durable
 * entitlement is granted at the moment the ring completes, so a participant who disconnects immediately afterwards keeps
 * what they earned. Registered once on the Forge server bus at mod init. Throttled; the no-ritual case is free.
 */
public final class SsgRitualManager
{
    /** How close a charger must be to the centre to count as part of the ring. */
    private static final double RING_RADIUS = 6.0D;
    /** How long the ring must hold, in server ticks. */
    private static final int HOLD_TICKS = 60;
    /** Check cadence in server ticks; progress accrues in these increments. */
    private static final int CHECK_INTERVAL = 10;

    // Centre UUID -> accumulated sustained ticks while its ring is valid. Cleared the moment the ring breaks.
    private static final Map<UUID, Integer> progress = new HashMap<>();
    // Centre UUID -> the participants of an in-flight temporary SSG, tracked until the centre detransforms/leaves.
    private static final Map<UUID, Set<UUID>> active = new HashMap<>();

    private int tickCounter;

    /**
     * Repair a player who logs in already stranded in an unresolvable active form, in any form group.
     *
     * <p>This is a live-server repair, not a theoretical one. The stranded state is the character pointing at a
     * form NAME its group cannot resolve (the live case was {@code godforms/ssg}, a name in no JSON anywhere), which DMZ
     * cannot resolve, so its effect handler warns about it on EVERY tick and never stops: one stranded player produced
     * 122,860 identical warnings in a single 2h43m session, which is a string format and a log write on the server
     * thread twenty times a second, and a log nobody can read. The spam then moved to a second account carrying the same
     * stale form. Nothing clears the state on its own, so it survives relogs, deaths and restarts, and the only way an
     * already-affected player gets out of it is a repair like this one.
     *
     * <p>Login is the right moment: the player is loaded, and they cannot be mid-ritual. The repair is group-agnostic
     * because per-shard config drift can strand a player in a group other than {@code godforms}, but it stays narrow by
     * asking DMZ's own resolver and clearing only a form whose group RESOLVES while the form does not, so a player
     * legitimately holding a purchased form, or one whose group is merely absent for now, is never touched.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            RitualFormsCompat.healStrandedForm(player);
            // Same moment, the other half of the repair: forget a temporary-grant marker whose grant is already gone
            // (a character reset, an admin edit), so it can never be spent on a form the player buys afterwards.
            RitualFormsCompat.reconcileTempSsgGrant(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END)
            return;
        if (++tickCounter < CHECK_INTERVAL)
            return;
        tickCounter = 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            progress.clear();
            active.clear();
            return;
        }
        try
        {
            reapActive(server);
            scanForRings(server);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] SSG tick failed: {}", t.toString());
        }
    }

    // Revert any temporary SSG whose centre has left the form or the game. The entitlement was granted at trigger, so
    // reverting only strips the temporary form availability.
    private void reapActive(MinecraftServer server)
    {
        if (active.isEmpty())
            return;
        Iterator<Map.Entry<UUID, Set<UUID>>> it = active.entrySet().iterator();
        while (it.hasNext())
        {
            UUID centre = it.next().getKey();
            ServerPlayer p = server.getPlayerList().getPlayer(centre);
            if (p == null)
            {
                // Offline, so there is no player object to revert against and tracking is dropped. The grant itself is
                // NOT lost when they go: it lives in DMZ's persisted stats, so a centre who logs out mid-form keeps the
                // temporary form until something clears it. That is deliberate (they earned the entitlement anyway) and
                // it is safe, because the form still RESOLVES while the skill is up. The state that used to be a
                // problem, the form set with the skill already back at 0, is now both prevented in
                // RitualForms.endTempSsg and repaired on login by onPlayerLoggedIn.
                it.remove();
                continue;
            }
            if (!RitualFormsCompat.isInSsg(p))
            {
                RitualFormsCompat.endTempSsg(p);
                it.remove();
            }
        }
    }

    private void scanForRings(MinecraftServer server)
    {
        List<ServerPlayer> chargers = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (RitualFormsCompat.isSaiyanChargingKi(p))
                chargers.add(p);

        // Drop progress for any centre that is no longer a valid, charging saiyan.
        progress.keySet().removeIf(id -> chargers.stream().noneMatch(c -> c.getUUID().equals(id)));

        // How many chargers (besides the centre) the ring needs, read live from the config so a reload applies at once.
        // Clamped to at least one so a bad value can never make the ritual complete with the centre alone.
        int requiredChargers = Math.max(1, SUConfig.ssgRitualRequiredChargers);
        if (chargers.size() <= requiredChargers)
            return; // need a centre plus at least this many others

        for (ServerPlayer centre : chargers)
        {
            if (active.containsKey(centre.getUUID()))
                continue; // already mid-ritual
            // The ritual is knowledge you wish Shenron for, not something five saiyans can stumble into. Checked on
            // the CENTRE only: the ring are helpers and earn the purchase right by taking part, they do not each
            // need to have wished. Checked before the ring is even measured so an unqualified centre costs nothing.
            if (!WishRitualStore.hasSsgKnowledge(centre))
            {
                progress.remove(centre.getUUID());
                continue;
            }
            Set<UUID> ring = ringAround(centre, chargers);
            if (ring.size() < requiredChargers)
            {
                progress.remove(centre.getUUID());
                continue;
            }
            int held = progress.getOrDefault(centre.getUUID(), 0) + CHECK_INTERVAL;
            if (held < HOLD_TICKS)
            {
                progress.put(centre.getUUID(), held);
                continue;
            }
            trigger(centre, ring);
            progress.remove(centre.getUUID());
        }
    }

    // The set of OTHER chargers within the ring radius of the centre, same dimension.
    private Set<UUID> ringAround(ServerPlayer centre, List<ServerPlayer> chargers)
    {
        Set<UUID> ring = new HashSet<>();
        for (ServerPlayer other : chargers)
        {
            if (other == centre || other.level() != centre.level())
                continue;
            if (other.distanceToSqr(centre) <= RING_RADIUS * RING_RADIUS)
                ring.add(other.getUUID());
        }
        return ring;
    }

    private void trigger(ServerPlayer centre, Set<UUID> ring)
    {
        if (!RitualFormsCompat.beginTempSsg(centre))
            return; // centre is not an eligible saiyan (e.g. race changed mid-charge)

        // Everyone who took part unlocks the purchase now, so a disconnect right after does not cost them the reward.
        WishRitualStore.grantSsgPurchase(centre);
        centre.sendSystemMessage(Component.translatable("ritual.dmz_ragnarok.ssg.centre"));
        MinecraftServer server = centre.getServer();
        for (UUID id : ring)
        {
            ServerPlayer participant = server == null ? null : server.getPlayerList().getPlayer(id);
            if (participant != null)
            {
                WishRitualStore.grantSsgPurchase(participant);
                participant.sendSystemMessage(Component.translatable("ritual.dmz_ragnarok.ssg.participant"));
            }
        }

        active.put(centre.getUUID(), new HashSet<>(ring));
        LoggingHandler.sulog.info("[ritual] SSG charge ritual completed for {} with {} participants",
                centre.getGameProfile().getName(), ring.size());
    }
}
