package net.shurui.dev.sdu.client.hud;

import com.dragonminez.client.render.camera.OverShoulderCamera;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Draws sdu's crescent crosshair for DMZ's over-the-shoulder third person camera, and optionally for first
 * person (replacing vanilla's white cross).
 *
 * <p>Vanilla {@code Gui.renderCrosshair} is gated on {@code getCameraType().isFirstPerson()}, so the
 * over-the-shoulder camera leaves the player with no reticle. This restores one, and when
 * {@code firstPersonCrosshair} is on, cancels the vanilla one and draws ours centred instead.
 *
 * <p>{@link OverShoulderCamera} offsets the camera position but aims down the player's own look vector. That
 * aim is a RAY, and the sideways camera offset means it projects to a line, so no single screen point is
 * right at every distance. We project {@link Minecraft#hitResult}'s location (the block face or entity under
 * the crosshair) and ease the drawn position toward it each frame.
 *
 * <p>Past interaction reach the hit result is a MISS: {@code hitResult} is only computed to ~4.5 blocks. The
 * correction needed is {@code atan(1.45 / distance)}, ~11 degrees at 4.5 blocks but under 1 at a hundred, so
 * anchoring at 4.5 flung the reticle ~140 px wide of a distant target, on the far side from the camera. See
 * {@link #longRangeAim}, which casts the ray properly.
 *
 * <p>We capture the projection during {@link RenderLevelStageEvent}: GUI overlays run after
 * {@code RenderSystem}'s projection is already the 2D ortho matrix. Do NOT multiply the event's projection
 * by {@code event.getPoseStack().last().pose()}: at {@code AFTER_LEVEL} the event gets its local pose stack
 * (projection plus view-bob) as BOTH arguments, never the pose stack carrying camera pitch/yaw/roll, so that
 * product is {@code projection * projection} with zero camera rotation and the reticle drifts. Rebuild the
 * view rotation from the live {@link Camera} instead ({@code Rx(getXRot()) * Ry(getYRot() + 180)}, exactly
 * how {@code GameRenderer} builds it).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class OverShoulderCrosshairOverlay {

    public static final IGuiOverlay OVER_SHOULDER_CROSSHAIR = OverShoulderCrosshairOverlay::render;

    /** On-screen reticle size in GUI pixels; texture is 32x32, drawn smaller. */
    private static final int DRAW_SIZE = 15;
    private static final int TEX_SIZE = 32;
    private static final ResourceLocation CROSSHAIR = new ResourceLocation("dmz_ragnarok", "textures/gui/crosshair.png");
    /** Reach fallback if the Forge reach attribute is not yet on the player. Matches survival BLOCK_REACH base. */
    private static final double FALLBACK_REACH = 4.5;
    /** How far the aim ray is followed past reach. Beyond this the correction is a couple flat pixels, not worth it. */
    private static final double MAX_AIM = 128.0;
    /**
     * Smoothing time constant in SECONDS. Blend is {@code alpha = 1 - exp(-dt / SMOOTH_TAU)} with dt the real
     * frame time, so the glide is framerate independent. 0.06 s is ~3-4 frames at 60 fps: subtle enough to sit
     * on target once settled, enough to glide a hit-point jump rather than snap.
     */
    private static final double SMOOTH_TAU = 0.06;
    /**
     * Jumps larger than this many GUI pixels SNAP instead of easing, so a target flip from near wall to far
     * horizon (or a camera whip) does not slide slowly across screen and read as lag. 48 px is a few reticle widths.
     */
    private static final float SMOOTH_SNAP_DISTANCE = 48.0f;

    /**
     * Perspective projection from the last world render (FOV and view-bob baked in). View rotation is NOT folded
     * in; it is rebuilt at draw time from the captured cam angles. Null until one world frame has rendered.
     */
    private static volatile Matrix4f capturedProjection;
    private static volatile double capturedCamX;
    private static volatile double capturedCamY;
    private static volatile double capturedCamZ;
    private static volatile float capturedCamXRot;
    private static volatile float capturedCamYRot;
    /** The partial tick the frame was captured at, reused for the aim ray so eye and camera share one frame. */
    private static volatile float capturedPartialTick;

    /** One-shot diagnostic: which branch the third-person draw took, so an invisible failure is nameable. */
    private static boolean loggedOnce;

    /**
     * Last smoothed reticle centre (what was drawn last frame), eased toward the fresh target. smoothValid is
     * false until the first frame seeds it, so the reticle starts on target. Reset whenever the overlay stops
     * drawing so a re-show snaps to the new aim.
     */
    private static float smoothedX;
    private static float smoothedY;
    private static boolean smoothValid;
    private static long lastFrameNanos;

    private OverShoulderCrosshairOverlay() {
    }

    /** Captures the projection, camera position, and camera rotation each level frame (see class doc). */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        // Fresh matrix so we never alias the game's live instance.
        capturedProjection = new Matrix4f(event.getProjectionMatrix());
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        capturedCamX = camPos.x;
        capturedCamY = camPos.y;
        capturedCamZ = camPos.z;
        capturedCamXRot = camera.getXRot();
        capturedCamYRot = camera.getYRot();
        capturedPartialTick = event.getPartialTick();
    }

    /**
     * First person: cancel vanilla's crosshair and draw ours at screen centre (aim projects there, no offset
     * needed). Forge only fires this once vanilla's own gating (hidden HUD, open screen, spectator) has passed.
     */
    @SubscribeEvent
    public static void onPreCrosshair(RenderGuiOverlayEvent.Pre event) {
        if (!net.shurui.dev.sdu.client.ClientConfig.firstPersonCrosshair) {
            return;
        }
        if (!VanillaGuiOverlay.CROSSHAIR.id().equals(event.getOverlay().id())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        event.setCanceled(true);
        int screenW = event.getWindow().getGuiScaledWidth();
        int screenH = event.getWindow().getGuiScaledHeight();
        float drawX = (screenW - DRAW_SIZE) / 2.0f;
        float drawY = (screenH - DRAW_SIZE) / 2.0f;
        drawSprite(event.getGuiGraphics(), drawX, drawY);
    }

    private static void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics g,
                               float partialTick, int screenW, int screenH) {
        if (!net.shurui.dev.sdu.client.ClientConfig.overShoulderCrosshair) {
            smoothValid = false;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            smoothValid = false;
            return;
        }
        // Mirror vanilla Gui.renderCrosshair's conditions: no F1, no open screen, not spectator, not first person.
        if (mc.options.hideGui || mc.screen != null) {
            smoothValid = false;
            return;
        }
        if (mc.options.getCameraType().isFirstPerson()) {
            smoothValid = false;
            return;
        }
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            smoothValid = false;
            return;
        }
        if (!OverShoulderCamera.isRunning()) {
            smoothValid = false;
            return;
        }

        // Target reticle CENTRE: the projected aim point, or screen centre if projection fails (never nothing).
        float targetCx = screenW / 2.0f;
        float targetCy = screenH / 2.0f;
        String branch = "centre-fallback";

        float[] projected = computeAimScreenPos(mc, screenW, screenH);
        if (projected != null) {
            targetCx = projected[0];
            targetCy = projected[1];
            branch = "projected";
        }

        // Ease toward the target, then clamp so the sprite cannot leave the screen.
        float[] centre = smoothCentre(targetCx, targetCy);
        float drawX = clamp(centre[0] - DRAW_SIZE / 2.0f, 0.0f, screenW - DRAW_SIZE);
        float drawY = clamp(centre[1] - DRAW_SIZE / 2.0f, 0.0f, screenH - DRAW_SIZE);

        if (!loggedOnce) {
            loggedOnce = true;
            DmzNpc.LOGGER.info("[sdu] over-shoulder crosshair drawing via {} at ({}, {}); captured projection: {}",
                    branch, Math.round(drawX), Math.round(drawY), capturedProjection != null);
        }

        drawSprite(g, drawX, drawY);
    }

    /** Ease the drawn centre toward the target; seed on target when first shown, snap on a large jump. */
    private static float[] smoothCentre(float targetCx, float targetCy) {
        long now = System.nanoTime();
        if (!smoothValid) {
            smoothedX = targetCx;
            smoothedY = targetCy;
            smoothValid = true;
            lastFrameNanos = now;
            return new float[] {smoothedX, smoothedY};
        }
        float dx = targetCx - smoothedX;
        float dy = targetCy - smoothedY;
        if (dx * dx + dy * dy > SMOOTH_SNAP_DISTANCE * SMOOTH_SNAP_DISTANCE) {
            smoothedX = targetCx;
            smoothedY = targetCy;
            lastFrameNanos = now;
            return new float[] {smoothedX, smoothedY};
        }
        double dtSeconds = (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        // Guard a non-positive or absurd delta (paused, first tick after a hitch) so alpha stays in [0, 1].
        if (dtSeconds <= 0.0) {
            return new float[] {smoothedX, smoothedY};
        }
        if (dtSeconds > 0.25) {
            dtSeconds = 0.25;
        }
        float alpha = (float) (1.0 - Math.exp(-dtSeconds / SMOOTH_TAU));
        smoothedX += dx * alpha;
        smoothedY += dy * alpha;
        return new float[] {smoothedX, smoothedY};
    }

    /** Draws the crescent with normal alpha blending, restoring the default blend func for later HUD elements. */
    private static void drawSprite(GuiGraphics g, float drawX, float drawY) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(CROSSHAIR, Math.round(drawX), Math.round(drawY), DRAW_SIZE, DRAW_SIZE,
                0.0f, 0.0f, TEX_SIZE, TEX_SIZE, TEX_SIZE, TEX_SIZE);
        // Leave blend enabled (the state the vanilla HUD runs in), just restore the func.
        RenderSystem.defaultBlendFunc();
    }

    /**
     * Projects the aim point to screen pixels: {@link Minecraft#hitResult}'s location when looking at something,
     * else the long-range ray ({@link #longRangeAim}).
     *
     * @return {screenX, screenY} in GUI pixels, or null if projection failed (caller degrades to screen centre).
     */
    private static float[] computeAimScreenPos(Minecraft mc, int screenW, int screenH) {
        try {
            LocalPlayer player = mc.player;
            if (player == null) {
                return null;
            }
            Matrix4f projection = capturedProjection;
            if (projection == null) {
                return null;
            }

            // Same partial tick the frame was captured at, so eye and camera share one frame on the miss fallback.
            float partialTick = capturedPartialTick;

            // Prefer the actual hit point; past interaction range fall to the longer ray (see longRangeAim).
            Vec3 aim;
            net.minecraft.world.phys.HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                aim = hit.getLocation();
            } else {
                aim = longRangeAim(mc, player, partialTick);
            }

            return projectPoint(projection, aim, screenW, screenH);
        } catch (Throwable t) {
            // Never throw inside a HUD render.
            return null;
        }
    }

    /**
     * The aim point when the target is beyond interaction reach (nearly always in a fight). {@code hitResult} is
     * only computed to ~4.5 blocks, so anchoring the miss fallback there mis-corrects at range: the offset needs
     * {@code atan(1.45 / distance)}, ~11 degrees at 4.5 but under 1 at a hundred, so it flung the reticle ~140 px
     * to one side. Cast the ray to {@link #MAX_AIM} and mark the first block or entity along it; with nothing out
     * there the far point projects near screen centre, which is right for open sky.
     *
     * <p>Cost is one block clip plus one entity sweep, only on frames where nothing is within reach (when
     * hitResult did no work either). The sweep is bounded by the block hit, so it spans air only as far as ground.
     */
    private static Vec3 longRangeAim(Minecraft mc, LocalPlayer player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        double distance = MAX_AIM;
        if (mc.level != null) {
            net.minecraft.world.phys.BlockHitResult block = mc.level.clip(new net.minecraft.world.level.ClipContext(
                    eye, eye.add(look.scale(MAX_AIM)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (block != null && block.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                distance = Math.min(distance, block.getLocation().distanceTo(eye));
            }
            // An entity in front of the surface wins. Searching only to the surface keeps the swept box small.
            Vec3 end = eye.add(look.scale(distance));
            net.minecraft.world.phys.EntityHitResult entity =
                    net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                            player, eye, end,
                            player.getBoundingBox().expandTowards(look.scale(distance)).inflate(1.0),
                            e -> !e.isSpectator() && e.isPickable() && e != player, distance * distance);
            if (entity != null) {
                distance = Math.min(distance, entity.getLocation().distanceTo(eye));
            }
        }
        return eye.add(look.scale(distance));
    }

    /** Interaction reach in blocks from Forge's BLOCK_REACH (base 4.5 survival, higher creative), or FALLBACK_REACH. */
    private static double interactionReach(LocalPlayer player) {
        Attribute reachAttr = ForgeMod.BLOCK_REACH.get();
        if (player.getAttributes().hasAttribute(reachAttr)) {
            return player.getAttributeValue(reachAttr);
        }
        return FALLBACK_REACH;
    }

    /**
     * Projects a world point to screen pixels: subtract the camera position (world is camera-relative), rebuild
     * view rotation from the captured angles as GameRenderer does ({@code Rx(camXRot) * Ry(camYRot + 180)}),
     * then apply the captured projection.
     */
    private static float[] projectPoint(Matrix4f projection, Vec3 world, int screenW, int screenH) {
        float rx = (float) (world.x - capturedCamX);
        float ry = (float) (world.y - capturedCamY);
        float rz = (float) (world.z - capturedCamZ);

        // Matches GameRenderer.renderLevel lines 1121-1122: rotate X by pitch, then Y by (yaw + 180).
        Matrix4f view = new Matrix4f()
                .rotationX((float) Math.toRadians(capturedCamXRot))
                .rotateY((float) Math.toRadians(capturedCamYRot + 180.0f));

        Vector4f p = new Vector4f(rx, ry, rz, 1.0f);
        view.transform(p);
        projection.transform(p);

        if (p.w() <= 1.0e-4f) {
            // Behind or on the camera plane.
            return null;
        }
        float ndcX = p.x() / p.w();
        float ndcY = p.y() / p.w();
        if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
            return null;
        }
        // NDC to screen pixels; Y flipped (NDC +Y up, GUI +Y down).
        float sx = (ndcX * 0.5f + 0.5f) * screenW;
        float sy = (1.0f - (ndcY * 0.5f + 0.5f)) * screenH;
        return new float[] {sx, sy};
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
