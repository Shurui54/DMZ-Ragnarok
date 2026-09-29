package net.shurui.dev.sdu.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import net.shurui.dev.sdu.network.DmzNet;

/**
 * Sends the key status (and the now-always-empty module packet) to each player on join.
 *
 * <p>Kept in its own class rather than {@code ForgeEvents}: the key sync must reach every client whatever else a
 * build carries, so it lives outside any conditional-registration path. Batch M removed the operator switchboard,
 * so the module packet carries an empty set now; the KEY sync is the load-bearing half.
 *
 * <p>Runs at {@link EventPriority#HIGHEST} and sends KEY FIRST, THEN the module packet, so the key fact is on the
 * wire before any other login handler acts and the client's {@link net.shurui.dev.sdu.api.ClientGate} sees it.
 *
 * <p>The client forgets both on disconnect in {@code ClientGateEvents} (a Dist.CLIENT subscriber on
 * ClientPlayerNetworkEvent), NOT here: a {@code PlayerLoggedOutEvent} is the wrong hook for clearing client
 * caches, and this class must never grow a teardown-shaped method.
 */
@Mod.EventBusSubscriber(modid = net.shurui.dev.sdu.DmzNpc.MODID)
public final class ModuleSyncEvents
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private ModuleSyncEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            try
            {
                DmzNet.syncKeyStatusToPlayer(player);    // key first: the client gate opens before the rest
                DmzNet.syncKeyFeaturesToPlayer(player);   // then which PRIVATE key features are actually installed
                DmzNet.syncModulesToPlayer(player);       // then which switches this server turned off
                LOGGER.info("[dmz_ragnarok] key sync -> {}: key={}, disabled={}",
                        player.getGameProfile().getName(),
                        DmzNet.clientKeyPresent(),
                        net.shurui.dev.sdu.network.ModuleSyncPacket.current().disabledCount());
                LOGGER.info("[dmz_ragnarok] key features -> {}: {}",
                        player.getGameProfile().getName(), DmzNet.clientFeatureIds());
            }
            catch (Throwable t)
            {
                // A failed sync costs a player some hidden buttons. It must never cost them the login.
            }
        }
    }
}
