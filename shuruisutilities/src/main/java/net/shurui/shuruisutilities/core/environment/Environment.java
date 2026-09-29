package net.shurui.shuruisutilities.core.environment;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

public class Environment
{

    private static boolean hasWorldEdit = false;

    private static boolean isClient = false;

    protected static boolean hasCauldron = false;

    protected static boolean hasSponge = false;

    private static boolean hasFTBU = false;

    public static void check()
    {
        // Check if dedicated or integrated server
        isClient = FMLEnvironment.dist == Dist.CLIENT;

        if (ModList.get().isLoaded("worldedit"))
        {
            hasWorldEdit = true;
            try
            {
                Class.forName("net.shurui.shuruisutilities.compat.worldedit.WEIntegration");
            }
            catch (ClassNotFoundException cnfe)
            {
                LoggingHandler.sulog.warn(
                        "Found WorldEdit Forge, but not SU WorldEdit-module. You cannot use WorldEdit for SU without it.");
            }
        }

        if (ModList.get().isLoaded("ftbu"))
        {
            LoggingHandler.sulog.warn("FTB Utilities is installed. Shurui's Utilities may not work as expected.");
            LoggingHandler.sulog.warn("Please uninstall FTB Utilities to regain full SU functionality.");
            hasFTBU = true;
            MinecraftForge.EVENT_BUS.register(new FTBUNagHandler());
        }

        if (Boolean.parseBoolean(System.getProperty("shuruisutilities.developermode.we")))
        {
            LoggingHandler.sulog.warn("WorldEdit integration tools force disabled.");
            hasWorldEdit = false;
            return;
        }

        // Some additional checks

        // Check for Cauldron or LavaBukkit
        // String modName = ServerLifecycleHooks.getCurrentServer().getServerModName();
        if (ModList.get().isLoaded("cauldron"))
        {
            LoggingHandler.sulog.error("You are attempting to run SU on Cauldron. This is completely unsupported.");

            LoggingHandler.sulog
                    .error("Bad things may happen. DO NOT BOTHER ANYONE ABOUT THIS CRASH - YOU WILL BE IGNORED");
            LoggingHandler.sulog.error(
                    "Please uninstall SU from this Cauldron server installation. We recommend to use bukkit plugins instead.");
            if (!ShuruisUtilities.isSafeMode())
            {
                LoggingHandler.sulog.error("The server will now shut down as a precaution against data loss.");
                throw new RuntimeException(
                        "Sanity check failed: Detected Cauldron, bad things may happen to your server. Shutting down as a precaution.");
            }
            LoggingHandler.sulog.error("SU safe mode has been enabled, you are proceeding at your own risk.");
            LoggingHandler.sulog.error("Sanity check failed: Detected Cauldron, bad things may happen to your server.");
        }
    }

    public static boolean isClient()
    {
        return isClient;
    }

    public static boolean hasWorldEdit()
    {
        return hasWorldEdit;
    }

    public static boolean hasSponge()
    {
        return hasSponge;
    }

    public static boolean hasFTBU()
    {
        return hasFTBU;
    }

    public static void registerSpongeCompatPlugin(boolean isWESpongePresent)
    {
        LoggingHandler.sulog.info("Sponge environment plugin found, enabling Sponge compat.");
        hasSponge = true;
        hasWorldEdit = isWESpongePresent;
    }

    public static class FTBUNagHandler
    {

        @SubscribeEvent
        public void playerLogIn(PlayerLoggedInEvent e)
        {
            if (ServerLifecycleHooks.getCurrentServer().getPlayerList().isOp(e.getEntity().getGameProfile()))
            {
                ChatOutputHandler.chatWarning(e.getEntity().createCommandSourceStack(),
                        "FTB Utilities is installed. Shurui's Utilities may not work as expected.");
                ChatOutputHandler.chatWarning(e.getEntity().createCommandSourceStack(),
                        "Please uninstall FTB Utilities to regain full SU functionality.");
            }
        }
    }

}
