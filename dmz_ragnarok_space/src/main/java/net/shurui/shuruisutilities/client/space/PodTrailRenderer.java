package net.shurui.shuruisutilities.client.space;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.util.UUID;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.init.entities.SpacePodEntity;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Character;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.cosmetics.form.client.FormCosmeticClientStore;
import net.shurui.shuruisutilities.space.SpaceDimension;

/**
 * Our own engine wake for space pods: a camera-facing tapered ribbon that fades with age plus a rear exhaust glow, drawn
 * behind every {@link SpacePodEntity} flying in SU's space dimension. Both the Space Pod Chip pod and DragonMineZ's
 * saiyan ship spawn the SAME {@code SpacePodEntity}, so one target class covers every pod.
 *
 * <p>Purely CLIENT SIDE: no packet. Positions are sampled on the client tick from each pod's real travelled position (a
 * tracked entity within render distance already moves smoothly on the client), joined nose to tail on the render pass,
 * and each sample fades with age so the wake has a bright head at the pod and thins to nothing at the tail. This mirrors
 * the {@code DashTrailRenderer} idiom, so it inherits the same discipline about a sample being a lie: the streak is CUT
 * (its history dropped) whenever a new sample is not continuous with the one before it (a different dimension, a reused
 * entity id, or a step longer than one hard tick of travel, which is a landing teleport, not flight), and the draw
 * refuses any single over long segment as a last line of defence.
 *
 * <p>Two ribbon layers on one untextured additive type ({@link #podTrail()}: additive blend, depth tested, no depth
 * write, no cull): a wide soft cyan glow under a thin warm orange-white core, both tapering to zero half width at the
 * tail. The exhaust is a stack of camera-facing additive glow discs at the pod's nozzle, sized by speed. No texture is
 * used (the discs are procedural per-vertex-alpha fans), and nothing is allocated per point beyond the sample arrays.
 * Drawn at {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES} so it blends over the world and reuses the level buffer
 * source, which keeps Embeddium / Sodium happy exactly as {@link SpaceBodyRenderer} does.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PodTrailRenderer
{
    private PodTrailRenderer()
    {
    }

    // How many positions are kept per pod. At twenty samples a second this is about two and a bit seconds of wake, long
    // enough to read as a streak behind a cruising pod, short enough to cost nothing to build.
    private static final int MAX_SAMPLES = 48;

    // Oldest a sample is allowed to be before it is dropped whatever the pod is doing, so a pod that stops dead still
    // trails its wake off in about this long rather than leaving a frozen tail in the void.
    private static final long MAX_POINT_AGE_MILLIS = 2400L;

    // The longest step, in blocks, that can be one genuine tick of travel. A pod cruises near 1.1 blocks a tick and a
    // boosted pod is faster, so this leaves generous headroom over real flight while still cutting a landing teleport or
    // a shard hop (tens to thousands of blocks), which are not travel and must break the streak.
    private static final double MAX_SEGMENT = 16.0D;
    private static final double MAX_SEGMENT_SQR = MAX_SEGMENT * MAX_SEGMENT;

    // Below this speed (blocks/tick, total) a pod is parked or merely bobbing at a body, so no new sample is taken and
    // its wake ages out. Comfortably above the parked Y bob, well under the ~1.1 cruise delta, so the wake starts the
    // instant travel begins.
    private static final double MIN_TRAIL_SPEED = 0.20D;
    private static final double MIN_TRAIL_SPEED_SQR = MIN_TRAIL_SPEED * MIN_TRAIL_SPEED;

    // Only pods within this radius of the camera are considered. Pods farther out are past entity tracking range and are
    // not present on the client anyway, so client side sampling naturally bounds the pod count with no explicit cap.
    private static final double SAMPLE_RADIUS = 384.0D;

    // Wake geometry. Half widths at the head (blocks), scaled by speed; the tail tapers to zero. The glow is the wide
    // soft layer, the core the thin bright one drawn over it.
    private static final float GLOW_HALF_WIDTH = 0.95F;
    private static final float CORE_HALF_WIDTH = 0.30F;
    private static final float GLOW_ALPHA = 0.26F;
    private static final float CORE_ALPHA = 0.60F;

    // Fallback engine palette: a warm orange-white core inside a cyan glow, used only when the pilot's aura colour cannot
    // be resolved (no DMZ stats, an empty pod, or DMZ changing shape). When a pilot IS found the wake is tinted with that
    // pilot's DMZ aura colour instead (see resolveAuraColor), so the trail reads as the player's own aura, form cosmetic
    // overrides included, matching what is drawn as their aura and as the pod exhaust plume.
    private static final float[] CORE_RGB = {1.00F, 0.86F, 0.58F};
    private static final float[] GLOW_RGB = {0.35F, 0.72F, 1.00F};

    // How far the tinted hot core is pulled toward white, so the core still reads as a bright flame while carrying the
    // aura hue. 0 keeps the pure aura colour, 1 is white. The glow layer uses the aura colour undiluted.
    private static final float CORE_WHITEN = 0.5F;

    // How far back along travel the head of the wake sits, so it leaves from the pod's tail rather than its nose.
    private static final double TRAIL_SETBACK = 0.55D;

    // Exhaust glow: base disc radius (blocks) at full speed, and how many stacked discs (largest dim glow to smallest
    // white-hot core) make the flame read as soft rather than a flat polygon.
    private static final float EXHAUST_RADIUS = 0.85F;
    private static final int EXHAUST_DISC_SEGMENTS = 14;

    private static final Map<Integer, Trail> TRAILS = new HashMap<>();

    // One sample: the world position and the moment it was taken, plus the pod speed then so the wake can widen with how
    // fast the pod was going at that point.
    private record Sample(Vec3 pos, long time, double speed)
    {
    }

    // The history for one pod, plus the dimension it was sampled in so a dimension change breaks the streak, plus the two
    // wake colours (hot core, soft glow) resolved from the pilot's aura and refreshed each sample. They default to the
    // fallback palette so a pod with no resolvable pilot still trails the shared engine colours rather than nothing.
    private static final class Trail
    {
        final Deque<Sample> samples = new ArrayDeque<>();
        net.minecraft.resources.ResourceKey<Level> dimension;
        float[] coreRgb = CORE_RGB;
        float[] glowRgb = GLOW_RGB;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        // Turned off in the config, or not in space: drop everything held so switching it off (or leaving the dimension)
        // clears wakes already in the air rather than freezing them there.
        if (!SUConfig.podTrailsEnabled)
        {
            TRAILS.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !SpaceDimension.isSpace(mc.level))
        {
            TRAILS.clear();
            return;
        }

        long now = System.currentTimeMillis();
        AABB near = mc.player.getBoundingBox().inflate(SAMPLE_RADIUS);
        List<SpacePodEntity> pods = mc.level.getEntitiesOfClass(SpacePodEntity.class, near);
        for (SpacePodEntity pod : pods)
        {
            if (pod.isRemoved())
            {
                continue;
            }
            Vec3 motion = pod.getDeltaMovement();
            double speedSqr = motion.lengthSqr();
            if (speedSqr < MIN_TRAIL_SPEED_SQR)
            {
                // parked or bobbing: take no new sample, let the existing wake age out on its own.
                continue;
            }
            Trail trail = TRAILS.computeIfAbsent(pod.getId(), k -> new Trail());
            Vec3 pos = pod.position();
            net.minecraft.resources.ResourceKey<Level> dim = pod.level().dimension();
            Sample last = trail.samples.peekLast();
            boolean discontinuous = !dim.equals(trail.dimension)
                    || (last != null && last.pos().distanceToSqr(pos) > MAX_SEGMENT_SQR);
            if (discontinuous)
            {
                trail.samples.clear();
            }
            trail.dimension = dim;
            trail.samples.addLast(new Sample(pos, now, Math.sqrt(speedSqr)));
            prune(trail.samples, now);

            // Tint the wake with the pilot's aura colour, refreshed each sample so a mid-flight transformation recolours
            // the trail. Falls back to the shared palette when there is no resolvable pilot colour.
            float[] aura = resolveAuraColor(pilotOf(pod));
            if (aura != null)
            {
                trail.glowRgb = aura;
                trail.coreRgb = whiten(aura, CORE_WHITEN);
            }
            else
            {
                trail.glowRgb = GLOW_RGB;
                trail.coreRgb = CORE_RGB;
            }
        }

        // Retire trails whose pod is gone, left our radius, or is aging out its tail while stopped.
        for (Iterator<Map.Entry<Integer, Trail>> it = TRAILS.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<Integer, Trail> e = it.next();
            Trail trail = e.getValue();
            net.minecraft.world.entity.Entity current = mc.level.getEntity(e.getKey());
            if (!(current instanceof SpacePodEntity) || current.isRemoved())
            {
                it.remove();
                continue;
            }
            prune(trail.samples, now);
            if (trail.samples.isEmpty())
            {
                it.remove();
            }
        }
    }

    // Drop the oldest samples until the deque is within its length budget and holds nothing past its age limit.
    private static void prune(Deque<Sample> samples, long now)
    {
        while (samples.size() > MAX_SAMPLES)
        {
            samples.removeFirst();
        }
        Sample head;
        while ((head = samples.peekFirst()) != null && now - head.time() > MAX_POINT_AGE_MILLIS)
        {
            samples.removeFirst();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
        {
            return;
        }
        // Re-checked here as well as in the tick: the render pass runs between ticks, so a mid-frame toggle or dimension
        // change must not paint one last set of wakes.
        if (!SUConfig.podTrailsEnabled || TRAILS.isEmpty())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !SpaceDimension.isSpace(mc.level))
        {
            return;
        }
        try
        {
            Vec3 cam = event.getCamera().getPosition();
            float partialTick = event.getPartialTick();
            PoseStack pose = event.getPoseStack();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            VertexConsumer buffer = buffers.getBuffer(podTrail());

            pose.pushPose();
            try
            {
                // Everything is drawn camera relative, so the whole level is shifted once here rather than per vertex.
                pose.translate(-cam.x, -cam.y, -cam.z);
                for (Map.Entry<Integer, Trail> entry : TRAILS.entrySet())
                {
                    drawTrail(pose, buffer, mc, cam, entry.getKey(), entry.getValue(), partialTick);
                }
            }
            finally
            {
                pose.popPose();
            }
            buffers.endBatch(podTrail());
        }
        catch (Throwable t)
        {
            // A wake is decoration. It must never take the level render down with it.
        }
    }

    private static void drawTrail(PoseStack pose, VertexConsumer buffer, Minecraft mc, Vec3 cam, int podId, Trail trail,
                                  float partialTick)
    {
        if (trail.samples.size() < 2)
        {
            return;
        }
        Sample[] history = trail.samples.toArray(new Sample[0]);
        int count = history.length;
        Vec3[] pts = new Vec3[count];
        double[] speeds = new double[count];
        for (int i = 0; i < count; i++)
        {
            pts[i] = history[i].pos();
            speeds[i] = history[i].speed();
        }

        // Re-seat the head of the wake on where the pod is actually DRAWN this frame, interpolated the same way the model
        // is, so the wake stays welded to the pod at every frame rate instead of lagging a sample behind. Set back along
        // travel so it leaves from the tail, not the nose.
        net.minecraft.world.entity.Entity entity = mc.level == null ? null : mc.level.getEntity(podId);
        Vec3 nozzle = pts[count - 1];
        Vec3 travelDir = pts[count - 1].subtract(pts[count - 2]);
        if (entity instanceof SpacePodEntity)
        {
            Vec3 head = entity.getPosition(partialTick);
            Vec3 along = head.subtract(pts[count - 2]);
            if (along.lengthSqr() > 1.0E-8D)
            {
                travelDir = along.normalize();
                head = head.subtract(travelDir.scale(TRAIL_SETBACK));
            }
            nozzle = head;
            pts[count - 1] = head;
        }

        float headSpeedScale = speedScale(speeds[count - 1]);

        // The pilot's aura colours (or the fallback palette), captured on the last sample.
        float[] coreRgb = trail.coreRgb;
        float[] glowRgb = trail.glowRgb;

        // Two ribbon layers: the wide soft glow first, then the thin bright core over it. Both additive on the same type.
        drawRibbon(pose, buffer, pts, speeds, cam, GLOW_HALF_WIDTH, GLOW_ALPHA, glowRgb);
        drawRibbon(pose, buffer, pts, speeds, cam, CORE_HALF_WIDTH, CORE_ALPHA, coreRgb);

        // Exhaust glow at the nozzle, sized by the pod's current speed. Skipped if the pod is barely moving so a drifting
        // pod does not wear a flame.
        if (headSpeedScale > 0.05F && travelDir.lengthSqr() > 1.0E-8D)
        {
            drawExhaust(pose, buffer, cam, nozzle, headSpeedScale, coreRgb, glowRgb);
        }
    }

    // one ribbon layer: a chain of camera-facing quads, half width tapering from full at the head (newest) to zero at the
    // tail (oldest), alpha fading the same way. side = travel x toCamera, so the ribbon always turns its flat face to the
    // viewer and never collapses to an edge.
    private static void drawRibbon(PoseStack pose, VertexConsumer buffer, Vec3[] pts, double[] speeds, Vec3 cam,
                                   float headHalfWidth, float maxAlpha, float[] rgb)
    {
        int count = pts.length;
        var m = pose.last().pose();
        for (int i = 1; i < count; i++)
        {
            Vec3 from = pts[i - 1];
            Vec3 to = pts[i];
            Vec3 along = to.subtract(from);
            double segSqr = along.lengthSqr();
            if (segSqr < 1.0E-8D || segSqr > MAX_SEGMENT_SQR)
            {
                // degenerate step, or a jump that slipped past the sampler: never draw it.
                continue;
            }

            float ageFrom = (float) (i - 1) / (float) (count - 1);
            float ageTo = (float) i / (float) (count - 1);

            Vec3 sideFrom = sideVector(tangent(pts, i - 1), from, cam);
            Vec3 sideTo = sideVector(tangent(pts, i), to, cam);
            if (sideFrom == null || sideTo == null)
            {
                continue;
            }

            float wFrom = headHalfWidth * ageFrom * speedScale(speeds[i - 1]);
            float wTo = headHalfWidth * ageTo * speedScale(speeds[i]);
            float aFrom = maxAlpha * ageFrom * ageFrom;
            float aTo = maxAlpha * ageTo * ageTo;

            Vec3 f0 = from.add(sideFrom.scale(wFrom));
            Vec3 f1 = from.subtract(sideFrom.scale(wFrom));
            Vec3 t1 = to.subtract(sideTo.scale(wTo));
            Vec3 t0 = to.add(sideTo.scale(wTo));

            vertex(buffer, m, f0, rgb, aFrom);
            vertex(buffer, m, f1, rgb, aFrom);
            vertex(buffer, m, t1, rgb, aTo);
            vertex(buffer, m, t0, rgb, aTo);
        }
    }

    // averaged tangent at point i from its neighbours, normalised, or null if degenerate.
    private static Vec3 tangent(Vec3[] pts, int i)
    {
        Vec3 a = i > 0 ? pts[i - 1] : pts[i];
        Vec3 b = i < pts.length - 1 ? pts[i + 1] : pts[i];
        Vec3 t = b.subtract(a);
        return t.lengthSqr() < 1.0E-8D ? null : t.normalize();
    }

    // the screen-facing perpendicular to travel at a point: cross(travel, toCamera). Falls back to a world up cross if the
    // pod flies straight at the camera (travel parallel to toCamera).
    private static Vec3 sideVector(Vec3 tangent, Vec3 point, Vec3 cam)
    {
        if (tangent == null)
        {
            return null;
        }
        Vec3 toCam = cam.subtract(point);
        if (toCam.lengthSqr() < 1.0E-8D)
        {
            return null;
        }
        toCam = toCam.normalize();
        Vec3 side = tangent.cross(toCam);
        if (side.lengthSqr() < 1.0E-6D)
        {
            side = tangent.cross(new Vec3(0.0D, 1.0D, 0.0D));
            if (side.lengthSqr() < 1.0E-6D)
            {
                side = tangent.cross(new Vec3(1.0D, 0.0D, 0.0D));
            }
        }
        return side.lengthSqr() < 1.0E-8D ? null : side.normalize();
    }

    // the exhaust flame: a stack of camera-facing additive discs at the nozzle, from a wide dim orange bloom to a small
    // white-hot core, all scaled by speed. Camera facing via the entity render dispatcher's orientation, drawn in the
    // pose already shifted camera relative, exactly as SpaceBodyRenderer draws its coronae.
    private static void drawExhaust(PoseStack pose, VertexConsumer buffer, Vec3 cam, Vec3 nozzle, float speedScale,
                                    float[] coreRgb, float[] glowRgb)
    {
        double dx = nozzle.x - cam.x;
        double dy = nozzle.y - cam.y;
        double dz = nozzle.z - cam.z;
        var orientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(orientation);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F)); // face the disc plane toward the camera
        var m = pose.last().pose();
        float base = EXHAUST_RADIUS * speedScale;
        // wide aura-tinted bloom, mid brighter bloom, small near-white hot core (nudged toward the aura hue so even the
        // hottest point of the flame carries the colour instead of washing to plain white).
        drawGlowDisc(buffer, m, base * 1.6F, glowRgb, 0.22F * speedScale);
        drawGlowDisc(buffer, m, base * 1.0F, coreRgb, 0.45F * speedScale);
        drawGlowDisc(buffer, m, base * 0.5F, whiten(coreRgb, 0.6F), 0.75F * speedScale);
        pose.popPose();
    }

    // a soft radial disc in the local XY plane: a fan of quads with a bright centre and a fully transparent rim, so
    // additive blending paints a smooth falloff with no texture. Emitted as quads (two coincident centre vertices per
    // wedge) to fit the QUADS render type.
    private static void drawGlowDisc(VertexConsumer buffer, org.joml.Matrix4f m, float radius, float[] rgb, float alpha)
    {
        if (radius <= 0.0F || alpha <= 0.0F)
        {
            return;
        }
        for (int s = 0; s < EXHAUST_DISC_SEGMENTS; s++)
        {
            double a0 = (Math.PI * 2.0D * s) / EXHAUST_DISC_SEGMENTS;
            double a1 = (Math.PI * 2.0D * (s + 1)) / EXHAUST_DISC_SEGMENTS;
            float x0 = (float) (Math.cos(a0) * radius);
            float y0 = (float) (Math.sin(a0) * radius);
            float x1 = (float) (Math.cos(a1) * radius);
            float y1 = (float) (Math.sin(a1) * radius);
            // centre (bright) twice, then the two rim points (transparent).
            buffer.vertex(m, 0.0F, 0.0F, 0.0F).color(rgb[0], rgb[1], rgb[2], alpha).endVertex();
            buffer.vertex(m, 0.0F, 0.0F, 0.0F).color(rgb[0], rgb[1], rgb[2], alpha).endVertex();
            buffer.vertex(m, x0, y0, 0.0F).color(rgb[0], rgb[1], rgb[2], 0.0F).endVertex();
            buffer.vertex(m, x1, y1, 0.0F).color(rgb[0], rgb[1], rgb[2], 0.0F).endVertex();
        }
    }

    private static void vertex(VertexConsumer buffer, org.joml.Matrix4f m, Vec3 p, float[] rgb, float alpha)
    {
        buffer.vertex(m, (float) p.x, (float) p.y, (float) p.z)
                .color(rgb[0], rgb[1], rgb[2], alpha)
                .endVertex();
    }

    // speed (blocks/tick) mapped to a 0.4..1.0 width/flame scale, so a slow drift wears a thin short wake and a full
    // cruise a wide bright one, without any layer vanishing entirely once the pod is moving.
    private static float speedScale(double speed)
    {
        double t = (speed - MIN_TRAIL_SPEED) / (1.2D - MIN_TRAIL_SPEED);
        t = Math.max(0.0D, Math.min(1.0D, t));
        return (float) (0.4D + 0.6D * t);
    }

    // ==== aura colour ====
    //
    // The player driving a pod, or null. Prefers the controlling passenger (the driver) and otherwise takes the first
    // player passenger, so a saiyan-ship pod with a single occupant is covered too.
    private static Player pilotOf(SpacePodEntity pod)
    {
        Entity driver = pod.getControllingPassenger();
        if (driver instanceof Player p)
        {
            return p;
        }
        for (Entity passenger : pod.getPassengers())
        {
            if (passenger instanceof Player p)
            {
                return p;
            }
        }
        return null;
    }

    // The pilot's DMZ aura colour as {r, g, b} in 0..1, resolved EXACTLY the way DMZ builds the base aura layer in
    // AuraRenderer.getAuraLayers: the character's own aura colour, overridden by the active form's aura colour when the
    // form sets one. We wrap the read in the same form-cosmetic render-target scope the aura draw uses
    // (FormCosmeticClientStore, consumed by MixinDmzCharacterFormCosmetic's getActiveFormData override), so a player who
    // has recoloured their aura through a form cosmetic gets that recoloured value here too, matching what is drawn.
    // Returns null on any failure so the caller falls back to the shared palette. Runs on the client tick thread; the
    // render-target ThreadLocal is per thread, so this never disturbs an in-progress render on the render thread.
    private static float[] resolveAuraColor(Player pilot)
    {
        if (pilot == null)
        {
            return null;
        }
        try
        {
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, pilot).resolve().orElse(null);
            if (stats == null)
            {
                return null;
            }
            Character character = stats.getCharacter();
            if (character == null)
            {
                return null;
            }
            UUID prev = FormCosmeticClientStore.currentRenderTarget();
            FormCosmeticClientStore.setRenderTarget(pilot.getUUID());
            try
            {
                float[] color = character.getRgbAuraColor();
                if (character.hasActiveForm())
                {
                    FormConfig.FormData fd = character.getActiveFormData();
                    if (fd != null && fd.getAuraColor() != null && !fd.getAuraColor().isEmpty())
                    {
                        float[] formColor = fd.getRgbAuraColor();
                        if (formColor != null)
                        {
                            color = formColor;
                        }
                    }
                }
                if (color == null || color.length < 3)
                {
                    return null;
                }
                // copy: DMZ hands back a cached array we must not retain or mutate.
                return new float[] {color[0], color[1], color[2]};
            }
            finally
            {
                if (prev != null)
                {
                    FormCosmeticClientStore.setRenderTarget(prev);
                }
                else
                {
                    FormCosmeticClientStore.clearRenderTarget();
                }
            }
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // Pull a colour toward white by t (0 = unchanged, 1 = white), for the hot core of the flame.
    private static float[] whiten(float[] rgb, float t)
    {
        float inv = 1.0F - t;
        return new float[] {
                rgb[0] * inv + t,
                rgb[1] * inv + t,
                rgb[2] * inv + t};
    }

    // ==== render type ====
    //
    // An untextured additive ribbon/flame type: SRC_ALPHA, ONE (additive so overlaps read brighter), depth TESTED against
    // the world (a planet or terrain in front occludes the wake) but depth writes OFF (the wake never occludes anything),
    // no cull (the ribbon is two sided). POSITION_COLOR: per-vertex colour and alpha, no texture. Access to the shard
    // constants and create() comes from extending RenderType, the same idiom SpaceBodyRenderer.SuperRenderTypes uses.
    private static RenderType podTrailType;

    static RenderType podTrail()
    {
        if (podTrailType == null)
        {
            podTrailType = PodTrailRenderType.build();
        }
        return podTrailType;
    }

    private static final class PodTrailRenderType extends RenderType
    {
        private PodTrailRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                                   boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear)
        {
            super(name, format, mode, size, affectsCrumbling, sortOnUpload, setup, clear);
            throw new UnsupportedOperationException();
        }

        static RenderType build()
        {
            CompositeState state = CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false);
            return create("su_pod_trail", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536,
                    false, true, state);
        }
    }
}
