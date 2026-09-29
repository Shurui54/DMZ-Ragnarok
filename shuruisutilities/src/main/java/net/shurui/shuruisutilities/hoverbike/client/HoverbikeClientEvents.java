package net.shurui.shuruisutilities.hoverbike.client;

import net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes;
import net.shurui.shuruisutilities.hoverbike.HoverbikeEntities;
import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// client wiring: mod bus binds the renderer + registers the four models for baking; forge bus feeds rider input
// each tick and starts the engine loop on mount.
public final class HoverbikeClientEvents
{
    private HoverbikeClientEvents() {}

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus
    {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerEntityRenderer(HoverbikeEntities.HOVERBIKE.get(), HoverbikeRenderer::new);
        }

        @SubscribeEvent
        public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event)
        {
            for (int v = 1; v <= 4; v++)
            {
                event.register(HoverbikeModels.LOCATION[v]);
                if (HoverbikeModels.EMISSIVE_LOCATION[v] != null)
                    event.register(HoverbikeModels.EMISSIVE_LOCATION[v]);
            }
        }
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus
    {
        private ForgeBus() {}

        // bike whose engine loop we last started, so we don't queue duplicates
        private static HoverbikeEntity active;

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END)
                return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null)
            {
                active = null;
                return;
            }

            HoverbikeEntity riding = mc.player.getVehicle() instanceof HoverbikeEntity h ? h : null;

            if (riding == null)
            {
                active = null;
                return;
            }

            // A race bike drives on the shared kart physics: the race input path feeds the same keys but also locks
            // the camera to the bike heading and handles drift / autopilot. A normal personal bike is unchanged.
            if (riding.isRaceBike())
            {
                net.shurui.shuruisutilities.racing.client.RaceInput.handle(mc, riding);
            }
            else
            {
                // feed rider input each tick (stand-in for LocalPlayer.rideTick -> Boat.setInput, which we can't
                // patch). tick() reads these on the authoritative client; the motion syncs via the vehicle-move packet.
                net.minecraft.client.player.Input in = mc.player.input;
                riding.setInput(in.up, in.down, in.left, in.right, mc.options.keySprint.isDown(), in.jumping);
            }

            if (riding != active)
            {
                active = riding;
                if (ConfigHoverbikes.soundEnabled)
                    mc.getSoundManager().play(new HoverbikeSoundInstance(riding));
            }
        }
    }
}
