package net.shurui.shuruisutilities.guilds.raid.clone.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


/**
 * Client FORGE-bus hook that hand-draws the guild-raid clone puppets managed by {@link GuildRaidPuppetManager}.
 * The puppets are never added to the {@link net.minecraft.client.multiplayer.ClientLevel} entity system, so the
 * vanilla entity render pass never touches them; this hook draws each one at its (invisible) driver's
 * camera-relative position by calling the entity render dispatcher, which fires DragonMineZ's own player-renderer
 * mixin and produces the true appearance.
 *
 * <h2>Why the driver is invisible, not deleted</h2>
 * The server-side driver stays a real entity (hitboxes, damage, AI) but is made invisible so it does not render
 * its plain saga body on top of the puppet. When a puppet exists, only the puppet is drawn (here). If a puppet
 * ever fails to build or render, {@link GuildRaidPuppetManager} drops it and the driver's own visibility is
 * restored by the server, so the clone is simply visible as a plain saga NPC and the fight is unaffected.
 *
 * <p>Everything here is wrapped so a render failure can never crash the client; the worst case is a dropped puppet.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class GuildRaidPuppetRenderHook
{
    private GuildRaidPuppetRenderHook()
    {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        // draw once per frame, after the main entity pass so the puppet composites correctly over the world.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES)
        {
            return;
        }
        // guild raids are private: no puppet draws unless the connected server reported the key
        if (GuildRaidPuppetManager.isEmpty() || !net.shurui.dev.sdu.api.ClientGate.key())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partial = event.getPartialTick();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        GuildRaidPuppetManager.syncAndForEach((puppet, driver) ->
                drawPuppet(mc, poseStack, buffer, cam, partial, puppet, driver));

        // flush the batch so the puppet actually appears this frame (we are outside the normal entity flush).
        buffer.endBatch();
    }

    private static void drawPuppet(Minecraft mc, PoseStack poseStack, MultiBufferSource buffer, Vec3 cam,
                                   float partial, GuildRaidPuppet puppet, Entity driver)
    {
        // interpolate the driver's position across the frame instead of snapping to its raw tick position. the raw
        // position only updates at the driver's client position-update cadence (~5 times a second), so rendering at
        // it stepped the puppet's body at that rate, which read as ~5 fps. lerping between the driver's previous and
        // current tick with the frame's partial tick makes the body glide at frame rate.
        double px = Mth.lerp(partial, driver.xOld, driver.getX());
        double py = Mth.lerp(partial, driver.yOld, driver.getY());
        double pz = Mth.lerp(partial, driver.zOld, driver.getZ());
        double dx = px - cam.x;
        double dy = py - cam.y;
        double dz = pz - cam.z;
        // rotLerp, NOT lerp: a plain lerp across the -180/180 wrap would spin the puppet the long way round when the
        // driver turns past due south. rotLerp takes the short arc.
        float yaw = Mth.rotLerp(partial, driver.yRotO, driver.getYRot());
        int packedLight = mc.getEntityRenderDispatcher().getPackedLightCoords(driver, partial);
        // render the puppet at the driver's interpolated, camera-relative position. This calls PlayerRenderer.render,
        // which DragonMineZ's mixin intercepts to draw the DMZ body, so the clone shows the source member's appearance.
        mc.getEntityRenderDispatcher().render(puppet, dx, dy, dz, yaw, partial, poseStack, buffer,
                packedLight);
    }

    /** Drop every puppet when the client level unloads (disconnect / dimension change), so none leak across worlds. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event)
    {
        if (event.getLevel().isClientSide())
        {
            GuildRaidPuppetManager.clear();
        }
    }
}
