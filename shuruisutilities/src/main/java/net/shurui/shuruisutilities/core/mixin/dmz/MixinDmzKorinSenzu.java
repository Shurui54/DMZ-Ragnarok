package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.shuruisutilities.compat.dmz.KorinRewardEvents;

/**
 * Korin's senzu handout is the suite's own beans, once a week, instead of DragonMineZ's beans on DMZ's tick cooldown.
 *
 * <p>DMZ's Korin dialogue is handled by {@code NPCActionC2S.handleKarin(ServerPlayer, StatsData, int)}. Option 2 is the
 * senzu gift: it checks DMZ's per-character {@code SenzuKarin} tick cooldown, gives {@code senzuGiftAmount} of
 * {@code dragonminez:senzu_bean}, and stamps that cooldown. That whole branch is replaced here by
 * {@link KorinRewardEvents#grantFromDialogue}, which gives the suite's {@code bean_senzu} on the suite's per player,
 * wall clock, network wide weekly cooldown.
 *
 * <p>Option 1 is the nimbus: DMZ hands over the ACTUAL cloud item ({@code nube} for alignment above 50, else
 * {@code nube_negra}). The owner wants Korin to give the suite's nimbus CHIP instead (2026-09-29), so that option is
 * replaced by {@link KorinRewardEvents#grantNimbusChipFromDialogue}. DMZ puts no cooldown or once-per-player rule on this
 * option, and none is added. The chip picks the flying or black cloud from alignment itself when it is deployed. If the
 * nimbus feature is switched off or the chip cannot be resolved, DMZ's own gift runs as before.
 *
 * <p>The handler takes the target's parameters exactly, plus the callback, as mixin argument capture requires.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.NPCActionC2S", remap = false)
public class MixinDmzKorinSenzu
{
    private static final int SENZU_OPTION = 2;
    private static final int NIMBUS_OPTION = 1;

    @Inject(method = "handleKarin", at = @At("HEAD"), cancellable = true, require = 0)
    private static void su$nimbusChip(ServerPlayer player, StatsData data, int option, CallbackInfo ci)
    {
        if (option != NIMBUS_OPTION || player == null)
            return;
        try
        {
            if (KorinRewardEvents.grantNimbusChipFromDialogue(player))
                ci.cancel();
        }
        catch (Throwable ignored)
        {
            // never let the handout crash the packet handler; DMZ's own nimbus gift runs instead
        }
    }

    @Inject(method = "handleKarin", at = @At("HEAD"), cancellable = true, require = 0)
    private static void su$weeklySuiteSenzu(ServerPlayer player, StatsData data, int option, CallbackInfo ci)
    {
        if (option != SENZU_OPTION || player == null)
            return;
        ci.cancel();
        try
        {
            KorinRewardEvents.grantFromDialogue(player);
        }
        catch (Throwable ignored)
        {
            // never let the handout crash the packet handler; the player simply gets nothing this click
        }
    }
}
