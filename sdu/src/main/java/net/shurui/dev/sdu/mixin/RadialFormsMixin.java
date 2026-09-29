package net.shurui.dev.sdu.mixin;

import net.shurui.dev.sdu.form.CustomFormTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Routes registered custom form types to the "More" radial wheel, not "Super", so a custom type whose id
// happens to contain super/legendary/android isn't mis-filed under Super by DMZ's substring test.
//
// RadialForms builds both wheels through one forms(stats, key, predicate) helper: superForms passes
// type -> contains("super")||contains("legendary")||contains("android") (lambda$superForms$0), moreForms the
// negation (lambda$moreForms$1). Both get the group's already-lowercased formType. We inject at each HEAD:
//   lambda$superForms$0: a registered custom type returns false, kept OUT of Super.
//   lambda$moreForms$1: a registered custom type returns true, placed INTO More.
//
// Full rerouting: the group appears in exactly one wheel (More); TransformationsHelperMixin already fixes its
// unlock gating. require=0/remap=false: if DMZ renumbers/renames these synthetic lambdas the mixin no-ops
// (gating stays correct; placement falls back to DMZ's substring).
@Mixin(targets = "com.dragonminez.client.gui.radial.nodes.RadialForms", remap = false)
public abstract class RadialFormsMixin {

    @Inject(method = "lambda$superForms$0", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$excludeCustomFromSuperWheel(String type, CallbackInfoReturnable<Boolean> cir) {
        if (CustomFormTypes.contains(type)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "lambda$moreForms$1", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$includeCustomInMoreWheel(String type, CallbackInfoReturnable<Boolean> cir) {
        if (CustomFormTypes.contains(type)) {
            cir.setReturnValue(true);
        }
    }
}
