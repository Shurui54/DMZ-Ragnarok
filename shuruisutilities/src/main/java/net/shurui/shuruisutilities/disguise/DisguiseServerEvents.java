package net.shurui.shuruisutilities.disguise;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Core-side lifecycle for the disguise system (the state and sync live in core, only the impersonation logic is in the
 * key). Binds the per-player merge store at server start and applies the active disguises around a join.
 *
 * <h2>Fully persistent: no clear on logout of any kind.</h2>
 * A disguise persists across a shard hop AND across a genuine logout/login (owner rule): it is removed only by an
 * explicit {@code /disguise clear}, or toggled off by {@code /disguise off} (which keeps the stored view). So this
 * class does NOT clear anything on {@code PlayerLoggedOutEvent}; the state simply stays in the shard-synced store
 * ({@code DisguiseState}) until staff remove it. That also sidesteps the "a handoff is a disconnect" trap entirely,
 * since there is nothing to clean up on a leave.
 *
 * <h2>Applied early on join.</h2>
 * On login the joining client is sent the full snapshot of active disguises (so it draws everyone correctly), and the
 * joining player's OWN active disguise is re-broadcast to everyone already online, so the disguised identity is in
 * place the moment they appear (tab, nametag, chat) with no flash of the real name. Because the disguise lives in
 * server-side persistent state, it is already present when the join / leave message is composed, so those messages
 * read the disguised name without any ordering dependency on this handler.
 *
 * <p>Explicit {@code modid=dmz_ragnarok} (core tree), FORGE bus.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class DisguiseServerEvents
{
    private DisguiseServerEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        DisguiseState.bind(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event)
    {
        DisguiseState.unbind();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer sp))
            return;
        MinecraftServer server = sp.getServer();
        // Send the joining client every disguise already in effect, then push this player's own disguise (if active)
        // to everyone else so nobody sees a frame of the real name.
        DisguiseState.syncTo(sp);
        if (server != null)
            DisguiseState.rebroadcast(server, sp.getUUID());
    }
}
