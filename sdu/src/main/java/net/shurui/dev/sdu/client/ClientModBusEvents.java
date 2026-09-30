package net.shurui.dev.sdu.client;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.AddPackFindersEvent;
import net.shurui.dev.sdu.client.hud.CompassOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.shurui.dev.sdu.registry.ModEntities;

/** Client-only, mod-event-bus subscriptions (renderers, reload listeners). */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModBusEvents {

    private ClientModBusEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.DMZ_FIGHTER.get(),
                net.shurui.dev.sdu.client.renderer.SduDmzFighterRenderer::new);
        event.registerEntityRenderer(ModEntities.SHENRON.get(),
                net.shurui.dev.sdu.client.renderer.ShenronRenderer::new);
        event.registerEntityRenderer(ModEntities.DUKE_SNIPPERJACK.get(),
                net.shurui.dev.sdu.client.renderer.DukeSnipperjackRenderer::new);
        event.registerEntityRenderer(ModEntities.PUMPKIN_PUPPET.get(),
                net.shurui.dev.sdu.client.renderer.PumpkinPuppetRenderer::new);
        event.registerEntityRenderer(ModEntities.PILAF_MECH.get(),
                net.shurui.dev.sdu.client.renderer.PilafMechRenderer::new);
        // Level Barrier: invisible block model; the BER draws an animated force-field cube (open/closed
        // sprite per viewing player) for everyone.
        event.registerBlockEntityRenderer(
                net.shurui.dev.sdu.registry.ModBlockEntities.LEVEL_BARRIER.get(),
                net.shurui.dev.sdu.client.renderer.BarrierBER::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        // Custom 64x64 armor model for "Shurui's Armor" (geometry copied from DMZ's ArmorBaseModel,
        // re-namespaced to sdu). The recolored textures are authored against this model's UVs.
        event.registerLayerDefinition(
                net.shurui.dev.sdu.client.model.ShuruisArmorModel.LAYER_LOCATION,
                net.shurui.dev.sdu.client.model.ShuruisArmorModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        // Load persisted custom race/form names and overlay them onto the active language.
        // (Custom forms cycle in DMZ's normal top-middle form slot; no extra wheel slot is added.)
        event.enqueueWork(GeneratedLang::init);
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        // Scans every loaded resource pack for assets/<ns>/npc_models/*.json into the model registry.
        // After a resource/language reload the language instance is replaced, so re-apply our overlay.
        // Also clear DMZ's model-resolution cache: a custom race geo enabled by a pack toggle (or F3+T)
        // would otherwise stay resolved to the human fallback until a full client restart.
        event.registerReloadListener((ResourceManagerReloadListener) rm -> {
            GeneratedLang.reinject();
            net.shurui.dev.sdu.compat.dmz.DmzModelCache.clear();
            // Forced-hair-code decode cache mirrors the model cache: a pack toggle can change a form's code.
            net.shurui.dev.sdu.compat.dmz.HairCodeCache.clear();
            // Barrier BER caches its open/closed atlas sprites; the atlas is rebuilt on reload.
            net.shurui.dev.sdu.client.renderer.BarrierBER.invalidateSprites();
        });
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        SduKeybinds.register(event);
    }

    // NO TINT HANDLER. Both token families wear real per-tier art now - the rarity badges for the stat tokens,
    // the server's own gem art for the TP ones - and multiplying a finished colour texture by a tier tint would
    // wash every tier of a family into the same colour. The tints themselves are gone from ModItems' comment as
    // history; TintedGem stays on the items but nothing asks it for a colour any more.

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        // Drawn above the hotbar so the quest/waypoint compass sits at the top of the HUD.
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "sdu_compass", CompassOverlay.COMPASS);
        // A vanilla-style crosshair for DMZ's over-the-shoulder camera (vanilla only draws it in first
        // person). Placed just above vanilla's own crosshair layer so it shares that HUD position.
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "sdu_over_shoulder_crosshair",
                net.shurui.dev.sdu.client.hud.OverShoulderCrosshairOverlay.OVER_SHOULDER_CROSSHAIR);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws java.io.IOException {
        SduShaders.register(event);
    }

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        // Generate (first launch) and force-load the shuruis_dmz_utils delivery pack. It is always
        // on and cannot be disabled. Client resources only.
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            PlaceholderResourcePack.registerForcedPack(event);
        }
    }
}
