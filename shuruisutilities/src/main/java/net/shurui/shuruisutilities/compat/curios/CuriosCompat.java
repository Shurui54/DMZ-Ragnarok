package net.shurui.shuruisutilities.compat.curios;

import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// classload-safe entry point for Curios. NO Curios imports here; everything touching Curios lives in
// CuriosGraveCompat, only reached when curios is loaded.
public final class CuriosCompat
{
    private CuriosCompat() {}

    // wire up the keepinv drop-rule override if Curios is present, else no-op
    public static void init()
    {
        if (!ModList.get().isLoaded("curios"))
            return;
        try
        {
            CuriosGraveCompat.register();
            LoggingHandler.sulog.info("[Grave] Curios keep-inventory integration enabled.");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Grave] Failed to enable Curios keep-inventory integration: {}", t.toString());
        }
    }
}
