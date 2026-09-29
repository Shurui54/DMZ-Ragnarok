package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import net.shurui.dev.sdu.form.CustomFormTypes;
import net.shurui.dev.sdu.form.FormDescendLadder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Two repairs to DMZ's common transformation helper: custom form types are routed to their own skill, and a
// descend steps DOWN the ladder instead of backwards through the group file.
//
// 1) Form-skill routing for custom form types.
//
// getSkillNameForType(String) maps any id CONTAINING super/legendary/god/android to the matching stock
// *forms skill, else returns the id verbatim. It feeds both radial unlock gating (client) and
// transform-ascension gating (common) via Skills.isUnlockedAtLevel. So a custom type whose id contains a
// reserved substring (e.g. god_of_destruction) gets gated against the wrong stock skill instead of its own
// (which FormTypeManager registers under the raw id), and its radial group can vanish even after the player
// buys the custom skill.
//
// Short-circuit at HEAD: a registered custom type (CustomFormTypes) returns the raw id (its own skill name).
// Fixing this one common method corrects gating everywhere. require=0 so a renamed/removed method degrades
// to stock instead of hard-failing.
//
// 2) The descend target. See sdu$descendByLadderNotFileOrder below.
@Mixin(targets = "com.dragonminez.common.util.TransformationsHelper", remap = false)
public abstract class TransformationsHelperMixin {

    @Inject(method = "getSkillNameForType", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$routeCustomTypeToOwnSkill(String formType, CallbackInfoReturnable<String> cir) {
        if (formType != null && CustomFormTypes.contains(formType)) {
            cir.setReturnValue(formType);
        }
    }

    // Makes descending a step DOWN, always.
    //
    // getPreviousForm returns whatever entry sits immediately before the active form in the group's forms map,
    // i.e. in raw JSON key order, and ExecuteActionC2S.descendForm then sets it active with no skill, mastery or
    // level check. For a group file whose keys are not in ladder order that entry can be a HIGHER form, so a
    // descend walks UP and drops the player into a form they never bought (frost demon reports of
    // 3rd -> 2nd -> full power -> final -> fifth are exactly the alphabetical key order read backwards).
    //
    // FormDescendLadder computes the rung from the CONTENT (unlockOnSkillLevel, then name) and only offers one
    // the character may actually enter, so the answer no longer depends on how the file happens to be laid out.
    // Null means no lower rung, which is already DMZ's signal to revert to base.
    //
    // HEAD + cancellable, require = 0: a renamed or removed target leaves DMZ's own walk in place. Fails open on
    // any unexpected shape, because refusing to descend would trap a player in a form.
    @Inject(method = "getPreviousForm", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$descendByLadderNotFileOrder(StatsData statsData,
                                                        CallbackInfoReturnable<FormConfig.FormData> cir) {
        try {
            cir.setReturnValue(FormDescendLadder.previous(statsData));
        } catch (Throwable ignored) {
            // leave DMZ's result alone rather than break a descend
        }
    }
}
