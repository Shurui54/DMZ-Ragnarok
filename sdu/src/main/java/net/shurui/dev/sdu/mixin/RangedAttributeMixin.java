package net.shurui.dev.sdu.mixin;

import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Drops the artificial UPPER cap on ranged attributes so custom NPCs (and anything) can have arbitrarily
// large values (vanilla caps max-health 1024, attack 2048, move speed 1024, etc). Lower bound + NaN handling
// preserved, and normal entities never set above the cap, so in practice this only lets our editors' huge
// numbers take effect (e.g. 11 trillion HP).
@Mixin(RangedAttribute.class)
public class RangedAttributeMixin {

    @Shadow @Final private double minValue;

    @Inject(method = "sanitizeValue", at = @At("HEAD"), cancellable = true)
    private void sdu$removeUpperCap(double value, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(Double.isNaN(value) ? this.minValue : Math.max(value, this.minValue));
    }
}
