package net.shurui.shuruisutilities.hoverbike;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

// rideable hoverbikes feature. registries live on DeferredRegisters in ShuruisUtilities' ctor; this module only
// owns config (speed/sprint/sound in Hoverbikes.toml). non-LivingEntity vehicle, so no attribute supplier.
@SUModule(name = "Hoverbikes", parentMod = ShuruisUtilities.class, canDisable = true, defaultModule = true, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class ModuleHoverbikes extends ConfigLoaderBase
{
    private static ForgeConfigSpec HOVERBIKES_CONFIG;
    private static final ConfigData data = new ConfigData("Hoverbikes", HOVERBIKES_CONFIG, new ForgeConfigSpec.Builder());

    @Override
    public void load(Builder BUILDER, boolean isReload)
    {
        ConfigHoverbikes.load(BUILDER, isReload);
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        ConfigHoverbikes.bakeConfig(reload);
    }

    @Override
    public ConfigData returnData()
    {
        return data;
    }
}
