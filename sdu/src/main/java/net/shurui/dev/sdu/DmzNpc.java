package net.shurui.dev.sdu;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
// No @Mod here: the single container net.shurui.dev.ragnarok.DmzRagnarok constructs this class.
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.shurui.dev.sdu.compat.dmz.DmzLogAddonMuteFilter;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.registry.ModCreativeTabs;
import net.shurui.dev.sdu.registry.ModEntities;
import net.shurui.dev.sdu.registry.ModItems;
import org.slf4j.Logger;

/**
 * DMZ NPCs, a DragonMine Z addon: a configurable GeckoLib-backed custom NPC with an in-game editor.
 * DMZ integration goes through a reflection-guarded compat layer so DMZ updates cannot hard-crash it.
 */
public class DmzNpc {

    public static final String MODID = "dmz_ragnarok";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DmzNpc() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::loadComplete);
        // Dev-only: log per-container EventBusSubscriber counts at load complete so a fat-jar boot and a
        // modular boot can be compared (they must match). No-op in a shipped production jar.
        modEventBus.addListener(net.shurui.dev.sdu.SplitDiagnostics::onLoadComplete);

        ModEntities.ENTITY_TYPES.register(modEventBus);
        // Touch ModBlocks first so its block-item registrations reach ModItems.ITEMS BEFORE ITEMS
        // registers on the mod bus below.
        net.shurui.dev.sdu.registry.ModBlocks.BLOCKS.register(modEventBus);
        // Block entities reference block instances, so register AFTER ModBlocks.
        net.shurui.dev.sdu.registry.ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        // The TP-boost gem pip. Registered on the mod bus alongside the items it reads from.
        net.shurui.dev.sdu.buff.TpBoostEffect.EFFECTS.register(modEventBus);
        // The stat-discount gem pip, same pattern against the STAT category.
        net.shurui.dev.sdu.buff.StatBoostEffect.EFFECTS.register(modEventBus);
        // The server-wide /tpboost window pip. Registered on EVERY install so no client hits a missing registry entry;
        // whether it is ever applied is decided by shuruisutilities, which owns the window and its key gating.
        net.shurui.dev.sdu.buff.GlobalTpBoostEffect.EFFECTS.register(modEventBus);
        // The five shared content tabs and their 233 items live in shuruisutilities (same container now).
        // sdu only inserts its legacy items into those SU tabs (ModCreativeTabs.Injector).
        net.shurui.dev.sdu.registry.ModSounds.SOUND_EVENTS.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);

        // Filename pinned so a future single-container merge cannot silently orphan the live config file.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "sdu-common.toml");
        // Client-only HUD toggles. The spec touches no client-only MC types, so registering on both
        // dists is safe: a dedicated server just loads the CLIENT file and never reads the values.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, net.shurui.dev.sdu.client.ClientConfig.SPEC, "sdu-client.toml");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[{}] common setup - DragonMine Z addon loading", MODID);
        // log4j filter to mute DMZ log lines about our namespaces. DMZ's "dragonminez" LoggerConfig may
        // not exist yet; this no-ops until it does, and we retry on FMLLoadCompleteEvent. Primary
        // suppression is JsonLoadReportMixin.
        DmzLogAddonMuteFilter.tryInstall();
        // Form damage mitigation runs on LivingDamageEvent at LOWEST, registered here (not @SubscribeEvent)
        // on purpose. DMZ's CombatEvent.overrideVanillaArmorReduction is also at LOWEST and rebuilds final
        // damage from a raw snapshot, ignoring the current amount, so a reduction applied on LivingHurtEvent
        // (or an earlier LOWEST listener) is discarded. Same-priority listeners fire in registration order,
        // and DMZ's @Mod.EventBusSubscriber registers during construction (before this setup), so registering
        // here lands ours after DMZ's and its reduction survives.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDamageEvent.class,
                net.shurui.dev.sdu.event.FormCombatHandler::onLivingDamage);
        event.enqueueWork(DmzNet::register);
        // If the key installs a private feature AFTER players are online (unlikely: it marks in its constructor,
        // before any login), re-push the installed set to everyone. No server running yet = nothing to do; the
        // login sync covers the normal case. sdu reaches no SU here, only Forge + its own DmzNet.
        net.shurui.dev.sdu.api.KeyFeatures.setListener(id -> {
            net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                DmzNet.syncKeyFeaturesToAll(server);
            }
        });
        // Resolve the optional DMZ x Custom NPCs bridge. init() holds the single isModLoaded probe for
        // customnpcs and the single place the model-bridge handler goes on the bus.
        event.enqueueWork(net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat::init);
    }

    private void loadComplete(final FMLLoadCompleteEvent event) {
        // Retry: by now DMZ's "dragonminez" LoggerConfig is present, so the filter can attach.
        DmzLogAddonMuteFilter.tryInstall();
    }
}
