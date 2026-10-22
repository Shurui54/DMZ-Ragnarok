package net.shurui.shuruisutilities.client.podview;

import com.dragonminez.common.init.entities.SpacePodEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the space pod the local player is sitting in LAST, in first person, so its glass no longer hides water, quest
 * markers and the rest of the translucent world (staff report 2026-10).
 *
 * <p>DMZ draws the pod ({@code SpacePodRenderer}, GeckoLib) as {@code entityTranslucent}, in the ordinary entity pass.
 * That pass runs before the translucent terrain (water, ice, glass blocks), particles and the after-particles markers,
 * and {@code entityTranslucent} writes depth, so from inside the pod every glass pixel left a depth value a few
 * centimetres from the camera and everything drawn later behind the glass failed the depth test. Seen from outside it
 * is the ordinary entity-versus-water order and not worth touching.
 *
 * <p>So for the one pod the local player rides, in first person only, {@code MixinDmzSpacePodFirstPerson} skips it in
 * the entity pass and this draws it at {@link RenderLevelStageEvent.Stage#AFTER_WEATHER}, after the translucent
 * terrain, particles and markers, through the same entity render dispatcher and the same renderer: the glass now
 * blends over what is behind it and the hull still covers what it should. Every other pod, and this one in third
 * person, renders exactly as before.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PodFirstPersonRender
{
    private PodFirstPersonRender() {}

    /** True while this class is drawing the deferred pod, so the mixin lets that one call through. */
    private static boolean drawing;

    /** Whether DMZ's own entity-pass render of {@code pod} should be skipped (it is drawn later, by this class). */
    public static boolean deferred(Entity pod)
    {
        if (drawing || pod == null)
            return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() == pod && mc.options.getCameraType().isFirstPerson();
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof SpacePodEntity pod)
                || !mc.options.getCameraType().isFirstPerson())
            return;
        float pt = event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        double x = Mth.lerp(pt, pod.xOld, pod.getX()) - cam.x;
        double y = Mth.lerp(pt, pod.yOld, pod.getY()) - cam.y;
        double z = Mth.lerp(pt, pod.zOld, pod.getZ()) - cam.z;
        float yaw = Mth.lerp(pt, pod.yRotO, pod.getYRot());
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        drawing = true;
        try
        {
            var dispatcher = mc.getEntityRenderDispatcher();
            dispatcher.render(pod, x, y, z, yaw, pt, pose, buffers, dispatcher.getPackedLightCoords(pod, pt));
            buffers.endBatch();
        }
        catch (Throwable ignored)
        {
            // A draw that fails here only loses the pod for this frame; never let it take the frame down.
        }
        finally
        {
            drawing = false;
        }
    }
}
