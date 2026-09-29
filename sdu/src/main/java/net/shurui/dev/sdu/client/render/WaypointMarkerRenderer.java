package net.shurui.dev.sdu.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.client.ClientWaypoints;
import net.shurui.dev.sdu.waypoint.Waypoint;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws quest pins in the world: a beacon beam in the marker's colour at the target, with the pin floating over
 * it facing the camera.
 *
 * <p>PER PLAYER: everything reads {@link ClientWaypoints}, the set synced to THIS client, so a player sees only
 * their own tracked quest's beam. Nothing is placed in the world and no entity exists.
 *
 * <p>Only markers with a pin AND {@link Waypoint#beacon} are drawn (an authored COORDS quest objective and the
 * airdrop), so a target that can move never gets a beam that appears to chase it.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WaypointMarkerRenderer {

    private WaypointMarkerRenderer() {
    }

    /** Beam draw range in blocks. Generous: a beam is one thin column and meant to be seen across the map. */
    private static final double BEAM_RANGE = 1000.0;

    /** Beam height in blocks, tall enough to clear terrain from a distance. */
    private static final int BEAM_HEIGHT = 256;

    /** Beam thickness, matching a vanilla beacon's proportions. */
    private static final float BEAM_RADIUS = 0.2f;
    private static final float BEAM_GLOW_RADIUS = 0.25f;

    /** Blocks above the target the pin floats at, before distance scaling. */
    private static final float PIN_HOVER = 2.4f;

    /** Pin height in blocks at arm's length. */
    private static final float PIN_SIZE = 1.2f;

    /** Distance past which the pin scales up to keep a constant apparent size; inside it draws at true size. */
    private static final double PIN_SCALE_START = 24.0;

    /**
     * Clamp on the distance scaling. Holds constant apparent size out to {@code PIN_SCALE_START * PIN_SCALE_MAX}
     * (~4800 blocks); past that it shrinks with distance rather than growing forever. An uncapped scale would
     * push the billboard half-extent and hover offset into the hundreds of blocks and lose float precision in
     * the pose matrix.
     */
    private static final double PIN_SCALE_MAX = 200.0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // After translucent terrain, so a beam behind glass or water draws over it (same stage as holograms).
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        // Beacon beams and floating pins are private (OWNER-SPECS section 4): nothing is drawn unless the connected
        // server reported the key. The quest tracker keeps the objective's location public.
        if (!net.shurui.dev.sdu.api.ClientGate.key()) {
            return;
        }
        var waypoints = ClientWaypoints.all();
        if (waypoints.isEmpty()) {
            return;
        }
        String dim = mc.level.dimension().location().toString();

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long gameTime = mc.level.getGameTime();
        float partialTick = event.getPartialTick();

        for (Waypoint w : waypoints) {
            // a travel marker has no position (it only names a dim), and a marker from another dim has none here.
            // beacon gates to the in-world markers (authored COORDS quest, airdrop), so a movable target gets no beam.
            if (w.isNotice() || w.travel || !w.beacon || !w.mark.hasPin() || !dim.equals(w.dim)) {
                continue;
            }
            double dx = w.x - cam.x;
            double dy = w.y - cam.y;
            double dz = w.z - cam.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            // BEAM only within range. The pin is drawn unconditionally below, so it stays visible from anywhere.
            if (distSq <= BEAM_RANGE * BEAM_RANGE) {
                float[] color = w.mark.beamColor();
                pose.pushPose();
                // snap to the block grid: the beam draws from a block corner outward like a beacon, or it stands
                // off-centre in its column.
                pose.translate(Math.floor(w.x) - cam.x, Math.floor(w.y) - cam.y, Math.floor(w.z) - cam.z);
                BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1.0f,
                        gameTime, 0, BEAM_HEIGHT, color, BEAM_RADIUS, BEAM_GLOW_RADIUS);
                pose.popPose();
            }

            // PIN unconditionally, no range limit.
            pose.pushPose();
            pose.translate(dx, dy, dz);
            drawPin(pose, buffers, w, Math.sqrt(distSq), camera);
            pose.popPose();
        }
        buffers.endBatch();
    }

    /** The pin itself, square to the camera and floating over the target. */
    private static void drawPin(PoseStack pose, MultiBufferSource buffers, Waypoint w, double distance,
                                Camera camera) {
        // grow past PIN_SCALE_START for constant apparent size, clamped both ends: never below true size, never
        // above PIN_SCALE_MAX (an uncapped scale loses float precision in the pose matrix).
        float scale = (float) Math.max(1.0, Math.min(PIN_SCALE_MAX, distance / PIN_SCALE_START));
        pose.translate(0.0, PIN_HOVER * scale, 0.0);
        // camera rotation squares the quad to the screen (same as a nameplate); the 180 is NOT optional, without
        // it the quad faces away and the texture comes out mirrored.
        pose.mulPose(camera.rotation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0f));
        float half = PIN_SIZE * scale / 2.0f;

        // EMISSIVE on purpose: a marker that dims in a cave fails when the player most needs it. No lightmap or
        // diffuse term, so it draws at its painted colours whatever the light.
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucentEmissive(w.mark.texture));
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        vertex(out, matrix, normal, -half, -half, 0.0f, 1.0f);
        vertex(out, matrix, normal, half, -half, 1.0f, 1.0f);
        vertex(out, matrix, normal, half, half, 1.0f, 0.0f);
        vertex(out, matrix, normal, -half, half, 0.0f, 0.0f);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Matrix3f normal, float x, float y,
                               float u, float v) {
        out.vertex(matrix, x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normal, 0.0f, 0.0f, 1.0f)
                .endVertex();
    }
}
