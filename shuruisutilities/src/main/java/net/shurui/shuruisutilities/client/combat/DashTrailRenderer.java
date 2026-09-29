package net.shurui.shuruisutilities.client.combat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.shurui.shuruisutilities.core.SUConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the streak a dashing or flying player leaves behind, as lines rather than as textured quads.
 *
 * <p>Lines were chosen over a billboarded ribbon for a practical reason as well as a look: a ribbon has to be turned to
 * face the camera every frame and degenerates into an invisible edge when the dash is coming straight at the viewer,
 * which is precisely the angle a dash at you is seen from. Lines have no facing to get wrong.
 *
 * <p>Positions are sampled on the client tick and drawn between on the render pass, so the trail is built from real
 * travelled positions rather than extrapolated. Each sample fades with age, which is what gives the streak a head and a
 * tail instead of a uniform stripe.
 *
 * <h2>Why a sample can be a lie, and how the streak is broken when it is</h2>
 * A trail is a chain of real positions joined nose to tail. That only reads as a streak while every link is a genuine
 * per tick step. It stops being one the moment two consecutive positions are NOT one tick's travel apart, and the
 * client sees that constantly: a teleport, a portal, a dimension change, a shard hop, a pod landing, a respawn, and,
 * because the streak is keyed by entity id, an id being reused when a real player replaces a ghost or a player is
 * re-tracked after leaving render distance. Left alone, the renderer would join the stale far point to the live body
 * and draw a line across the whole world that stays pinned to the player for as long as they keep flying. So the trail
 * is CUT (its history dropped, a fresh streak started) whenever a sample is not continuous with the one before it:
 * further than one hard tick of travel, in a different dimension, or belonging to a different entity than last tick.
 * A final guard in the draw refuses any single segment longer than that same distance, so a bad link can never reach
 * the screen even if one slips past the sampler.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class DashTrailRenderer
{
    private DashTrailRenderer() {}

    // How many positions are kept per player. At twenty samples a second this is roughly a second of streak, which is
    // about the length of a dash.
    private static final int MAX_SAMPLES = 20;

    // Oldest a sample is allowed to be before it is dropped, whatever the player is doing. The per sample cap above
    // already ages points out while someone keeps moving (new samples push old ones off the end), but a player who
    // stops dead while still counted as flying, or one held on the dash entry's safety expiry, would otherwise keep a
    // now stale tail hanging in the air. This retires points by wall clock so the streak always trails off in about a
    // second regardless of movement. Kept a touch above the sample budget so it never fights the per tick cap.
    private static final long MAX_POINT_AGE_MILLIS = 1200L;

    // The longest step, in blocks, that can be a genuine one tick move. A dash is the fastest thing that trails: its
    // range is 104 blocks over as few as 12 travel ticks, about 8.7 blocks a tick at the very peak, and flight is far
    // slower. Twelve leaves better than a third of headroom over that peak, so no real dash or flight is ever cut,
    // while every teleport, portal, dimension change and shard hop (tens to thousands of blocks) is. A step longer than
    // this is not travel, it is a jump, and the streak is broken across it rather than drawn.
    private static final double MAX_SEGMENT = 12.0D;
    private static final double MAX_SEGMENT_SQR = MAX_SEGMENT * MAX_SEGMENT;

    // Lines drawn between each pair of samples, spread across the width of the streak so it reads as a band rather than
    // a single hairline.
    private static final int STRANDS = 5;
    private static final double STRAND_SPREAD = 0.45D;

    // Where on the body the streak leaves from. A fixed height rather than a fraction of the bounding box, because the
    // box SHRINKS when a player goes prone to fly, which is exactly when the trail is drawn; a fraction of it therefore
    // slid down toward the feet at speed. This keeps the origin at the waist whatever the pose.
    private static final double WAIST_HEIGHT = 0.9D;

    // How far back along the direction of travel the streak's head sits. A trail leaves from behind someone, so without
    // this it emerges from the front of the model and reads as something being pushed rather than something left behind.
    private static final double TRAIL_SETBACK = 0.45D;

    // How far the aura tint is lifted toward white before it is drawn, 0 being the raw colour. Small on purpose: enough
    // that a dark aura still reads as a glowing streak rather than a smear of shadow, little enough that the hue is
    // still recognisably the player's own.
    private static final float HIGHLIGHT = 0.25F;

    private static final Map<Integer, Trail> TRAILS = new HashMap<>();

    // One sample, with the moment it was taken so it can be aged out by wall clock and not only by being pushed off the
    // end of a full deque.
    private record Sample(Vec3 pos, long time) {}

    // The history for one entity, plus the two facts that decide whether the newest sample is continuous with it: the
    // dimension it was taken in and the identity of the entity it belongs to. Either changing means the id now points
    // somewhere or someone else, so the old points must not be joined to the new ones.
    private static final class Trail
    {
        final Deque<Sample> samples = new ArrayDeque<>();
        ResourceKey<Level> dimension;
        UUID owner;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        // Turned off in DragonMineZ's config menu: drop what is held and sample nothing further, so switching it off
        // clears the streaks already in the air rather than freezing them there.
        if (!SUConfig.auraTrails)
        {
            TRAILS.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            TRAILS.clear();
            return;
        }
        long now = System.currentTimeMillis();
        // Sample every player who is dashing OR simply flying, and let everyone else's trail decay a sample per tick so
        // a streak trails off after they stop instead of vanishing the instant they do.
        for (Player p : mc.level.players())
        {
            int id = p.getId();
            if (DashAuraState.isTrailing(id))
            {
                Trail trail = TRAILS.computeIfAbsent(id, k -> new Trail());
                Vec3 pos = p.position().add(0.0D, WAIST_HEIGHT, 0.0D);
                ResourceKey<Level> dim = p.level().dimension();
                UUID owner = p.getUUID();
                // Cut the streak wherever the newest point is not continuous with the last one. A different dimension
                // or a different owner means the entity id was handed to somewhere or someone else since last tick; a
                // step longer than one hard tick of travel is a teleport, portal, shard hop or pod landing, not flight.
                // In every case the old points belong to another place and must not be joined to this one.
                Sample last = trail.samples.peekLast();
                boolean discontinuous = !dim.equals(trail.dimension)
                        || !owner.equals(trail.owner)
                        || (last != null && last.pos().distanceToSqr(pos) > MAX_SEGMENT_SQR);
                if (discontinuous)
                    trail.samples.clear();
                trail.dimension = dim;
                trail.owner = owner;
                trail.samples.addLast(new Sample(pos, now));
                prune(trail.samples, now);
            }
        }
        for (Iterator<Map.Entry<Integer, Trail>> it = TRAILS.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<Integer, Trail> e = it.next();
            Trail trail = e.getValue();
            // An id that no longer resolves to a live entity, or resolves to someone other than this trail's owner, is
            // stale. Drop it outright rather than let its old points hang or, worse, attach to whoever now holds the id.
            Entity current = mc.level.getEntity(e.getKey());
            if (current == null || (trail.owner != null && !trail.owner.equals(current.getUUID())))
            {
                it.remove();
                continue;
            }
            // Age points out by wall clock whether or not the player is moving, so a stationary flier's tail still ends.
            prune(trail.samples, now);
            if (DashAuraState.isTrailing(e.getKey()))
                continue;
            trail.samples.pollFirst();
            if (trail.samples.size() < 2)
                it.remove();
        }
    }

    // Drop the oldest samples until the deque is within its length budget and holds nothing past its age limit.
    private static void prune(Deque<Sample> samples, long now)
    {
        while (samples.size() > MAX_SAMPLES)
            samples.removeFirst();
        Sample head;
        while ((head = samples.peekFirst()) != null && now - head.time() > MAX_POINT_AGE_MILLIS)
            samples.removeFirst();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        // Drawn with the other translucent work so the streak blends with the world rather than punching through it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        // Checked here as well as in the tick: the render pass runs between ticks, so a toggle mid-frame must not
        // paint one last set of streaks from samples the tick is about to drop.
        if (!SUConfig.auraTrails)
            return;
        if (TRAILS.isEmpty())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        try
        {
            Vec3 camera = event.getCamera().getPosition();
            float partialTick = event.getPartialTick();
            PoseStack pose = event.getPoseStack();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            VertexConsumer buffer = buffers.getBuffer(RenderType.lines());

            pose.pushPose();
            try
            {
                // Everything is drawn in camera relative space, so the whole level is shifted once here rather than
                // every sample being converted individually.
                pose.translate(-camera.x, -camera.y, -camera.z);
                for (Map.Entry<Integer, Trail> entry : TRAILS.entrySet())
                {
                    drawTrail(pose, buffer, mc, entry.getKey(), entry.getValue(), partialTick);
                }
            }
            finally
            {
                pose.popPose();
            }
            buffers.endBatch(RenderType.lines());
        }
        catch (Throwable t)
        {
            // A trail is decoration. It must never take the level render down with it.
        }
    }

    private static void drawTrail(PoseStack pose, VertexConsumer buffer, Minecraft mc, int entityId, Trail trail,
            float partialTick)
    {
        if (trail.samples.size() < 2)
            return;
        Sample[] history = trail.samples.toArray(new Sample[0]);
        int count = history.length;
        Vec3[] samples = new Vec3[count];
        for (int i = 0; i < count; i++)
            samples[i] = history[i].pos();
        // Tinted to whatever colour this player's aura is currently drawn in, so the streak reads as part of the aura
        // rather than as a separate white effect laid over it. Resolved per player, not per segment, because the
        // lookup walks DMZ's stats capability and the colour cannot change within one trail.
        Entity entity = mc.level == null ? null : mc.level.getEntity(entityId);
        // Only draw for the entity this trail belongs to. An id reused since the trail was built resolves to somebody
        // else; the tick pass drops such trails, but the render pass runs between ticks, so guard here too.
        if (entity != null && trail.owner != null && !trail.owner.equals(entity.getUUID()))
            return;
        float[] tint = AuraColors.of(entity instanceof Player p ? p : null);
        // Re-seat the head of the streak on where the player is actually DRAWN this frame.
        //
        // Samples are taken on the tick, but the player is rendered somewhere between last tick's position and this
        // one. At a dash's two and a bit blocks per tick the newest sample therefore sits up to that far in FRONT of the
        // body, which is exactly the "lines start in front of them" the trail looked wrong for. Interpolating the head
        // the same way the model is interpolated pins the two together at every frame rate.
        if (entity != null)
        {
            Vec3 head = entity.getPosition(partialTick).add(0.0D, WAIST_HEIGHT, 0.0D);
            // Set back along travel so the streak leaves from the player's back rather than their nose. Taken from the
            // last two samples rather than from the look vector, because a dash does not have to go where you look.
            Vec3 along = head.subtract(samples[count - 2]);
            if (along.lengthSqr() > 1.0E-8D)
                head = head.subtract(along.normalize().scale(TRAIL_SETBACK));
            samples[count - 1] = head;
        }
        for (int i = 1; i < count; i++)
        {
            Vec3 from = samples[i - 1];
            Vec3 to = samples[i];
            Vec3 along = to.subtract(from);
            if (along.lengthSqr() < 1.0E-8D)
                continue;
            // Never draw a jump. Even with the sampler breaking the streak on a discontinuity, the re-seated head can
            // land a whole interpolated step from the last real sample right as a teleport is drawn, so refuse any
            // segment longer than one hard tick of travel here as the last line of defence.
            if (along.lengthSqr() > MAX_SEGMENT_SQR)
                continue;
            // Age runs from 0 at the oldest sample to 1 at the newest, so the streak is brightest and widest at the
            // player and thins out behind them.
            float age = (float) i / (float) count;
            float alpha = age * age;
            Vec3 side = along.normalize().cross(new Vec3(0.0D, 1.0D, 0.0D));
            if (side.lengthSqr() < 1.0E-8D)
                side = new Vec3(1.0D, 0.0D, 0.0D);
            side = side.normalize();
            Vec3 up = side.cross(along.normalize()).normalize();

            for (int strand = 0; strand < STRANDS; strand++)
            {
                // Strands are laid out around the travel axis, so the band has depth from every viewing angle instead of
                // collapsing to a flat sheet when seen edge on.
                double theta = (Math.PI * 2.0D * strand) / STRANDS;
                double spread = STRAND_SPREAD * age;
                Vec3 offset = side.scale(Math.cos(theta) * spread).add(up.scale(Math.sin(theta) * spread));
                Vec3 a = from.add(offset);
                Vec3 b = to.add(offset);
                line(pose, buffer, a, b, tint, alpha);
            }
        }
    }

    private static void line(PoseStack pose, VertexConsumer buffer, Vec3 a, Vec3 b, float[] tint, float alpha)
    {
        var last = pose.last();
        Vec3 dir = b.subtract(a);
        if (dir.lengthSqr() < 1.0E-8D)
            return;
        dir = dir.normalize();
        // Lifted toward white rather than used raw. A dark aura colour drawn straight would give a streak that reads as
        // a shadow against the world, so the tint keeps its hue while staying bright enough to look like light.
        float r = HIGHLIGHT + (1.0F - HIGHLIGHT) * tint[0];
        float g = HIGHLIGHT + (1.0F - HIGHLIGHT) * tint[1];
        float b2 = HIGHLIGHT + (1.0F - HIGHLIGHT) * tint[2];
        // RenderType.lines wants a normal per vertex; the segment direction is the natural one and keeps the line's
        // shading consistent along its length.
        buffer.vertex(last.pose(), (float) a.x, (float) a.y, (float) a.z)
                .color(r, g, b2, alpha)
                .normal(last.normal(), (float) dir.x, (float) dir.y, (float) dir.z)
                .endVertex();
        buffer.vertex(last.pose(), (float) b.x, (float) b.y, (float) b.z)
                .color(r, g, b2, alpha)
                .normal(last.normal(), (float) dir.x, (float) dir.y, (float) dir.z)
                .endVertex();
    }
}
