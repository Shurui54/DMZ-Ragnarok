package net.shurui.dev.sdu.compat;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;
import com.dragonminez.common.init.entities.ki.KiBlastEntity;
import com.dragonminez.common.init.entities.ki.KiLaserEntity;
import com.dragonminez.common.init.entities.ki.KiWaveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Stops DragonMineZ ki attacks damaging through solid blocks.
 *
 * <p>DMZ never asks whether a wall stands between a ki attack and what it hurts. A ball's detonation
 * ({@code KiBlastEntity.explodeAndDie}) and its charging pulse ({@code pulseAreaDamage}) damage every living thing in
 * a CUBE around the ball before any block is broken, so a blast bursting on the outside of a house hits everyone
 * inside it, and where ki griefing is off the wall does not even come down. Beams, disks, area waves and Final
 * Explosion have the same gap. All of them funnel their entity damage through
 * {@code AbstractKiProjectile.applyDamageOrHeal}, which is where {@code AbstractKiProjectileMixin} calls this.
 *
 * <h2>The rule</h2>
 *
 * <p>Rays are cast from the target's eyes, middle and feet to one source point on the attack. If ANY ray is clear the
 * damage lands; only when every ray meets a block first is it refused. Healing is never touched.
 *
 * <p>The source point depends on the attack's shape:
 * <ul>
 *   <li><b>Beams</b> ({@code KiWaveEntity}, {@code KiLaserEntity}): the point on the beam nearest the target. DMZ
 *       already cuts a beam off at the first block it meets, so that point is in open air on the beam's own path.</li>
 *   <li><b>Balls</b> ({@code KiBlastEntity}): the point on the ball's SURFACE nearest the target, not its centre. A
 *       giant ball is up to 15 blocks across and DMZ does not stop one on block contact, so its centre is routinely
 *       buried in the ground while its body sits on top of the players it is hitting. Aiming at the surface keeps
 *       that a hit.</li>
 *   <li><b>Everything else</b>: the same surface rule about the bounding box centre.</li>
 * </ul>
 *
 * <h2>When it cannot tell, it allows</h2>
 *
 * <p>A source point inside a block cannot be judged (every ray would "hit" the block it sits in), so it falls back to
 * the attack's centre, then to where the attack was last tick, which it flew through and so was open. If all three
 * are inside blocks, the damage is allowed. The failure this exists to stop is damage through a wall; the failure it
 * must never cause is an ordinary hit going missing, so every uncertain case goes the old way.
 */
public final class KiLineOfSight {

    /** How close to the source a block hit may land and still count as clear: grazing the face the attack rests on. */
    private static final double SOURCE_TOLERANCE = 0.3;

    private KiLineOfSight() {
    }

    /** True when solid blocks stand between {@code ki} and every sampled point on {@code target}. Server side only. */
    public static boolean blockedByTerrain(AbstractKiProjectile ki, Entity target) {
        try {
            Level level = ki.level();
            if (level == null || level.isClientSide || target == null || ki.isHeal()) {
                return false;
            }
            AABB box = target.getBoundingBox();
            Vec3 middle = box.getCenter();
            Vec3 source = source(level, ki, middle);
            if (source == null) {
                return false;
            }
            Vec3[] samples = {
                    target.getEyePosition(),
                    middle,
                    new Vec3(middle.x, box.minY + 0.2, middle.z),
            };
            for (Vec3 sample : samples) {
                if (clear(level, ki, sample, source)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] ki line-of-sight check failed, allowing the hit: {}", DmzNpc.MODID, t.toString());
            return false;
        }
    }

    private static boolean clear(Level level, Entity ki, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ki));
        return hit.getType() == HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(to) <= SOURCE_TOLERANCE * SOURCE_TOLERANCE;
    }

    /** The point the rays aim at, or null when every candidate is inside a block and the hit cannot be judged. */
    private static Vec3 source(Level level, AbstractKiProjectile ki, Vec3 toward) {
        if (ki instanceof KiWaveEntity wave) {
            return open(level, nearestOnBeam(ki.position(), wave.getFixedPitch(), wave.getFixedYaw(), wave.getBeamLength(), toward));
        }
        if (ki instanceof KiLaserEntity laser) {
            return open(level, nearestOnBeam(ki.position(), laser.getFixedPitch(), laser.getFixedYaw(), laser.getBeamLength(), toward));
        }

        double radius;
        Vec3 centre;
        Vec3 previous;
        if (ki instanceof KiBlastEntity) {
            // DMZ's own getVisualCenterY (private): the ball's position is its bottom, and size is its diameter.
            radius = ki.getSize() / 2.0;
            centre = new Vec3(ki.getX(), ki.getY() + radius, ki.getZ());
            previous = new Vec3(ki.xo, ki.yo + radius, ki.zo);
        } else {
            AABB box = ki.getBoundingBox();
            centre = box.getCenter();
            radius = Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) / 2.0;
            previous = centre.add(ki.xo - ki.getX(), ki.yo - ki.getY(), ki.zo - ki.getZ());
        }

        Vec3 offset = toward.subtract(centre);
        double distance = offset.length();
        if (distance > 1.0e-6 && radius > 0.0) {
            Vec3 surface = centre.add(offset.scale(Math.min(radius, distance) / distance));
            if (!insideBlock(level, surface)) {
                return surface;
            }
        }
        if (!insideBlock(level, centre)) {
            return centre;
        }
        return insideBlock(level, previous) ? null : previous;
    }

    private static Vec3 nearestOnBeam(Vec3 start, float pitch, float yaw, float length, Vec3 toward) {
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        double t = Math.max(0.0, Math.min(length, toward.subtract(start).dot(dir)));
        return start.add(dir.scale(t));
    }

    private static Vec3 open(Level level, Vec3 point) {
        return insideBlock(level, point) ? null : point;
    }

    private static boolean insideBlock(Level level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        for (AABB part : shape.move(pos.getX(), pos.getY(), pos.getZ()).toAabbs()) {
            if (part.contains(point)) {
                return true;
            }
        }
        return false;
    }
}
