package net.shurui.shuruisutilities.saibaman;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;

/**
 * Registers {@code SaibamanPet.toml} (config name "SaibamanPets", the same name and file the {@code SaibamanPets}
 * module used) and bakes it into {@link ConfigSaibamanPet}, on EVERY server. The tamed saibaman is public (seed to
 * pet), and its stats come from this file, so it must load keyless too: before S19a the module did this and was torn
 * down only after its config had already been baked. Registered by core ({@code ShuruisUtilities.initConfiguration}),
 * next to the main config, so it loads with the module configs. Editing the stats in game (the hub row) and carrying
 * them across shards stay private in the Ragnarok Key.
 */
public class SaibamanPetConfigLoader extends ConfigLoaderBase
{
    private static ForgeConfigSpec SAIBAMAN_CONFIG;
    private static final ConfigData data = new ConfigData("SaibamanPets", SAIBAMAN_CONFIG, new ForgeConfigSpec.Builder());

    @Override
    public void load(Builder BUILDER, boolean isReload)
    {
        ConfigSaibamanPet.load(BUILDER, isReload);
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        ConfigSaibamanPet.bakeConfig(reload);
    }

    @Override
    public ConfigData returnData()
    {
        return data;
    }
}
