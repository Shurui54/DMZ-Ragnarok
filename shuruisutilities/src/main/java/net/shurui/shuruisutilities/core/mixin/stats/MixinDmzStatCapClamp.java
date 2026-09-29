package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.prestige.PrestigeCaps;
import net.shurui.shuruisutilities.stats.StatCapBypass;

/**
 * Prestige stat-cap boost, chokepoint 1 of 2: the hard clamp every DragonMineZ stat setter runs through
 * ({@code Stats.setStrength} ... -&gt; {@code clampStatValue}). DMZ ends that method with
 * {@code Math.min(capped, max)} where {@code max} is the configured per-stat cap; we widen {@code max} by the
 * player's prestige cap multiplier so maxed prestige characters can keep pushing stats past the base cap.
 *
 * <p>The redirect fires only on the clamping return path (the {@code Math.min}); the method's early returns
 * for null config / {@code maxLevelValueInsteadOfStats} bypass it and are left untouched. A null player
 * (e.g. an unattached {@code Stats}) yields multiplier 1.0, i.e. no change. String targets are
 * {@code remap = false} because they resolve against DMZ's own (non-Mojmap) descriptors, matching the
 * {@code MixinDmzTpMultiplierTooltip} precedent; {@code java/lang/Math.min} is a vanilla-JDK symbol so it
 * needs no remapping either.</p>
 */
@Mixin(targets = "com.dragonminez.common.stats.character.Stats", remap = false)
public abstract class MixinDmzStatCapClamp
{
    @Shadow
    private Player player;

    @Redirect(
            method = "clampStatValue",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"),
            require = 0)
    private int su$widenClamp(int capped, int max)
    {
        // Command bypass (/dmzstats set|add) and the per-character override re-assert set no cap at all, so the
        // requested above-cap value sticks. Runs only on the server thread; normal in-GUI purchases never hit it.
        if (StatCapBypass.active())
            return capped;
        double mult = this.player == null ? 1.0 : PrestigeCaps.getCapMultiplier(this.player);
        int widened = mult <= 1.0 ? max : (int) Math.min((double) max * mult, Integer.MAX_VALUE);
        return Math.min(capped, widened);
    }
}
