package net.shurui.shuruisutilities.core.mixin.stats;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.character.StatCapOverrides;
import net.shurui.shuruisutilities.stats.StatCapBypass;

/**
 * Lets /dmzstats set|add raise a stat above the global max even when already at/over the cap. DMZ's
 * StatsCommand.modifyStats is the single set/add/remove entry point; we open StatCapBypass for its whole body
 * so both chokepoints (clampStatValue widened by MixinDmzStatCapClamp, the budget from getConfiguredMaxValue
 * widened by MixinDmzStatCapBudget) stand down for the command.
 *
 * NO permission check around the bypass: anyone allowed to run the command may exceed the cap (product
 * decision). remap=false (DMZ non-Mojmap names). runs on the server thread; bypass cleared at RETURN, and
 * exit() is idempotent so a re-clear can't leak the flag.
 *
 * on return, before releasing the bypass, each target's above-cap value is recorded as a per-character
 * override (or cleared if back under cap) so it survives DMZ's re-clamp on login/clone/dim change/slot switch.
 */
@Mixin(targets = "com.dragonminez.server.commands.StatsCommand", remap = false)
public abstract class MixinDmzStatsCommand
{
    @Inject(method = "modifyStats", at = @At("HEAD"), require = 0)
    private static void su$openCapBypass(CommandSourceStack source, String stat, String amountStr,
            Collection<ServerPlayer> targets, String mode, CallbackInfoReturnable<Integer> cir)
    {
        StatCapBypass.enter();
    }

    @Inject(method = "modifyStats", at = @At("RETURN"), require = 0)
    private static void su$recordAndCloseCapBypass(CommandSourceStack source, String stat, String amountStr,
            Collection<ServerPlayer> targets, String mode, CallbackInfoReturnable<Integer> cir)
    {
        try
        {
            if (!"remove".equals(mode) && targets != null)
            {
                for (ServerPlayer target : targets)
                {
                    StatCapOverrides.recordAfterCommand(target, stat);
                }
            }
        }
        finally
        {
            StatCapBypass.exit();
        }
    }
}
