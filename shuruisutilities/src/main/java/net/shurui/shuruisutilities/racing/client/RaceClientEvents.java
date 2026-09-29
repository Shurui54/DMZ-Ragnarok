package net.shurui.shuruisutilities.racing.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;
import net.shurui.shuruisutilities.racing.RaceRegistries;
import net.shurui.shuruisutilities.racing.net.PacketRaceUseItem;

/**
 * Client wiring for the racing entities: binds their renderers on the mod bus. The item box uses the real
 * {@link RaceItemBoxRenderer} (R6, the Namek dragon ball), the ki orb the real {@link RaceKiOrbRenderer} (R8, the
 * ki / mine / fake-ball visuals), and the Saibaman the real {@link RaceSaibamanRenderer} (R9, DMZ's saga-saibaman
 * art). Explicit {@code modid = "dmz_ragnarok"} as the split requires.
 */
public final class RaceClientEvents
{
    private RaceClientEvents() {}

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus
    {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerEntityRenderer(RaceRegistries.RACE_ITEM_BOX.get(), RaceItemBoxRenderer::new);
            event.registerEntityRenderer(RaceRegistries.RACE_KI_ORB.get(), RaceKiOrbRenderer::new);
            event.registerEntityRenderer(RaceRegistries.RACE_SAIBAMAN.get(), RaceSaibamanRenderer::new);
        }
    }

    /** Forge-bus client wiring: while racing, right click uses the held powerup (packet 114) instead of the item. */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus
    {
        private ForgeBus() {}

        // DMZ ambient particles for the self powerups: emitted client-side each tick for every nearby race bike whose
        // synced FX bits are set. DMZ is mandatory, so the particle types resolve; the lookup is still guarded so a
        // non-simple or future-removed type degrades to no particles (the aura shell / nimbus discs still draw).
        private static final net.minecraft.resources.ResourceLocation DIVINE =
                new net.minecraft.resources.ResourceLocation("dragonminez", "divine_particle");
        private static final net.minecraft.resources.ResourceLocation KI_SHEDDING =
                new net.minecraft.resources.ResourceLocation("dragonminez", "ki_shedding");

        @SubscribeEvent
        public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event)
        {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.END)
                return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || !RaceClientState.isSessionActive())
                return;
            for (HoverbikeEntity bike : mc.level.getEntitiesOfClass(HoverbikeEntity.class,
                    mc.player.getBoundingBox().inflate(48.0)))
            {
                if (!bike.isRaceBike())
                    continue;
                // Use the effective FX so a remote human racer's server-authored aura also sheds ambient particles.
                int fx = RaceBikeFx.effectiveFx(bike);
                if (fx == 0)
                    continue;
                double bx = bike.getX();
                double by = bike.getY() + 0.6;
                double bz = bike.getZ();
                if (net.shurui.shuruisutilities.racing.physics.RaceFx.has(fx,
                        net.shurui.shuruisutilities.racing.physics.RaceFx.DESTROYER_AURA))
                    emit(mc, DIVINE, bx, by, bz, 3, 0.6);
                if (net.shurui.shuruisutilities.racing.physics.RaceFx.has(fx,
                        net.shurui.shuruisutilities.racing.physics.RaceFx.KAIOKEN)
                        || net.shurui.shuruisutilities.racing.physics.RaceFx.has(fx,
                        net.shurui.shuruisutilities.racing.physics.RaceFx.KAIOKEN_X20))
                    emit(mc, KI_SHEDDING, bx, by, bz, 2, 0.5);
                // The Flying Nimbus draws its own full-bright kinton MODEL under the bike (RaceBikeFx / DmzKintonRender);
                // the old dragonminez:kinton PARTICLE trail is unlit and rose behind the bike as black blobs, so it is no
                // longer emitted here (R11). The model alone reads as the cloud.
            }
        }

        private static void emit(Minecraft mc, net.minecraft.resources.ResourceLocation id, double x, double y,
                                 double z, int count, double spread)
        {
            net.minecraft.core.particles.ParticleType<?> type =
                    net.minecraftforge.registries.ForgeRegistries.PARTICLE_TYPES.getValue(id);
            if (!(type instanceof net.minecraft.core.particles.ParticleOptions options))
                return;
            var rng = mc.level.random;
            for (int i = 0; i < count; i++)
                mc.level.addParticle(options,
                        x + (rng.nextDouble() - 0.5) * spread,
                        y + (rng.nextDouble() - 0.5) * spread,
                        z + (rng.nextDouble() - 0.5) * spread,
                        (rng.nextDouble() - 0.5) * 0.02, 0.04 + rng.nextDouble() * 0.03,
                        (rng.nextDouble() - 0.5) * 0.02);
        }

        // Afterimage (Boo): hide the PLAYER model of anyone (other than yourself) riding a race bike whose
        // server-authored aura carries the afterimage bit. The bike itself is hidden in HoverbikeRenderer; this
        // hides the rider sitting on it. The local player still sees their own ghosted self (never cancelled here).
        @SubscribeEvent
        public static void onRenderPlayer(net.minecraftforge.client.event.RenderPlayerEvent.Pre event)
        {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.entity.player.Player player = event.getEntity();
            if (player == mc.player)
                return;
            if (!(player.getVehicle() instanceof HoverbikeEntity bike) || !bike.isRaceBike())
                return;
            if (net.shurui.shuruisutilities.racing.physics.RaceFx.has(bike.getRaceAura(),
                    net.shurui.shuruisutilities.racing.physics.RaceFx.AFTERIMAGE))
                event.setCanceled(true);
        }

        // Gravity Crush: a short camera shake on the squashed client while its red flash runs, so the crush is felt as
        // well as seen (a HUD fallback for the post shader). Reads only the synced flash window.
        @SubscribeEvent
        public static void onCameraAngles(net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles event)
        {
            if (!RaceClientState.isSessionActive())
                return;
            float g = RaceClientState.gravityAlpha();
            if (g <= 0.001F)
                return;
            long t = System.currentTimeMillis();
            float amp = 2.4F * g; // degrees, fading with the flash
            float roll = (float) Math.sin(t * 0.06) * amp;
            float pitch = (float) Math.sin(t * 0.083) * amp * 0.5F;
            event.setRoll((float) event.getRoll() + roll);
            event.setPitch((float) event.getPitch() + pitch);
        }

        @SubscribeEvent
        public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event)
        {
            if (!event.isUseItem())
                return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !RaceClientState.isSessionActive())
                return;
            if (!(mc.player.getVehicle() instanceof HoverbikeEntity bike) || !bike.isRaceBike())
                return;
            // Racing takes priority over the held item's own right-click. The mouse AIMS: send the crosshair look
            // direction projected onto the road plane; back = drop a mine behind (sneak or S), kept as an alias.
            event.setCanceled(true);
            event.setSwingHand(false);
            boolean back = mc.player.isShiftKeyDown() || (mc.player.input != null && mc.player.input.down);
            double[] aim = RaceInput.aimDirection(mc);
            NetworkUtils.sendToServer(new PacketRaceUseItem(back, (float) aim[0], (float) aim[1]));
        }
    }
}
