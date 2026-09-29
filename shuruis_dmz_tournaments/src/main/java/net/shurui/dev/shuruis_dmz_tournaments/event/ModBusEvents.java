package net.shurui.dev.shuruis_dmz_tournaments.event;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.shurui.dev.shuruis_dmz_tournaments.client.TournamentNpcRenderer;
import net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpc;
import net.shurui.dev.shuruis_dmz_tournaments.registry.ModEntities;

/** Mod-bus registrations: entity attributes (both sides) and the client entity renderer. */
public final class ModBusEvents {
    private ModBusEvents() {}

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_tournaments", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Common {
        @SubscribeEvent
        public static void onAttributes(EntityAttributeCreationEvent event) {
            event.put(ModEntities.TOURNAMENT_NPC.get(), TournamentNpc.createAttributes().build());
        }
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_tournaments", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            // optional: add a "Tournaments" section into sdu's shared /rg npc edit hub when sdu is present.
            // Guarded (SduHubCompat checks isModLoaded before touching sdu types).
            event.enqueueWork(net.shurui.dev.shuruis_dmz_tournaments.compat.sdu.SduHubCompat::register);
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.TOURNAMENT_NPC.get(), TournamentNpcRenderer::new);
        }
    }
}
