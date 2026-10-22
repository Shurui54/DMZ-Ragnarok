package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.ocarina.OcarinaSkills;

/**
 * After DragonMineZ (re)loads its configs, put the ocarina songs and Tapion's shop back into the live skills config
 * ({@link OcarinaSkills}). TAIL, so DMZ has already written skills.json and the songs stay in memory only.
 */
@Mixin(targets = "com.dragonminez.common.config.ConfigManager", remap = false)
public class MixinDmzConfigManagerOcarinaSkills
{
    @Inject(method = "loadGeneralConfigs", at = @At("TAIL"), require = 0)
    private static void su$ocarinaAfterLoad(CallbackInfo ci)
    {
        // The LOCAL config, never getSkillsConfig(): on a connected client that answers the server's synced copy.
        OcarinaSkills.reapply();
    }

    @Inject(method = "reloadSpecificConfig", at = @At("TAIL"), require = 0)
    private static void su$ocarinaAfterReload(String name, CallbackInfo ci)
    {
        if ("skills".equals(name))
            OcarinaSkills.reapply();
    }
}
