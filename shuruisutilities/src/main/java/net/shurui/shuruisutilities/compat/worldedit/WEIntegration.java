package net.shurui.shuruisutilities.compat.worldedit;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.environment.Environment;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule.Preconditions;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// separate class from the main WEIntegration stuff so as to avoid nasty errors
@SUModule(name = WEIntegration.weModule, parentMod = ShuruisUtilities.class, version=ShuruisUtilities.CURRENT_MODULE_VERSION)
public class WEIntegration
{
	public static final String weModule = "WEIntegrationTools";

	@SUModule.Instance
    public static WEIntegration instance;

    protected static boolean disable;

    WEIntegrationHandler handler;

    public void postLoad()
    {
        handler = new WEIntegrationHandler();
        if (handler.postLoad())
        {
            handler = null;
            ModuleLauncher.instance.unregister(weModule);
            return;
        }
    }

    private static boolean getDevOverride()
    {
        String prop = System.getProperty("shuruisutilities.developermode.we");
        if (prop != null && prop.equals("true"))
        { // FOR DEVS ONLY! THAT IS WHY IT IS A PROPERTY!!!

            LoggingHandler.sulog.error("Developer mode has been enabled, things may break.");
            return true;
        }
        else
        {
            return false;
        }
    }

    @Preconditions
    public static boolean canLoad()
    {
        if (getDevOverride())
        {
            disable = true;
            return false;
        }

        if (!Environment.hasWorldEdit())
        {
            disable = true;
            LoggingHandler.sulog
                    .error("The SU integration tools for WorldEdit will not work without installing WorldEdit Forge.");
            LoggingHandler.sulog
                    .error("You are highly recommended to install WorldEdit Forge for the optimal SU experience.");
            return false;
        }

        else
        {
            try
            {
                Class.forName("com.sk89q.worldedit.forge.ForgePermissionsProvider");
            }
            catch (ClassNotFoundException e)
            {
                disable = true;
                LoggingHandler.sulog
                        .error("ForgePermissionsProvider not found, are you using an old version of WorldEdit?");
                LoggingHandler.sulog.error(
                        "The SU integration tools for WorldEdit will not be loaded as your version of WorldEdit may be too old.");
                return false;
            }
        }
        return true;
    }
}
