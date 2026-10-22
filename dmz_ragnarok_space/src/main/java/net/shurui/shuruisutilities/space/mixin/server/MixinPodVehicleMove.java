package net.shurui.shuruisutilities.space.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.init.entities.SpacePodEntity;

import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import net.shurui.shuruisutilities.space.PodSpeedBoost;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Stops the space pod rubber band, and the pod vanishing under its pilot, on the server.
 *
 * <p>Vanilla's {@code handleMoveVehicle} measures every client vehicle report against {@code vehicleFirstGood}, the
 * vehicle's position at the START of the server tick, and rejects (snapping the client back with a
 * {@code ClientboundMoveVehiclePacket}) any report more than 10 blocks past the vehicle's own delta. A pod cruises at
 * about 12 blocks per tick, so the moment two client move packets land in one server tick (ordinary network jitter, and
 * constant while the server lags) the second sits about 24 blocks from that start point, fails the check, and the pilot
 * is yanked back. Our movement-warning filter hid the "moved too quickly" line, so it never showed in a log.
 *
 * <p>For a DMZ space pod controlled by this player, a report within a bounded distance of the server pod (scaled by the
 * pod's own per-tick speed, plus a flat margin) moves {@code vehicleFirstGood} onto the report, so the speed check
 * passes and vanilla's own collision path still applies. A report beyond the bound falls through to vanilla untouched,
 * so a client cannot use this to teleport. Every other vehicle is untouched.
 *
 * <p>Second job, space dimension only: a pod outruns chunk loading. If vanilla moved the server pod into a chunk whose
 * entities are not live yet, its entity section would go hidden, the pod would stop being tracked and every client,
 * the pilot's included, would be told to remove it: the pilot is left with no pod under them, their client starts
 * reporting them as a free player far ahead, and the pod's old chunk unloads behind. So a report that lands in such a
 * chunk is HELD: the server pod advances along the report's line only as far as live chunks reach, the pilot's chunk
 * tracking follows it (so the chunks ahead keep loading), no snap is sent and the pilot keeps flying. Once the report's
 * own chunk is live, vanilla's normal path moves the pod there; that catch-up is allowed because each report is checked
 * against the previous one at the pod's own cruise speed, so a held stretch is never a free teleport.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinPodVehicleMove
{
    // how many ticks of the pod's own speed a report may lead or trail the server pod by, and a flat margin in blocks
    // on top (covers a pod that has just been slowed, and the server's own advance in SpaceTravelModule.integratePod).
    private static final double SU_TICKS_OF_SLACK = 8.0D;
    private static final double SU_FLAT_SLACK = 16.0D;
    // at most this many reports in a row (one per client tick, so ten seconds) may be held WITHOUT the server pod
    // advancing at all before vanilla takes over again and snaps if it must, so chunks that never load cannot leave the
    // server pod stuck forever. A hold that keeps advancing never runs out.
    private static final int SU_MAX_HELD_REPORTS = 200;

    @Shadow
    public ServerPlayer player;
    @Shadow
    private Entity lastVehicle;
    @Shadow
    private double vehicleFirstGoodX;
    @Shadow
    private double vehicleFirstGoodY;
    @Shadow
    private double vehicleFirstGoodZ;
    @Shadow
    private double vehicleLastGoodX;
    @Shadow
    private double vehicleLastGoodY;
    @Shadow
    private double vehicleLastGoodZ;

    // the last report accepted or held for this connection's pod, and how many reports have been held in a row.
    @Unique
    private int su$reportPodId = -1;
    @Unique
    private double su$reportX, su$reportY, su$reportZ;
    @Unique
    private int su$heldReports;
    @Unique
    private boolean su$holding;

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true, require = 0)
    private void su$podMoveSlack(ServerboundMoveVehiclePacket packet, CallbackInfo ci)
    {
        try
        {
            // HEAD also runs once on the network thread before vanilla re-queues the packet; only act on the server thread.
            if (this.player == null || !this.player.server.isSameThread())
            {
                return;
            }
            Entity vehicle = this.player.getRootVehicle();
            if (!(vehicle instanceof SpacePodEntity pod) || vehicle != this.lastVehicle
                    || vehicle.getControllingPassenger() != this.player)
            {
                this.su$reportPodId = -1;
                return;
            }
            double px = packet.getX(), py = packet.getY(), pz = packet.getZ();
            double dx = px - vehicle.getX();
            double dy = py - vehicle.getY();
            double dz = pz - vehicle.getZ();
            if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz))
            {
                return;
            }

            // continuity with this pod's previous report, at its own cruise speed (the server pod's delta is zero
            // while it is ridden, so the attribute is the honest per-tick speed).
            boolean continuous = false;
            if (this.su$reportPodId == pod.getId())
            {
                double cruise = pod.getAttributeValue(Attributes.FLYING_SPEED) * PodSpeedBoost.POD_HORIZONTAL_FACTOR;
                double step = Math.max(cruise, vehicle.getDeltaMovement().length()) * SU_TICKS_OF_SLACK + SU_FLAT_SLACK;
                double rx = px - this.su$reportX, ry = py - this.su$reportY, rz = pz - this.su$reportZ;
                continuous = rx * rx + ry * ry + rz * rz <= step * step;
            }

            if (vehicle.level() instanceof ServerLevel level && SpaceKeys.isSpace(level) && continuous
                    && this.su$heldReports < SU_MAX_HELD_REPORTS)
            {
                if (!su$live(level, px, py, pz))
                {
                    // hold: move the server pod as far toward the report as live chunks reach (sampled every 8 blocks,
                    // farthest first), seat the pilot on it and move their chunk tracking, so the chunks ahead keep
                    // coming. Vanilla is skipped, so nothing is moved into a chunk that would hide the pod.
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    int samples = Math.max(1, (int) Math.ceil(length / 8.0D));
                    boolean advanced = false;
                    for (int i = samples - 1; i >= 1; i--)
                    {
                        double t = i / (double) samples;
                        double x = vehicle.getX() + dx * t, y = vehicle.getY() + dy * t, z = vehicle.getZ() + dz * t;
                        if (su$live(level, x, y, z))
                        {
                            vehicle.absMoveTo(x, y, z, packet.getYRot(), packet.getXRot());
                            vehicle.positionRider(this.player);
                            level.getChunkSource().move(this.player);
                            this.vehicleFirstGoodX = this.vehicleLastGoodX = x;
                            this.vehicleFirstGoodY = this.vehicleLastGoodY = y;
                            this.vehicleFirstGoodZ = this.vehicleLastGoodZ = z;
                            advanced = true;
                            break;
                        }
                    }
                    this.su$remember(pod, px, py, pz);
                    this.su$heldReports = advanced ? 0 : this.su$heldReports + 1;
                    this.su$holding = true;
                    ci.cancel();
                    return;
                }
            }

            double speed = vehicle.getDeltaMovement().length();
            double allowed = speed * SU_TICKS_OF_SLACK + SU_FLAT_SLACK;
            boolean nearServerPod = dx * dx + dy * dy + dz * dz <= allowed * allowed;
            // a pod caught up after a hold is far from the server pod but in step with its own last report.
            boolean catchUp = this.su$holding && continuous;
            this.su$remember(pod, px, py, pz);
            this.su$heldReports = 0;
            this.su$holding = false;
            if (!nearServerPod && !catchUp)
            {
                return;
            }
            this.vehicleFirstGoodX = px;
            this.vehicleFirstGoodY = py;
            this.vehicleFirstGoodZ = pz;
        }
        catch (Throwable ignored)
        {
            // never let the slack break vehicle movement: vanilla runs exactly as before.
        }
    }

    // True when the entity manager already treats this position's chunk as live (entity ticking). That is the state the
    // pod's section takes when it moves in, so a pod moved there stays tracked. A chunk merely present in memory is not
    // enough: until its promotion is applied the manager still hides every entity in it.
    @Unique
    private static boolean su$live(ServerLevel level, double x, double y, double z)
    {
        return level.isPositionEntityTicking(BlockPos.containing(x, y, z));
    }

    @Unique
    private void su$remember(Entity pod, double x, double y, double z)
    {
        this.su$reportPodId = pod.getId();
        this.su$reportX = x;
        this.su$reportY = y;
        this.su$reportZ = z;
    }
}
