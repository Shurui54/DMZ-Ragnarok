package net.shurui.shuruisutilities.hologram.client;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.hologram.network.GifBillboard;

/**
 * Draws the hologram images as flat quads in the world.
 *
 * <p>These are not entities. Nothing is spawned, nothing is tracked and nothing is synced per tick: the server
 * says where the pictures are once, and this draws them from that list until it is told otherwise. It is why a
 * ten frame animation costs the same in packets as a still, which is nothing.
 *
 * <p>The quad is built facing local +Z with +Y up, and then the pose is turned to face the viewer, so the
 * billboard modes are all just a different rotation in front of the same four vertices.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class HologramBillboardRenderer
{
    private HologramBillboardRenderer() {}

    /** The same distance a vanilla display entity is visible from, before the hologram's own multiplier. */
    private static final double BASE_RANGE = 64.0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        // after the translucent terrain pass, so the picture sits behind glass and water rather than through them
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;

        var billboards = HologramBillboards.all();
        // holograms are private: nothing draws unless the connected server reported the key
        if (billboards.isEmpty() || !net.shurui.dev.sdu.api.ClientGate.key())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        String dimension = mc.level.dimension().location().toString();

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long now = System.currentTimeMillis();

        for (GifBillboard billboard : billboards)
        {
            if (!dimension.equals(billboard.dim()))
                continue;

            double dx = billboard.x() - cam.x;
            double dy = billboard.y() - cam.y;
            double dz = billboard.z() - cam.z;
            double range = BASE_RANGE * Math.max(0.01f, billboard.viewRange());
            if (dx * dx + dy * dy + dz * dz > range * range)
                continue;

            ResourceLocation texture = HologramGifCache.frame(billboard.gif(), now);
            if (texture == null)
                continue;   // not decoded yet; it will appear a frame or two from now

            // Glow means EMISSIVE for a picture, not merely a full bright lightmap. The ordinary entity
            // translucent shader also multiplies in diffuse lighting off the vertex normal, so a full bright
            // quad still darkened, and darkened by a different amount depending on which way you walked round
            // it. The emissive type carries no lightmap and no diffuse term at all, so the picture is drawn as
            // authored, which is what "so they are not dark" asks for. It writes colour without depth, the same
            // trade vanilla makes for glowing eyes.
            RenderType type = billboard.glow()
                    ? RenderType.entityTranslucentEmissive(texture)
                    : RenderType.entityTranslucent(texture);
            int light = billboard.glow() ? LightTexture.FULL_BRIGHT
                    : LevelRenderer.getLightColor(mc.level, BlockPos.containing(billboard.x(), billboard.y(), billboard.z()));

            pose.pushPose();
            pose.translate(dx, dy, dz);
            face(pose, billboard, camera);
            quad(pose, buffers.getBuffer(type), billboard.width(), billboard.height(), light);
            pose.popPose();
        }
        buffers.endBatch();
    }

    /**
     * Turn the pose so the picture faces the way this billboard is supposed to.
     *
     * <p>The camera's own orientation is the one that puts a quad square on the screen, which is the same
     * quaternion a nameplate is drawn with. Taking only its yaw leaves the picture upright while it still spins
     * to follow you, and taking none of it leaves the picture facing wherever the hologram was pointed.
     *
     * <h2>Why the camera-facing modes turn a further half circle</h2>
     * The camera quaternion is built to describe where the CAMERA looks, not to present something to it.
     * {@code Camera.setRotation} spells out what its axes mean, and names them itself:
     * <pre>
     *   rotation.rotationYXZ(-yRot, xRot, 0);
     *   forwards.set(0, 0, 1).rotate(rotation);   // local +Z is the way the camera LOOKS
     *   left    .set(1, 0, 0).rotate(rotation);   // local +X is the camera's LEFT
     * </pre>
     * So adopting it unturned points our +Z away from the viewer and puts our +X on their left: we were showing
     * the BACK of the picture, and the back of a picture reads right to left. That is the mirrored text.
     *
     * <p>A half turn about Y fixes both halves of that at once, because it maps +Z to the viewer and +X to their
     * right. Vanilla solves the same problem for nameplates with {@code scale(-1, -1, 1)}, which is that same
     * half turn written as a pair of sign flips (its determinant is +1, so it is a rotation, not a mirror).
     *
     * <p>The yaw-based modes are NOT touched: their {@code Axis.YP.rotationDegrees(-yaw)} already leaves +X on
     * the right of somebody standing in front, so they were never mirrored and must not be "corrected".
     */
    private static void face(PoseStack pose, GifBillboard billboard, Camera camera)
    {
        String mode = billboard.billboard() == null ? "center" : billboard.billboard();
        switch (mode)
        {
        case "fixed" ->
        {
            pose.mulPose(Axis.YP.rotationDegrees(-billboard.yaw()));
            pose.mulPose(Axis.XP.rotationDegrees(billboard.pitch()));
        }
        case "vertical" -> pose.mulPose(Axis.YP.rotationDegrees(180.0f - camera.getYRot()));
        case "horizontal" ->
        {
            pose.mulPose(Axis.YP.rotationDegrees(-billboard.yaw()));
            pose.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        }
        default ->
        {
            pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
            pose.mulPose(Axis.YP.rotationDegrees(180.0f));
        }
        }
    }

    /** Four vertices around the origin, the picture the right way up, drawn from both sides. */
    private static void quad(PoseStack pose, VertexConsumer out, float width, float height, int light)
    {
        float w = width / 2.0f;
        float h = height / 2.0f;
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();

        vertex(out, matrix, normal, -w, -h, 0.0f, 1.0f, light);
        vertex(out, matrix, normal, w, -h, 1.0f, 1.0f, light);
        vertex(out, matrix, normal, w, h, 1.0f, 0.0f, light);
        vertex(out, matrix, normal, -w, h, 0.0f, 0.0f, light);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Matrix3f normal, float x, float y, float u,
            float v, int light)
    {
        out.vertex(matrix, x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, 0.0f, 0.0f, 1.0f)
                .endVertex();
    }

    /** Leaving a server has to drop both the placements and the textures, or the next world inherits them. */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        HologramBillboards.clear();
        HologramGifCache.clear();
    }
}
