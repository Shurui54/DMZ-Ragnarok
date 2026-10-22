package net.shurui.shuruisutilities.core.mixin.dmz;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.ritual.RitualSettings;

/**
 * DragonMineZ 2.2's own Super Saiyan God ritual can be turned off by an admin ({@code DmzGodRitualEnabled} in the
 * config, on by default, or the Rituals row of the admin hub, see {@link RitualSettings}). While it is on this changes
 * nothing about DMZ's ritual.
 *
 * <p>{@code GodRitualService.evaluate} is the ritual's single start point: DMZ's ki sharing calls it for every
 * player receiving ki, and it checks the donors, gives the "who is missing" feedback and begins the ritual. Cancelling
 * it at HEAD starts nothing and prints nothing, so no half of the ritual shows. The ritual's form
 * ({@code godritual.supersaiyangodritual}) is a scripted form DMZ grants only from the ritual, for a few minutes, and
 * hides from the skills and transform lists itself; nothing permanent was ever granted, so there is nothing to take
 * away. A ritual or form already running when it is switched off simply runs out. Server side only.
 * {@code require = 0}: if DMZ renames the method, its ritual stays on as before.
 */
@Mixin(targets = "com.dragonminez.server.events.players.GodRitualService", remap = false)
public abstract class MixinDmzGodRitualToggle
{
    @Inject(method = "evaluate", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$dmzGodRitualToggle(MinecraftServer server, ServerPlayer recipient, CallbackInfo ci)
    {
        try
        {
            if (!RitualSettings.dmzGodRitualEnabled(server))
                ci.cancel();
        }
        catch (Throwable ignored)
        {
        }
    }
}
