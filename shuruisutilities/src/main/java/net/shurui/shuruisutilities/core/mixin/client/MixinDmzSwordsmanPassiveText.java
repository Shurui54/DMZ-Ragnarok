package net.shurui.shuruisutilities.core.mixin.client;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.client.gui.character.util.ScaledScreen;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;

import net.shurui.shuruisutilities.swordsman.Swordsman;

/**
 * Fills the live numbers into the swordsman's passive blurb.
 *
 * <p>DMZ renders a class passive's text as {@code tr("class.dragonminez.<class>.passive.desc")} with no arguments,
 * both on the character creation class tab and in the skills menu, and both go through {@code ScaledScreen.tr}. The
 * swordsman's bonus sizes live in the race's synced stats.json, so the blurb carries two {@code %s} slots and this
 * hands it the configured percentages instead of a number that would go stale when an admin edits them. The
 * player's own character is the one being created or viewed in both screens, so its race and class pick the values.
 *
 * <p>{@code require = 0}: a DMZ rename degrades to the blurb with its slots unfilled, never a crash.
 */
@Mixin(targets = "com.dragonminez.client.gui.character.util.ScaledScreen", remap = false)
public abstract class MixinDmzSwordsmanPassiveText
{
    private static final String KEY = "class.dragonminez." + Swordsman.CLASS_ID + ".passive.desc";

    @Inject(method = "tr(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$swordsmanNumbers(String key, Object[] args, CallbackInfoReturnable<MutableComponent> cir)
    {
        if (!KEY.equals(key) || (args != null && args.length > 0))
            return;
        try
        {
            Minecraft mc = Minecraft.getInstance();
            StatsData data = mc.player == null ? null
                    : StatsProvider.get(StatsCapability.INSTANCE, mc.player).orElse(null);
            if (data == null)
                return;
            // Recurses once with arguments, which the guard above lets straight through to DMZ's own tr.
            cir.setReturnValue(((ScaledScreen) (Object) this).tr(key,
                    pct(Swordsman.bonus(data, Swordsman.DAMAGE_KEY)), pct(Swordsman.bonus(data, Swordsman.SPEED_KEY))));
        }
        catch (Throwable ignored)
        {
            // leave DMZ's own lookup in place
        }
    }

    private static String pct(double fraction)
    {
        double p = fraction * 100.0;
        return Math.abs(p - Math.rint(p)) < 1.0E-6 ? String.valueOf((long) Math.rint(p))
                : String.format(Locale.ROOT, "%.1f", p);
    }
}
