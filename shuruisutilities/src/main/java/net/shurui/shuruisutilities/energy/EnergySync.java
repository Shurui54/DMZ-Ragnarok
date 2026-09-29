package net.shurui.shuruisutilities.energy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Refills role bars over time and keeps each player's own client told what their active bar is.
 *
 * <p>Registered MANUALLY on the Forge bus from the mod's main class, not with {@code @Mod.EventBusSubscriber}. After
 * the five addons were merged into one jar, an annotation-driven subscriber keyed to one of the old modids registers
 * nothing and fails SILENTLY, so every handler in this suite is registered by hand.
 *
 * <p>Regen runs on the SERVER tick, once per second rather than every tick, because the bar is a slow resource and a
 * 20x finer update would only multiply the sync traffic for a change no player can perceive.
 */
public final class EnergySync
{
    /** Server ticks between regen steps. One second: see the class note on why this is not per-tick. */
    private static final int REGEN_INTERVAL_TICKS = 20;

    /**
     * How far the displayed value must move before a push is worth sending, in bar points.
     *
     * <p>The HUD track is about a hundred pixels wide at most, so a change under half a point cannot move the fill by
     * a visible amount. Without this floor a continuous drain would push a packet every tick for a bar that looks
     * identical. A change of KIND always pushes regardless, since that repaints the whole track.
     */
    private static final float PUSH_EPSILON = 0.5f;

    // last state actually sent to each player, so a push can be skipped when nothing visible changed. Runtime only:
    // a miss just costs one redundant packet, and the map is rebuilt as players are seen.
    private static final Map<UUID, Sent> lastSent = new HashMap<>();

    private static final class Sent
    {
        EnergyKind kind;
        float value;
    }

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (++tickCounter < REGEN_INTERVAL_TICKS)
            return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null)
            return;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            regen(player);
            push(player);
        }
    }

    /**
     * Refill the player's single shared pool. Runs whether or not they currently hold a role, so time spent without
     * one still counts toward recovery and losing a title does not freeze the bar at whatever it was.
     */
    private static void regen(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        if (server == null)
            return;
        EnergyData data = EnergyData.get(server);
        UUID id = player.getUUID();
        float current = data.getPool(id);
        if (current < EnergyManager.MAX)
            data.setPool(id, current + EnergyManager.REGEN_PER_SECOND);
    }

    /**
     * Send this player their own active bar, if it has visibly changed since the last push.
     *
     * <p>Called both from the tick above and from every spend path in {@link EnergyManager}, so a cast updates the
     * HUD immediately instead of on the next regen step.
     */
    public static void push(ServerPlayer player)
    {
        if (player == null || player.getServer() == null)
            return;
        EnergyKind kind = EnergyManager.activeKind(player);
        float value = kind == null ? 0.0f : EnergyManager.get(player, kind);

        Sent previous = lastSent.get(player.getUUID());
        if (previous != null && previous.kind == kind && Math.abs(previous.value - value) < PUSH_EPSILON)
            return;

        Sent now = previous == null ? new Sent() : previous;
        now.kind = kind;
        now.value = value;
        lastSent.put(player.getUUID(), now);

        NetworkUtils.sendTo(new PacketEnergySync(kind, value), player);
    }

    /** Push once on join so the HUD is correct before anything changes, and forget any stale throttle state. */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            lastSent.remove(player.getUUID());
            push(player);
        }
    }

    /** Drop the throttle row on logout so a returning player is always sent a fresh value. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            lastSent.remove(player.getUUID());
    }
}
