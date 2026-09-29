package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.function.IntSupplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Make a dragon ball set configured to scatter ZERO copies actually scatter zero.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code DragonBallSetDefinition.getCopies()} returns {@code Math.max(1, copiesSupplier.getAsInt())}, so a set
 * whose supplier says 0 is floored to 1 and DragonMineZ's first spawn drops one copy of every star anyway. That is
 * exactly how stray Cerulean balls (a set deliberately configured with 0 copies, meant to be summon / craft only)
 * ended up physically placed on the live open world twins. The floor treats "never scatter" as "scatter one".
 *
 * <h2>Why a mixin and not a guard before our own scatter calls</h2>
 *
 * <p>The floor lives inside {@code getCopies()}, and that method is read by DragonMineZ's OWN scatter paths that we
 * never invoke and cannot wrap: {@code DragonBallsHandler.scatterDragonBalls} (first spawn) and
 * {@code DragonWishEntity.onDespawn} (the post wish rescatter). A guard placed before the scatter calls that the
 * suite makes would only cover our reconcile and one shot rescatter; DragonMineZ's internal post wish rescatter
 * would keep flooring 0 to 1. Only correcting the source reaches every caller. The mixin itself is minimal risk:
 * the target descriptor is {@code ()I}, there is NO argument capture (only the callback), it shadows one field, and
 * it is {@code require = 0} so a DragonMineZ rename degrades to a no op rather than a load crash.
 *
 * <p>We do not touch the {@code Math.max(1, ...)} for positive values, so a set configured with 3 copies still
 * scatters 3. We only intercept the 0 (and negative) case, returning 0 so the scatter loop's {@code setsToSpawn}
 * and {@code actualToSpawn} both fall to zero and nothing is placed.
 */
@Mixin(targets = "com.dragonminez.common.dragonball.DragonBallSetDefinition", remap = false)
public abstract class MixinDmzDragonBallCopies
{
    @Shadow
    @Final
    private IntSupplier copiesSupplier;

    @Inject(method = "getCopies", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$respectZeroCopies(CallbackInfoReturnable<Integer> cir)
    {
        try
        {
            if (this.copiesSupplier != null && this.copiesSupplier.getAsInt() <= 0)
            {
                cir.setReturnValue(0);
            }
        }
        catch (Throwable ignored)
        {
            // A supplier that throws is DMZ's problem, not ours: leave its own getCopies to handle it.
        }
    }
}
