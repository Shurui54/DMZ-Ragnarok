package net.shurui.dev.shuruis_dmz_dungeons;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.shurui.dev.shuruis_dmz_dungeons.network.SddNet;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlockEntities;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlocks;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModCreativeTabs;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModItems;
import org.slf4j.Logger;

// Shurui's DMZ Dungeons: adds instanced void dungeons, per-dim world rules (PvP + ki block destruction), the
// advanced NPC spawn block, and optional WorldEdit schematic pasting. Thin, just wires registers + network.
//
// Own @Mod container in the core-plus-modules split: this loads either as its own jar or as one of the mods
// declared by the fat jar. Either way it depends on core (dmz_ragnarok, mandatory, loads AFTER it).
@Mod(Shuruis_dmz_dungeons.CONTAINER_ID)
public class Shuruis_dmz_dungeons {

    // The FORGE mod-container id. This is the id in mods.toml / @Mod / @Mod.EventBusSubscriber, and what
    // "is this module present" checks look for. It is DISTINCT from MODID below.
    public static final String CONTAINER_ID = "dmz_ragnarok_dungeons";

    // The REGISTRY / ASSET / DATA namespace. Stays "dmz_ragnarok" so this module's blocks, items, block
    // entities, menus, dimensions and assets keep the exact ids they have on disk today: an existing world
    // loads unchanged whether this module arrives via its own jar or the fat jar. NEVER change this value.
    public static final String MODID = "dmz_ragnarok";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Shuruis_dmz_dungeons() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        net.shurui.dev.shuruis_dmz_dungeons.registry.ModMenus.MENUS.register(modEventBus);
        // shuruisutilities is now in this same container and always present, so this mod's items are always
        // injected into the SU tabs (see ModCreativeTabs.Injector).

        modEventBus.addListener(this::commonSetup);

        // forge-bus subscribers (commands, rule enforcement, login sync) live in event/ via @Mod.EventBusSubscriber.
        // rule + time enforcement are ALSO registered explicitly below so they don't rely on annotation scanning alone.
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(net.shurui.dev.shuruis_dmz_dungeons.event.DungeonRuleEvents.class);
        MinecraftForge.EVENT_BUS.register(net.shurui.dev.shuruis_dmz_dungeons.event.DungeonTimeEvents.class);

        // SU integration (same container, always active): veto SU /portal teleports into the dungeon dim while
        // on cooldown, BEFORE any transfer runs (a same-tick double dim change hangs the client).
        net.shurui.dev.shuruis_dmz_dungeons.compat.UtilitiesPortalCompat.init();
        // reuse SU's ss_ticket item as the dungeon floor ticket (right-click warp when it carries a floor tag).
        net.shurui.dev.shuruis_dmz_dungeons.compat.UtilitiesTicketCompat.init();

        // Publish this module's shared arena dimensions to core, so the Raid Bosses rift system can host a
        // tear arena in a dungeon theme dimension without a direct dependency on this module. Absent Dungeons,
        // the core hook stays unset and rifts refuse to open.
        net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonArenaHookImpl.register();

        // Publish the event-floor provider to core, so the private event engine (Ragnarok Key) can stand up a
        // temporary event dungeon floor without naming this module. Absent Dungeons, the core hook stays unset and
        // /event dungeon declines cleanly; the event floors live in a LOCAL store that never enters the shared sync.
        net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonEventFloorHookImpl.register();

        // Clients learn the dungeon feature ids only when the real key installed their hooks (SF follow-up).
        net.shurui.dev.sdu.network.DmzNet.answerFeature(
                net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonKeyHooks.FEATURE_ID,
                net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonKeyHooks::available);
        net.shurui.dev.sdu.network.DmzNet.answerFeature(
                net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonRewardHooks.FEATURE_ID,
                net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonRewardHooks::available);

        // Filename pinned so a future single-container merge cannot silently orphan the live config file.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "shuruis_dmz_dungeons-common.toml");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[{}] common setup - DragonMine Z dungeon addon loading", MODID);
        // register SimpleChannel packets after registries freeze
        event.enqueueWork(SddNet::register);
    }
}
