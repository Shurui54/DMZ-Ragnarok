package net.shurui.shuruisutilities.regions;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartingEvent;

/**
 * The part of the regions that runs on every server (S12). The Regions module moved into the Ragnarok Key, but
 * public features read the saved regions: terrain regen's {@code terrain-regen} flag, the world-flag mixins and
 * DragonMineZ's ki-grief / gravity lookups. Before S12 the module was built keyless too and loaded
 * {@code regions.json} before the keyless teardown, and its DMZ hook still went in, so those answers were live keyless.
 * This keeps exactly that, on every server:
 * <ul>
 *   <li>{@code regions.json} is loaded into {@link RegionManager#instance()} at SU server start (the same event the
 *       module used). It is only READ here; nothing in core writes it.</li>
 *   <li>{@link RegionDmzHook} is injected at {@link ServerStartedEvent}, LOWEST, after DragonMineZ's own
 *       WorldGuardCompat init (see the hook for why the order matters).</li>
 * </ul>
 * Registered once from {@code ShuruisUtilities} common setup.
 */
public final class RegionEngine
{
    private static boolean registered;

    private RegionEngine() {}

    public static synchronized void register()
    {
        if (registered)
            return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(new RegionEngine());
    }

    @SubscribeEvent
    public void serverStarting(SUModuleServerStartingEvent event)
    {
        RegionManager.instance().load();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void serverStarted(ServerStartedEvent event)
    {
        RegionDmzHook.inject();
    }
}
