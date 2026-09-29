package net.shurui.shuruisutilities.disguise;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.audit.AuditLog;
import net.shurui.shuruisutilities.shard.ShardExecutor;
import net.shurui.shuruisutilities.shard.ShardPresence;
import net.shurui.shuruisutilities.shard.ShardSync;

/**
 * Core-side lifecycle for the disguise system (the state and sync live in core, only the impersonation logic is in the
 * key). Binds the per-player merge store at server start, streams the active disguises to a joining client, and clears
 * a disguise ONLY when its owner has genuinely left the network.
 *
 * <h2>The hop-vs-quit rule.</h2>
 * A shard hop is a logout here and a login there within milliseconds, so clearing on {@code PlayerLoggedOutEvent}
 * would unmask staff on every hop (the "a handoff is a disconnect" trap). Instead, a logout that {@link ShardSync}
 * already knows is a handoff is ignored, and any other logout SCHEDULES a check {@link #GRACE_TICKS} ticks later that
 * clears the disguise only if the player is still not the local player and {@link ShardPresence#isOnlineAnywhere} says
 * they turned up nowhere on the network (which also covers a non-seamless hop that reconnects a moment later). A
 * reconnect, here or on a sibling, cancels the pending clear.
 *
 * <p>Explicit {@code modid=dmz_ragnarok} (core tree), FORGE bus.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class DisguiseServerEvents
{
    // Long on purpose: covers a slow non-seamless hop and a two-shard failover bounce before deciding a player has
    // truly gone, the same reasoning the leave-announce and rgnpc-offer windows use.
    private static final long GRACE_TICKS = 200L;

    // realId -> overworld game time at which to re-check for a genuine leave.
    private static final Map<UUID, Long> PENDING_CLEAR = new ConcurrentHashMap<>();

    private DisguiseServerEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        DisguiseState.bind(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event)
    {
        PENDING_CLEAR.clear();
        DisguiseState.unbind();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp)
        {
            // A (re)join cancels any pending genuine-leave clear for this player, and re-syncs them the live set.
            PENDING_CLEAR.remove(sp.getUUID());
            DisguiseState.syncTo(sp);
        }
    }

    // HIGH, above ShardSync's NORMAL logout handler, which CONSUMES the hand-off mark: read after it, every hop would
    // look like a quit (the same reason DragonBallHopGuard runs at HIGH).
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer sp))
            return;
        UUID id = sp.getUUID();
        MinecraftServer server = sp.getServer();
        if (server == null || !DisguiseState.isDisguised(id))
            return;
        // A hop this server already recognises is not a leave: keep the disguise so it survives the transfer.
        if (ShardSync.isHandingOff(id))
            return;
        long due = gameTime(server) + GRACE_TICKS;
        PENDING_CLEAR.put(id, due);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || PENDING_CLEAR.isEmpty())
            return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        long now = gameTime(server);
        Iterator<Map.Entry<UUID, Long>> it = PENDING_CLEAR.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, Long> e = it.next();
            if (now < e.getValue())
                continue;
            UUID id = e.getKey();
            it.remove();
            // Reconnected here, or is mid-handoff again: not a genuine leave.
            if (server.getPlayerList().getPlayer(id) != null || ShardSync.isHandingOff(id))
                continue;
            if (!ShardSync.active())
            {
                // No shard network (single server): a quit that did not come back is a genuine leave.
                clearIfStillGone(server, id);
                continue;
            }
            // Turned up on a sibling? The presence read is a database round trip, so it runs on the vault thread and
            // the answer comes back to the server thread.
            ShardExecutor.submit(id, () ->
            {
                boolean online;
                try
                {
                    online = ShardPresence.isOnlineAnywhere(id);
                }
                catch (Throwable t)
                {
                    online = false;
                }
                if (!online)
                    server.execute(() -> clearIfStillGone(server, id));
            });
        }
    }

    private static void clearIfStillGone(MinecraftServer server, UUID id)
    {
        if (server.getPlayerList().getPlayer(id) != null || PENDING_CLEAR.containsKey(id))
            return; // came back (here) while the check ran, or a newer logout is already pending
        if (DisguiseState.clear(server, id))
            AuditLog.log("DISGUISE off: %s (left the network)", id);
    }

    private static long gameTime(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld == null ? server.getTickCount() : overworld.getGameTime();
    }
}
