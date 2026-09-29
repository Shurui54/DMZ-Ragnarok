package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client FORGE-bus hook that draws the triggered cosmetic-animation RIGS once per frame, after the entity pass so a
 * rig composites over the world like an entity would. Particle-style animations are NOT touched here; they are
 * emitted on the client tick in {@link CosmeticAnimationClientStore}. Everything is delegated to
 * {@link CosmeticAnimationClientStore#renderGeo}, which honours the caps, the client draw options and subject
 * invisibility.
 *
 * <p>The annotation is deliberately BARE of a modid (the five addons are one jar now), matching the sibling
 * {@link CosmeticAnimationClientEvents} and {@code GuildRaidPuppetRenderHook}.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CosmeticAnimGeoRenderHook
{
    private CosmeticAnimGeoRenderHook()
    {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partial = event.getPartialTick();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        try
        {
            CosmeticAnimationClientStore.renderGeo(poseStack, buffer, cam, partial);
        }
        finally
        {
            // Flush the batch so the rigs actually appear this frame (we are outside the normal entity flush).
            buffer.endBatch();
        }
    }
}
