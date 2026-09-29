package net.shurui.dev.shuruis_dmz_dungeons.client;

import net.minecraft.client.renderer.DimensionSpecialEffects.SkyType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.client.render.AdvancedSpawnerRenderer;
import net.shurui.dev.shuruis_dmz_dungeons.client.render.CrateRenderer;
import net.shurui.dev.shuruis_dmz_dungeons.compat.sdu.SduHubCompat;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlockEntities;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlocks;

// client-only mod-bus subscriptions (same shape as sdu's ClientModBusEvents).
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModBusEvents {

    private ClientModBusEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.ADVANCED_SPAWNER.get(), AdvancedSpawnerRenderer::new);
        // both crate forms draw through the same GeckoLib renderer; which of the twelve models a viewer sees is
        // decided per crate in CrateGeoModel.
        event.registerBlockEntityRenderer(ModBlockEntities.CRATE_CHEST.get(), c -> new CrateRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.CRATE_BARREL.get(), c -> new CrateRenderer());
    }

    // add the dungeon config GUI to the shared "/rg npc edit" hub when SDU is present. Guarded + probed inside the
    // compat class, so this call is safe whether or not SDU is loaded. Also binds the one-slot crate reveal menu to
    // its DMZ-panel-skinned screen.
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            SduHubCompat.register();
        });
    }

    // the four themed-dimension effect groups referenced by our dimension_type JSONs
    // (effects: "shuruis_dmz_dungeons:{surface,otherworld,nether,end}"). One configurable subclass, four instances.
    @SubscribeEvent
    public static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        // These keys must equal the "effects" ids in our dimension_type JSONs. Those files moved to the dmz_ragnarok
        // namespace in the rename stage, so the live ids are dmz_ragnarok:{surface,otherworld,nether,end}. We ALSO
        // register the old shuruis_dmz_dungeons:{...} ids: a pre-migration world bakes its dimension_type (effects
        // field included) into level.dat referencing the old id, so without these it loses the custom sky/fog until
        // migrated with world-tools/ns-rename. Harmless once every live world is migrated.
        // surface: normal gradient sky with sun, moon and clouds at 192; daylit themes differ via biome colours.
        register(event, "surface",
                new DungeonDimensionEffects(192.0F, true, SkyType.NORMAL, false, false, true, true, false),
                new DungeonDimensionEffects(192.0F, true, SkyType.NORMAL, false, false, true, true, false));
        // otherworld: no sun/moon (NONE sky), a low flat cloud deck, thick warm biome-driven fog.
        register(event, "otherworld",
                new DungeonDimensionEffects(96.0F, true, SkyType.NONE, false, false, false, false, true),
                new DungeonDimensionEffects(96.0F, true, SkyType.NONE, false, false, false, false, true));
        // nether: no sky, no clouds, dense dark-red fog hugging the camera.
        register(event, "nether",
                new DungeonDimensionEffects(Float.NaN, true, SkyType.NONE, false, true, false, false, true),
                new DungeonDimensionEffects(Float.NaN, true, SkyType.NONE, false, true, false, false, true));
        // end: black starfield sky, no sun/moon, no clouds, dim purple fog.
        register(event, "end",
                new DungeonDimensionEffects(Float.NaN, false, SkyType.END, false, true, false, false, true),
                new DungeonDimensionEffects(Float.NaN, false, SkyType.END, false, true, false, false, true));
    }

    // register under both the live dmz_ragnarok id and the legacy shuruis_dmz_dungeons id (pre-migration worlds
    // name the old effects id). Two instances because Forge stores one per key.
    private static void register(RegisterDimensionSpecialEffectsEvent event, String path,
                                 net.minecraft.client.renderer.DimensionSpecialEffects live,
                                 net.minecraft.client.renderer.DimensionSpecialEffects legacy) {
        event.register(new ResourceLocation("dmz_ragnarok", path), live);
        event.register(new ResourceLocation("shuruis_dmz_dungeons", path), legacy);
    }
}
