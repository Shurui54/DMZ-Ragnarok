package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.config.SkillsConfig;

/**
 * Puts the {@code su_mini_clone} technique in Tien's, Cell's and Majin Buu's master shops.
 *
 * <p>Added to DMZ's own skill DEFAULTS rather than patched into the live config, because that is the path DMZ already
 * uses to roll a new skill out: {@code SkillsConfig.addMissingDefaults} compares the loaded skills.json against a fresh
 * {@code new SkillsConfig()}, and for a skill id the file lacks it adds the cost entry AND appends the id to every
 * master's offering list that has it by default, then saves the file. So an existing server gets the shop entry once,
 * an operator can still reprice or remove it in skills.json, and a /dmz reload keeps it. The client builds its copy
 * from the server's synced json, so both sides agree.
 *
 * <p>The id goes in kiSkills too: that is what files it under the shop's KI tab and what lets
 * {@code UpdateSkillC2S.PURCHASE} unlock it as a technique for any race. Tien teaches multiplying himself, Cell spawns
 * Cell Jr. and Buu splits off pieces of himself, which is why those three sell it. {@code require = 0}: a DMZ rename
 * means the shop entry is missing, never a failed load.
 */
@Mixin(value = SkillsConfig.class, remap = false)
public abstract class MixinDmzSkillsConfigMiniClone
{
    // Must match MiniClone.TECHNIQUE_ID; not referenced directly so this mixin pulls no suite class into DMZ's load.
    private static final String SU_MINI_CLONE = "su_mini_clone";
    private static final int SU_MINI_CLONE_TP_COST = 8000;
    private static final String[] SU_MINI_CLONE_MASTERS = {"tien", "cell", "buu"};

    @Shadow(remap = false) @Final private List<String> kiSkills;
    @Shadow(remap = false) @Final private Map<String, SkillsConfig.SkillCosts> skills;
    @Shadow(remap = false) @Final private Map<String, List<String>> skillOfferings;

    @Inject(method = "createDefaults", at = @At("TAIL"), require = 0, remap = false)
    private void su$offerMiniClone(CallbackInfo ci)
    {
        if (!this.kiSkills.contains(SU_MINI_CLONE))
            this.kiSkills.add(SU_MINI_CLONE);
        this.skills.putIfAbsent(SU_MINI_CLONE, new SkillsConfig.SkillCosts(new ArrayList<>(List.of(SU_MINI_CLONE_TP_COST))));
        for (String master : SU_MINI_CLONE_MASTERS)
        {
            List<String> offered = this.skillOfferings.computeIfAbsent(master, k -> new ArrayList<>());
            if (!offered.contains(SU_MINI_CLONE))
                offered.add(SU_MINI_CLONE);
        }
    }
}
