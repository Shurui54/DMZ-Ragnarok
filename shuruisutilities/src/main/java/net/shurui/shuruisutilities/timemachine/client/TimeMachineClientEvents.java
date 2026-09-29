package net.shurui.shuruisutilities.timemachine.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.block.SUBlockEntities;
import net.shurui.shuruisutilities.timemachine.TimeMachineEntities;
import net.shurui.shuruisutilities.timemachine.TimeMachineEntity;

/**
 * Client wiring for the time machine: the mod bus binds the entity + block-entity GeckoLib renderers, and the forge
 * bus feeds rider input each tick (the same stand-in for {@code LocalPlayer.rideTick} the hoverbike uses, since we
 * cannot patch it; the resulting motion, not the keys, is what syncs via the vanilla vehicle-move packet).
 */
public final class TimeMachineClientEvents
{
    private TimeMachineClientEvents() {}

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus
    {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerEntityRenderer(TimeMachineEntities.TIME_MACHINE.get(), TimeMachineRenderer::new);
            event.registerBlockEntityRenderer(SUBlockEntities.TIME_MACHINE.get(), TimeMachineBlockRenderer::new);
        }
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus
    {
        private ForgeBus() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END)
                return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null)
                return;
            if (!(mc.player.getVehicle() instanceof TimeMachineEntity machine))
                return;

            net.minecraft.client.player.Input in = mc.player.input;
            machine.setInput(in.up, in.down, in.left, in.right,
                    mc.options.keySprint.isDown(), in.jumping, mc.options.keyShift.isDown());
        }
    }
}
