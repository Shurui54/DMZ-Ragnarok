package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.wish.ReleaseBoostClient;

/**
 * Adds the wished-for release bonus to the ceiling the radial release node DISPLAYS, on the CLIENT. Companion to the
 * two server release mixins: {@code ReleaseNode.maxRelease} returns DMZ's bare {@code 50 + potentialunlock * 5}, so
 * without this the client would draw the unwished ceiling and the slider would look wrong even though the server lets
 * the player charge higher.
 *
 * <p>The bonus comes from {@link ReleaseBoostClient}, synced from the server on login and after each wish, because a
 * client cannot read the server-side SU property store. Injecting at RETURN and overwriting the returned value keeps
 * this decoupled from DMZ's formula: whatever DMZ computes, the bonus is added on top.
 *
 * <p>{@code remap = false} and {@code require = 0} so a DMZ reshape degrades to the vanilla display rather than
 * crashing, and the handler swallows any Throwable, leaving DMZ's own return value in place.
 */
@Mixin(targets = "com.dragonminez.client.gui.radial.nodes.ReleaseNode", remap = false)
public abstract class MixinDmzReleaseNodeCap
{
    @Inject(method = "maxRelease", at = @At("RETURN"), cancellable = true, require = 0)
    private static void su$addReleaseBonus(StatsData data, CallbackInfoReturnable<Integer> cir)
    {
        try
        {
            cir.setReturnValue(cir.getReturnValueI() + ReleaseBoostClient.get());
        }
        catch (Throwable t)
        {
            // Leave DMZ's own return value in place on any surprise.
        }
    }
}
