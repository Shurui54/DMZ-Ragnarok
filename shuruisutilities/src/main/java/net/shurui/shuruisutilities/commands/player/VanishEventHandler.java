package net.shurui.shuruisutilities.commands.player;

import net.shurui.shuruisutilities.api.key.VanishHooks;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge-bus handler that keeps vanished players hidden through teleports, dimension changes and respawns. The
 * one-shot hide in {@code CommandVanish#vanish} (in the Ragnarok Key since S18a) only covers players tracking the
 * vanished player at that moment;
 * vanilla re-tracks a player whenever it re-enters an observer's view range (a teleport, dimension change, chunk
 * reload, etc.), which is what made vanished admins pop back into view.
 *
 * <p>{@link PlayerEvent.StartTracking} fires exactly at that re-track. It is not cancelable, so we re-hide on the
 * next tick via {@code server.execute(...)}: stop the observer seeing the target and send a remove-entity packet.
 * Guarded so a vanished player is never hidden from their own client.
 */
public class VanishEventHandler
{

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event)
    {
        if (!(event.getTarget() instanceof ServerPlayer target))
            return;
        if (!(event.getEntity() instanceof ServerPlayer observer))
            return;
        // A viewer who may see this target (themselves, or another vanished player) keeps the freshly tracked
        // entity. Only re-hide from observers who must not see a vanished player. canSee also covers the not
        // vanished and self cases, so no separate guards are needed here.
        if (VanishHooks.canSee(observer, target.getUUID()))
            return;

        MinecraftServer server = target.getServer();
        if (server == null)
            return;
        // StartTracking is not cancelable, so re-hide next tick after vanilla finishes adding the entity.
        server.execute(() ->
        {
            if (VanishHooks.canSee(observer, target.getUUID()))
                return;
            target.stopSeenByPlayer(observer);
            observer.connection.send(new ClientboundRemoveEntitiesPacket(target.getId()));
        });
    }
}
