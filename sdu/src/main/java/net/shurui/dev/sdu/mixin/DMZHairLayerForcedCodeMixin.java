package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.stats.character.Character;
import net.shurui.dev.sdu.compat.dmz.HairCodeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Fixes two client-side bugs in DMZ 2.1's per-form forced-hair-code feature, both in DMZHairLayer's
// getHairForForm / getHairForStackForm (differ only in getFormGroup vs getStackFormGroup).
//
// 1. Base-only bug. Both decode forcedHairCode with HairManager.fromCode. For a full-set code
//    (Base+SSJ+SSJ2+SSJ3) fromCode decodes all four slots but returns only slot [0] (Base), so a form
//    forcing a full set always shows Base regardless of transformation. We decode the SET and, when the form
//    also defines hairType, pick the matching slot, composing forcedHairCode (set) with hairType (slot
//    selector), which DMZ's if/else-if can't express. No hairType -> slot 0.
// 2. Per-frame FPS collapse (421->35). renderHair runs every frame per visible player, so DMZ re-decodes the
//    code every frame uncached. Memoised in HairCodeCache.
//
// @Inject at HEAD, cancellable, handling ONLY the hasHairCodeOverride() branch. A @Redirect on the fromCode
// call site was rejected: only the code string is in scope there, not hairType, so it can't compose set +
// slot. No usable override -> don't cancel, DMZ's hairType/fallback runs. Decode failures cache an empty
// sentinel so a bad code isn't re-decoded per frame.
//
// Client-only, remap=false. HairCodeCache.clear() runs on resource reload (ClientModBusEvents) and after
// config sync (ConfigSyncSeedMixin), so edits apply without relog.
@Mixin(targets = "com.dragonminez.client.render.layer.DMZHairLayer", remap = false)
public abstract class DMZHairLayerForcedCodeMixin {

    @Inject(
            method = "getHairForForm(Lcom/dragonminez/common/stats/character/Character;Ljava/lang/String;Ljava/lang/String;)Lcom/dragonminez/common/hair/CustomHair;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void sdu$forcedCodeForForm(Character character, String group, String formName,
                                       CallbackInfoReturnable<CustomHair> cir) {
        FormConfig config = ConfigManager.getFormGroup(character.getRaceName(), group);
        sdu$handleOverride(config, formName, cir);
    }

    @Inject(
            method = "getHairForStackForm(Lcom/dragonminez/common/stats/character/Character;Ljava/lang/String;Ljava/lang/String;Lcom/dragonminez/common/hair/CustomHair;)Lcom/dragonminez/common/hair/CustomHair;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void sdu$forcedCodeForStackForm(Character character, String group, String formName, CustomHair fallback,
                                            CallbackInfoReturnable<CustomHair> cir) {
        FormConfig config = ConfigManager.getStackFormGroup(group);
        sdu$handleOverride(config, formName, cir);
    }

    // cancel with the cached slot-selected hair only when the form has a usable override, else fall through
    // to DMZ's stock hairType/fallback
    private static void sdu$handleOverride(FormConfig config, String formName,
                                           CallbackInfoReturnable<CustomHair> cir) {
        if (config == null) {
            return;
        }
        FormConfig.FormData formData = config.getForm(formName);
        if (formData == null || !Boolean.TRUE.equals(formData.hasHairCodeOverride())) {
            return; // no override -> let DMZ's else-if (hairType / fallback) run unchanged
        }
        CustomHair[] slots = HairCodeCache.decode(formData.getForcedHairCode());
        if (slots.length == 0) {
            return; // decode failed (cached sentinel) -> fall through to DMZ's own behaviour
        }
        // Full-set codes hold [base, ssj, ssj2, ssj3]; single-slot codes hold exactly [hair].
        int slot = 0;
        if (slots.length >= 4 && Boolean.TRUE.equals(formData.hasDefinedHairType())) {
            slot = HairCodeCache.slotForType(formData.getHairType());
        }
        CustomHair hair = slots[slot];
        if (hair != null) {
            cir.setReturnValue(hair);
        }
        // A null slot is unexpected; leaving cir untouched falls through to DMZ (character.getHairBase()).
    }
}
