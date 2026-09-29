package net.shurui.shuruisutilities.model;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side lifecycle for the look-only {@code /model} override: bind the persisted store and register the shard
 * sync when the server starts, push the whole map to a joining client, and detach on stop so a restart does not read
 * a stale in-memory store. Self-contained (its own explicit-modid FORGE subscriber) so it adds nothing to the core
 * boot path beyond the two packet registrations.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class ModelEvents
{
    private ModelEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        ModelState.bind(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event)
    {
        ModelState.unbind();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp)
        {
            MinecraftServer server = sp.getServer();
            if (server != null)
                ModelState.syncTo(sp);
        }
    }
}
