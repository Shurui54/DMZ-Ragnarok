package net.shurui.dev.shuruis_raid_bosses.event;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.shuruis_raid_bosses.client.RaidNpcRenderer;
import net.shurui.dev.shuruis_raid_bosses.entity.RaidNpc;
import net.shurui.dev.shuruis_raid_bosses.registry.ModEntities;

/** Mod-bus registrations: entity attributes (both sides) and the client entity renderer. */
public final class ModBusEvents {
    private ModBusEvents() {}

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_raids", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Common {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

        @SubscribeEvent
        public static void onAttributes(EntityAttributeCreationEvent event) {
            event.put(ModEntities.RAID_NPC.get(), RaidNpc.createAttributes().build());
        }

        /**
         * Vanilla hard-caps ATTACK_DAMAGE at 2048, silently clamping higher boss melee. DMZ lifts the
         * armor/toughness/max-health caps this way but not attack damage, so raise it through DMZ's
         * {@code RangedAttribute} mixin duck (DMZ is mandatory; its mixin makes every RangedAttribute
         * implement the interface). Needed so big {@code baseMeleeDamage} survives DMZ transformations,
         * which compute the new form's melee from the OLD form's (previously clamped) attribute value.
         */
        @SubscribeEvent
        public static void onLoadComplete(net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent event) {
            event.enqueueWork(() -> {
                if (net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE
                        instanceof com.dragonminez.mixin.common.RangedAttributeMixin ranged) {
                    ranged.setMaxValue(Float.MAX_VALUE);
                    LOGGER.info("[Shurui's Raid Bosses] Raised the vanilla ATTACK_DAMAGE cap (2048) so high boss melee values apply in full.");
                } else {
                    LOGGER.warn("[Shurui's Raid Bosses] Could not raise the ATTACK_DAMAGE cap, DMZ's RangedAttribute mixin is not applied; boss melee stays clamped at 2048.");
                }
            });
        }
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_raids", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            // Adds a "Raid Bosses" dropdown into sdu's /rg npc edit hub when sdu is present. SduHubCompat
            // checks isModLoaded before touching sdu types.
            event.enqueueWork(net.shurui.dev.shuruis_raid_bosses.compat.sdu.SduHubCompat::register);
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.RAID_NPC.get(), RaidNpcRenderer::new);
            event.registerEntityRenderer(ModEntities.DIMENSIONAL_TEAR.get(),
                    net.shurui.dev.shuruis_raid_bosses.client.DimensionalTearRenderer::new);
        }

        @SubscribeEvent
        public static void onRegisterGuiOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
            // The raid progress bar (DMZ ki-sense style) that replaced the vanilla boss bar.
            event.registerAboveAll(net.shurui.dev.shuruis_raid_bosses.client.RaidHudOverlay.OVERLAY_ID,
                    net.shurui.dev.shuruis_raid_bosses.client.RaidHudOverlay::render);
        }
    }
}
