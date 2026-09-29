package net.shurui.shuruisutilities.cosmetics.form;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;

/**
 * Lifecycle glue for the single form cosmetic: on login it syncs the joining player the current table of styled
 * players and announces the joiner to everyone (see {@link FormCosmeticManager#onLogin}). All authority and
 * storage live in {@link FormCosmeticManager} / {@link FormCosmeticData}; this module is only the event hook.
 */
@SUModule(name = "FormCosmetics", parentMod = ShuruisUtilities.class, canDisable = true, defaultModule = true, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class ModuleFormCosmetic
{
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            FormCosmeticManager.onLogin(player);
    }
}
