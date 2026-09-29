package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.stats.skills.Skill;
import net.shurui.dev.sdu.form.FormTombstoneStore;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Set;

// Strips addon-tombstoned skill keys out of DMZ's per-player skill map BEFORE its fuzzy name repair can
// resurrect them.
//
// DMZ keeps every unlocked skill (regular and form/stack-form) in one flat map Skills.skillMap. When the
// editor deletes a custom form type, its name is gone from skills.json but a player who unlocked it still
// carries the key. On data load repairSkillNames() builds a valid-name set from the current config and
// fuzzy-migrates any key not in it onto the closest surviving skill (plural/singular alias, then Levenshtein
// >= 0.8), so the orphan reappears as a phantom skill. This runs at world/data load BEFORE any login handler
// could react, so a login-time cleanup isn't enough.
//
// We inject at repairSkillNames HEAD and drop exactly the keys recorded in FormTombstoneStore (never generic
// unknown keys, which may belong to other mods). DMZ lowercases every skillMap key and the tombstone store
// keeps its names lowercase, so removals line up. UsedForms has no comparable repair hook, so tombstoned
// groups are scrubbed from UsedForms in the online-player + login paths instead (stale UsedForms names are
// only cosmetic once the skill entry is gone).
//
// Common (not client-only): skill data loads and repairs server-side. Targeted by literal name, remap=false.
@Mixin(targets = "com.dragonminez.common.stats.skills.Skills", remap = false)
public abstract class SkillsRepairMixin {

    @Shadow
    @Final
    private Map<String, Skill> skillMap;

    @Inject(method = "repairSkillNames", at = @At("HEAD"))
    private void sdu$dropTombstonedSkills(CallbackInfoReturnable<Map<String, String>> cir) {
        Set<String> tombstoned = FormTombstoneStore.skills();
        if (tombstoned.isEmpty() || this.skillMap.isEmpty()) {
            return;
        }
        // both skillMap and tombstone keys are lowercase, so keySet().removeAll is exact
        this.skillMap.keySet().removeAll(tombstoned);
    }
}
