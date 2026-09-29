package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.shuruisutilities.wish.ReleaseBoostStore;

/**
 * Adds the wished-for release bonus ({@link ReleaseBoostStore}) to the ceiling used when a player SETS their manual
 * release limit from the radial menu, on the SERVER. Companion to {@link MixinDmzReleaseChargeCap}: DMZ recomputes
 * {@code 50 + potentialunlock * 5} in this packet handler too (to clamp the requested limit), so without this a wished
 * player could not pick a limit above the unwished ceiling even though the charge tick would honour it.
 *
 * <p>The real work sits in the lambda {@code lambda$handle$0(ServerPlayer, StatsData)}; the public {@code handle} just
 * defers to it on the server thread. Here {@code maxRelease} (local slot 4) is written exactly once, from the formula,
 * so {@code STORE ordinal 0} is unambiguous. Verified in the 2.1.3 bytecode: a single {@code istore 4} to the
 * "maxRelease" local, and it is the formula store; the neighbouring stores are to {@code releaseLimit}, a different
 * slot, which this must not touch.
 *
 * <p>{@code remap = false} and {@code require = 0} so a DMZ reshape degrades gracefully, and the handler swallows any
 * Throwable back to the original value. It takes the target's full parameter list (all of them or none), giving it the
 * player directly.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.SetReleaseLimitC2S", remap = false)
public abstract class MixinDmzReleaseLimitPacket
{
    @ModifyVariable(method = "lambda$handle$0", at = @At(value = "STORE", ordinal = 0),
            name = "maxRelease", require = 0)
    private int su$addReleaseBonus(int maxRelease, ServerPlayer player, StatsData data)
    {
        try
        {
            return maxRelease + ReleaseBoostStore.bonus(player);
        }
        catch (Throwable t)
        {
            return maxRelease;
        }
    }
}
