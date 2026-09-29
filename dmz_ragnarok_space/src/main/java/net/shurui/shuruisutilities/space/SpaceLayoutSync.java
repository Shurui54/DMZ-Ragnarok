package net.shurui.shuruisutilities.space;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Pushes {@link PacketSpaceLayoutSync} so clients re-derive the same space layout the server draws (bodies are not
 * entities, so nothing tracks them automatically). The inputs (layout numbers + fixed planet bodies) are sent at the
 * two moments the layout can change:
 *
 * <ul>
 *   <li>PLAYER LOGIN: told the current layout immediately.</li>
 *   <li>DATAPACK RELOAD: DMZ's space-pod destination list is what the fixed-body set derives from.
 *       {@link net.minecraftforge.event.OnDatapackSyncEvent} fires AFTER datapacks reload, both on world load and every
 *       {@code /reload}. A CONFIG reload re-bakes the numbers and calls {@link #syncAll()} directly
 *       (PlanetSpawnModule.bakeConfig), covering the numbers-changed path.</li>
 * </ul>
 *
 * <p>Auto-registered on the Forge bus by the SU module launcher.
 */
@SUModule(name = "SpaceLayoutSync", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class SpaceLayoutSync
{
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            NetworkUtils.sendTo(PacketSpaceLayoutSync.current(player.getServer()), player);
        }
    }

    // Fires after datapacks (DMZ's destinations) reload. Fine to syncAll() regardless of whether this is one player or
    // a server-wide /reload: cheap (a few numbers plus the tiny fixed-body and owner lists).
    @SubscribeEvent
    public void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent event)
    {
        // drop the eligibility cache so bodies() rebuilds against the reloaded destinations, then broadcast.
        PlanetRegistry.invalidate();
        syncAll();
    }

    public static void syncAll()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return;
        }
        PacketSpaceLayoutSync packet = PacketSpaceLayoutSync.current(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            NetworkUtils.sendTo(packet, player);
        }
    }
}
