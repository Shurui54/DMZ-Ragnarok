package net.shurui.dev.shuruis_dmz_tournaments;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.registry.ModCreativeTabs;
import net.shurui.dev.shuruis_dmz_tournaments.registry.ModEntities;
import net.shurui.dev.shuruis_dmz_tournaments.registry.ModItems;
import org.slf4j.Logger;

// automated single-elimination tournament manager for DragonMineZ. sign-ups, NPC + GUI, arena/waiting/
// stands regions, scheduling, combat rules, announcements, rewards, transferable titles.
// Own @Mod container in the core-plus-modules split: loads either as its own jar or as one of the mods
// declared by the fat jar. Either way it depends on core (dmz_ragnarok, mandatory, loads AFTER it).
@Mod(Shuruis_dmz_tournaments.CONTAINER_ID)
public class Shuruis_dmz_tournaments {
    // FORGE mod-container id (mods.toml / @Mod / @Mod.EventBusSubscriber / presence checks). Distinct from
    // the MODID namespace below.
    public static final String CONTAINER_ID = "dmz_ragnarok_tournaments";

    // REGISTRY / ASSET / DATA namespace. Stays "dmz_ragnarok" so existing ids load unchanged. NEVER change.
    public static final String MODID = "dmz_ragnarok";
    // Public so the client GUI theme package (GuiTheme / NineSlice / LayoutValidator) can log through the mod's
    // own logger, matching the sdu template it was ported from.
    public static final Logger LOGGER = LogUtils.getLogger();

    public Shuruis_dmz_tournaments() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        // no fallback tab: shuruisutilities is now in this same container and always present, so the gems are
        // always injected into SU's gems_souls tab (see ModCreativeTabs.Injector).

        // ForgeEventHandler registers itself via @Mod.EventBusSubscriber
        MinecraftForge.EVENT_BUS.register(this);

        TournamentNet.register();
        // Clients learn the stat gem feature id only when the real key installed TournamentKeyHooks (SF follow-up).
        net.shurui.dev.sdu.network.DmzNet.answerFeature(
                net.shurui.dev.shuruis_dmz_tournaments.api.key.TournamentKeyHooks.FEATURE_ID,
                net.shurui.dev.shuruis_dmz_tournaments.api.key.TournamentKeyHooks::available);
        // Filename pinned so a future single-container merge cannot silently orphan the live config file.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "shuruis_dmz_tournaments-common.toml");
        LOGGER.info("Shurui's DMZ Tournaments loaded.");
    }
}
