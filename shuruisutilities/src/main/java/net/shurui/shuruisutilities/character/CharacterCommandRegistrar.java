package net.shurui.shuruisutilities.character;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.core.commands.registration.SUCommandManager;
import net.shurui.shuruisutilities.core.config.Features;

/**
 * Registers {@code /character} (aliases {@code /char}, {@code /characters}). Character slots are PUBLIC (owner decision
 * for 2.0), so the command registers on every server, keyed or keyless. It used to be registered by the Commands
 * module before that module's entitlement gate; the Commands module moved into the Ragnarok Key (S18b), so core
 * registers it here, on the Forge bus by instance (built by ShuruisUtilities), with the same switchboard check.
 */
public final class CharacterCommandRegistrar
{
    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event)
    {
        if (!Features.enabled(Features.CHARACTER_SLOTS))
            return;
        SUCommandManager.setBuildContext(event.getBuildContext());
        SUCommandManager.registerCommand(new CommandCharacter(true), event.getDispatcher());
    }
}
