package net.shurui.shuruisutilities.space;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.commands.registration.SUCommandManager;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartingEvent;

/**
 * Registers the {@code /spaceplanet} command for claiming generated planets. Auto-registered on the Forge bus by the
 * SU module launcher, so this class only subscribes to {@link RegisterCommandsEvent}.
 *
 * <p>No guild-disband cleanup hook on purpose: a stale claim whose guild is gone is harmless (unique ids mean no fresh
 * guild inherits it), and PlanetDestruction unclaims a destroyed planet's id anyway.
 * {@link GeneratedPlanetClaims#unclaimGuild} is available for a future disband hook without a storage change.
 */
@SUModule(name = "SpacePlanetClaims", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class ModuleSpacePlanetClaims
{
    // OP-level permission gating the admin destroy/restore subcommands.
    public static final String PERM_ADMIN = "shuruisutilities.spaceplanet.admin";

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event)
    {
        // Registration cannot be revisited: enforce() runs at ServerStartedEvent, after RegisterCommandsEvent, so a
        // module torn down there leaves its commands in the dispatcher and usable. Deciding it here is what holds.
        //
        // moduleEntitled, not KeyGate.unlocked(): space is public now (PublicContent.PUBLIC_MODULES), so a
        // full-key-only test would keep /spaceplanet off the very tiers it is granted to. The admin subcommands sit
        // behind PERM_ADMIN separately, which is a different question from which tier may run the module.
        if (!net.shurui.shuruisutilities.core.config.PublicContent.moduleEntitled("SpacePlanetClaims"))
            return;
        SUCommandManager.registerCommand(new CommandSpacePlanet(true), event.getDispatcher());
    }

    @SubscribeEvent
    public void serverStarting(SUModuleServerStartingEvent event)
    {
        APIRegistry.perms.registerPermission(PERM_ADMIN, DefaultPermissionLevel.OP,
                "Destroy or restore a generated planet");
    }
}
