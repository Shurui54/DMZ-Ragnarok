package net.shurui.shuruisutilities.client.space;

import com.dragonminez.common.init.entities.SpacePodEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import net.shurui.shuruisutilities.space.PlanetDefenderEntities;
import net.shurui.shuruisutilities.space.PlanetGarrisonDefenderEntities;
import net.shurui.shuruisutilities.space.PlanetSaiyanGarrisonEntities;
import net.shurui.shuruisutilities.space.PlanetSaiyanTownEntities;
import net.shurui.shuruisutilities.space.SpaceDimension;
import net.shurui.shuruisutilities.space.SurfaceDimension;

/**
 * Client-only, mod-event-bus registration of the sky effects for BOTH space dimensions: open space and the shared
 * planet-surface dimension. Gated to {@link Dist#CLIENT} so the renderer class is never loaded on a dedicated server.
 * Each effect is keyed on the ResourceLocation the matching dimension_type's "effects" field points at
 * (shuruisutilities:space and shuruisutilities:planet_surface). Both use the SAME {@link SpaceDimensionEffects}
 * implementation and therefore the same procedural star buffer, so a planet's surface reads as a rock in space; the
 * surface variant just adds dense fog. Mirrors the corrupted-event CorruptedClientBusEvents registration idiom.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SpaceClientBusEvents
{
    private SpaceClientBusEvents()
    {
    }

    @SubscribeEvent
    public static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event)
    {
        // The effects key equals the dimension id, which the dimension_type JSON references in its "effects" field.
        // SpaceDimension.ID / SurfaceDimension.ID now live in the dmz_ragnarok namespace, matching the new
        // data/dmz_ragnarok dimension_type files.
        event.register(SpaceDimension.ID, new SpaceDimensionEffects(false));
        // the shared planet-surface dimension: same star sky, plus dense fog for the near-void look.
        event.register(SurfaceDimension.ID, new SpaceDimensionEffects(true));

        // Compatibility rebinds for a pre-migration world: its space / planet_surface dimensions are baked into
        // level.dat referencing the OLD shuruisutilities:{space,planet_surface} effects id, so without these it would
        // fall back to the plain overworld sky until the world is migrated. Registering the old ids too keeps the
        // custom star sky and fog on both pre- and post-migration worlds. Harmless once migrated (nothing references
        // them). Remove only after every live world has been migrated with world-tools/ns-rename.
        event.register(new net.minecraft.resources.ResourceLocation("shuruisutilities", "space"),
                new SpaceDimensionEffects(false));
        event.register(new net.minecraft.resources.ResourceLocation("shuruisutilities", "planet_surface"),
                new SpaceDimensionEffects(true));
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws java.io.IOException
    {
        // shuruisutilities:lensing, the black hole gravitational-lensing screen pass. Registered here on the mod bus
        // alongside the dimension effects, so both the sky and the pass that warps it are wired in one client-only class.
        SuShaders.register(event);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        // the planet-clash holder needs a renderer registered or the client crashes when it is added to a level;
        // PlanetDefenderRenderer draws nothing, which is the intended invisible look (only the ki wave is seen).
        event.registerEntityRenderer(PlanetDefenderEntities.PLANET_DEFENDER.get(), PlanetDefenderRenderer::new);
        // the wild-planet garrison fighter: an rgnpc-skinned GeckoLib renderer bound to the SU saga subclass, so it
        // shows the approved rgnpc model instead of DragonMineZ's saga model (which its type would otherwise resolve).
        event.registerEntityRenderer(PlanetGarrisonDefenderEntities.GARRISON_DEFENDER.get(),
                PlanetGarrisonDefenderRenderer::new);
        // the DMZ custom-character garrison saiyan: SU's own reimplementation of DragonMineZ's player body/face/hair/
        // tail/armor layer stack over the DMZ race geo, driven by the entity's synced appearance fields. The renderer is
        // now generic over the SaiyanAppearance contract, so the two passive town NPCs below reuse it unchanged.
        event.registerEntityRenderer(PlanetSaiyanGarrisonEntities.GARRISON_SAIYAN.get(),
                context -> new PlanetSaiyanGarrisonRenderer<>(context));
        // the passive town citizen and the saiyan trader: same custom-character render stack, different chassis.
        event.registerEntityRenderer(PlanetSaiyanTownEntities.CITIZEN.get(),
                context -> new PlanetSaiyanGarrisonRenderer<>(context));
        event.registerEntityRenderer(PlanetSaiyanTownEntities.TRADER.get(),
                context -> new PlanetSaiyanGarrisonRenderer<>(context));
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event)
    {
        // the floating planet-info panel, shown while a planet is aimed at in space (toggled by the P keybind). Moved
        // here from the core menu class so the Space module owns its own overlay registration; with Space absent this
        // subscriber never loads and the overlay is simply not registered.
        event.registerAboveAll(PlanetInfoOverlay.OVERLAY_ID, PlanetInfoOverlay::render);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        // install the client-side SpaceClientHook provider so the core keybind handler can fire the planet-select /
        // planet-info actions without naming the Space client classes. Client dist only, so it never loads server-side.
        event.enqueueWork(SpaceClientHookImpl::register);
    }

    // Client-derived travel signal, shared by the tick starter below and by the sound instance's self-stop. Autopilot
    // state lives only in a server-side persistent tag, so the client has no direct "travelling" flag; instead we take
    // travel to mean the local player is seated in a SpacePodEntity, is in the SU space dimension, and the pod is
    // actually moving. WHY a horizontal-only gate: the pod bobs slightly on the Y axis while parked at a body, so a
    // horizontal threshold reads real autopilot cruise without a parked pod tripping it. The threshold (0.10 blocks/tick
    // horizontal) sits well under the pod's ~1.08 stock cruise delta, so the loop starts as soon as travel begins and
    // the volume curve handles the fade-in from there.
    static final double POD_TRAVEL_SPEED_SQR = 0.10D * 0.10D;

    static boolean podTravelActive(LocalPlayer player, SpacePodEntity pod)
    {
        if (player == null || pod == null || pod.isRemoved())
        {
            return false;
        }
        if (player.getVehicle() != pod)
        {
            return false;
        }
        if (!SpaceDimension.isSpace(player.level()))
        {
            return false;
        }
        Vec3 motion = pod.getDeltaMovement();
        double horizontalSqr = motion.x * motion.x + motion.z * motion.z;
        return horizontalSqr >= POD_TRAVEL_SPEED_SQR;
    }

    /**
     * Forge-bus half of this file: starts (and never double-stacks) the pod flight loop. Kept a separate nested
     * subscriber because the outer class is a MOD-bus subscriber and {@link TickEvent.ClientTickEvent} is a FORGE-bus
     * event; this mirrors the hoverbike client wiring idiom in the workspace.
     */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus
    {
        // the loop we last started, so the DMZ-style guard (null || not-active) cannot stack a second copy on re-entry.
        private static PodFlightSoundInstance podSound;

        private ForgeBus()
        {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END)
            {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null)
            {
                podSound = null;
                return;
            }

            SpacePodEntity pod = player.getVehicle() instanceof SpacePodEntity sp ? sp : null;
            if (!podTravelActive(player, pod))
            {
                // not travelling: let any live instance self-stop from its own tick; nothing to start.
                return;
            }

            // same duplicate guard DMZ uses for its flight loop, so re-entering travel cannot layer two loops.
            if (podSound == null || !mc.getSoundManager().isActive(podSound))
            {
                podSound = new PodFlightSoundInstance(player);
                mc.getSoundManager().play(podSound);
            }
        }
    }
}
