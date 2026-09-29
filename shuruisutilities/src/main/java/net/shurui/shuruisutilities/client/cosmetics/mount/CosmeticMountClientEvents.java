package net.shurui.shuruisutilities.client.cosmetics.mount;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountEntities;
import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountEntity;

/**
 * Client wiring for the cosmetic mount: the mod bus binds the renderer, and the forge bus feeds the rider's keys to
 * the mount each tick. The input feed is the same stand-in the hoverbike uses: we cannot patch
 * {@code LocalPlayer.rideTick} the way {@code Boat} is driven, so a tick handler copies the input flags across while
 * the local player is the controlling passenger, and the resulting motion (not the keys) syncs via the vanilla
 * vehicle-move packet.
 */
public final class CosmeticMountClientEvents
{
    private CosmeticMountClientEvents()
    {
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus
    {
        private ModBus()
        {
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerEntityRenderer(CosmeticMountEntities.COSMETIC_MOUNT.get(), CosmeticMountRenderer::new);
        }
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus
    {
        private ForgeBus()
        {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END)
                return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null)
                return;
            if (!(mc.player.getVehicle() instanceof CosmeticMountEntity mount))
                return;
            net.minecraft.client.player.Input in = mc.player.input;
            // jump climbs (flying) or hops (walking); sneak descends on a flying mount. sprint speeds both.
            mount.setInput(in.up, in.down, in.left, in.right, mc.options.keySprint.isDown(), in.jumping,
                    in.shiftKeyDown);
        }
    }
}
