package net.shurui.shuruisutilities.compat.worldedit;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.util.selections.SelectionHandler;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

public class WEIntegrationHandler
{

    public CUIComms cuiComms;

    public boolean postLoad()
    {
        if (WEIntegration.disable)
        {
            LoggingHandler.sulog.error("Requested to force-disable WorldEdit.");
            if (ModList.get().isLoaded("worldedit"))
            {
                try
                {
                    MinecraftForge.EVENT_BUS.unregister(ForgeWorldEdit.inst); // forces worldedit forge NOT to load
                }
                catch (IllegalArgumentException e1)
                {
                    LoggingHandler.sulog.error("WorldEdit not found, unregistering WEIntegrationTools");
                    return true;
                }
            }
            return true;
        }
        else
        {
            if (ModList.get().isLoaded("worldedit"))
            {
                SelectionHandler.selectionProvider = new WESelectionHandler();
                try
                {
                    ForgeWorldEdit.inst.setPermissionsProvider(new PermissionsHandler());
                    LoggingHandler.sulog.info("WorldEdit permission provider installed.");
                }
                catch (Throwable t)
                {
                    // WE is present but the provider could not be installed (WE internals changed, reflection/class
                    // mismatch, etc). Surface it loudly so a broken install is obvious in the log rather than silently
                    // leaving WE on op-only defaults; do not abort the rest of the WE integration.
                    LoggingHandler.sulog.warn(
                            "WorldEdit is present but the SU permission provider FAILED to install; WorldEdit will fall back to its own op-only defaults. Non-op WE grants via SU will NOT apply.",
                            t);
                }
                cuiComms = new CUIComms();
                return false;
            }
            else
            {
                LoggingHandler.sulog.error("WorldEdit not found, unregistering WEIntegrationTools");
                return true;
            }
        }
    }
}
