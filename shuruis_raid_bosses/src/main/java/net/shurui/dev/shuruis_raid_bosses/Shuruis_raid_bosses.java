package net.shurui.dev.shuruis_raid_bosses;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.registry.ModCreativeTabs;
import net.shurui.dev.shuruis_raid_bosses.registry.ModEntities;
import net.shurui.dev.shuruis_raid_bosses.registry.ModItems;
import org.slf4j.Logger;

/**
 * Shurui's Raid Bosses: an automated raid boss manager for DragonMineZ. Sign-ups, an NPC + GUI, a
 * WorldEdit arena with PvP disabled, per-player damage tracking, count-scaled health, a timer,
 * announcements, and damage-based rewards.
 *
 * <p>Own @Mod container in the core-plus-modules split: loads either as its own jar or as one of the mods
 * declared by the fat jar. Either way it depends on core (dmz_ragnarok, mandatory, loads AFTER it).
 */
@Mod(Shuruis_raid_bosses.CONTAINER_ID)
public class Shuruis_raid_bosses {
    // FORGE mod-container id (mods.toml / @Mod / @Mod.EventBusSubscriber / presence checks). Distinct from
    // the MODID namespace below.
    public static final String CONTAINER_ID = "dmz_ragnarok_raids";

    // REGISTRY / ASSET / DATA namespace. Stays "dmz_ragnarok" so existing ids load unchanged. NEVER change.
    public static final String MODID = "dmz_ragnarok";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Shuruis_raid_bosses() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        net.shurui.dev.shuruis_raid_bosses.sound.BossMusicSounds.REGISTER.register(modEventBus);
        // shuruisutilities is now in this same container and always present, so souls always inject into
        // SU's gems_souls tab (see ModCreativeTabs.Injector).

        MinecraftForge.EVENT_BUS.register(this);

        // The Z-Soul over-cap grant that Tournaments' Stat Gem overflow reaches through core's ZSoulHook is private:
        // the Ragnarok Key registers it. Keyless the core hook stays unset and the grant lands 0, as it did before.

        // Let the event engine's bundle import (e.g. /event import builtin:halloween) store this module's
        // eventOnly raids and rifts from SNBT, without the key ever naming Raids. No-op when Raids is absent.
        net.shurui.dev.shuruis_raid_bosses.data.RaidContentImport.register();

        RaidNet.register();
        // Clients learn the rift and Z-Soul feature ids only when the real key installed RaidKeyHooks (SF follow-up).
        net.shurui.dev.sdu.network.DmzNet.answerFeature(
                net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.FEATURE_ID,
                net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks::available);
        net.shurui.dev.sdu.network.DmzNet.answerFeature(
                net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.ZSOUL_FEATURE_ID,
                net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks::available);
        // Filename pinned so the single-container merge cannot silently orphan the live config file.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "shuruis_raid_bosses-common.toml");
        LOGGER.info("Shurui's Raid Bosses loaded.");
    }
}
