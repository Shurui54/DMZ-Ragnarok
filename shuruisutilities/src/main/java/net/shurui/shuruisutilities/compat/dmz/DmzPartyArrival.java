package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Classload-safe entry point for the DMZ party arrival reconcile. No DMZ types are named here; {@link
 * DmzPartyArrivalImpl} is only loaded (and only registered on the Forge bus) once DragonMineZ is confirmed present.
 * See {@link DmzPartyArrivalImpl} for what the reconcile does and why it rebuilds the roster rather than syncing it.
 */
public final class DmzPartyArrival
{
    private DmzPartyArrival() {}

    public static void init()
    {
        if (!ModList.get().isLoaded("dragonminez"))
        {
            return;
        }
        try
        {
            MinecraftForge.EVENT_BUS.register(new DmzPartyArrivalImpl());
            LoggingHandler.sulog.info("[shard] DMZ party arrival reconcile enabled.");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not enable DMZ party arrival reconcile: {}", t.toString());
        }
    }
}
