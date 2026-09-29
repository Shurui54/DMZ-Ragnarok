package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.client.autopilot.SpaceAutopilotClient;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The core of the space-pod jitter fix, client side. When SU autopilot is driving the LOCAL pilot's pod, this makes the
 * pod move through the client's OWN {@code travel()} exactly the way MANUAL flight does, instead of being snapped to a
 * server position every tick. Manual flight is smooth precisely because it is client-authoritative and never reaches
 * {@code Entity.absMoveTo} (which sets {@code xo = x} and leaves the game with nothing to interpolate); by integrating
 * the pod here and letting the client report position through the ordinary {@code ServerboundMoveVehiclePacket} path, we
 * put autopilot on that same smooth path.
 *
 * <p>Each client tick, while autopilot is active for the local pilot and the pod is in the synced target's dimension, we
 * take a single step of length {@code min(step, distanceToTarget)} toward the synced target, face travel, set the pod's
 * delta to that step (so the server's vehicle "moved too quickly" tolerance, which compares against the pod's own delta,
 * is satisfied), {@code move(MoverType.SELF, ...)} it, and CANCEL DMZ's own {@code travel}. Everything else, manual
 * flight most of all, falls straight through untouched: the guard requires the pod's controlling passenger to be the
 * local player AND an active, dimension-matched SU autopilot.
 *
 * <p>Interaction with {@code space.mixin.dmz.MixinDmzSpacePodVerticalSpeed}: that mixin's {@code @ModifyArg} rewrites the
 * vertical arg of a {@code setDeltaMovement} call INSIDE DMZ's {@code travel}. When this HEAD inject cancels, that call
 * is never reached, so the two never fight; when autopilot is off this inject does nothing and the vertical mixin drives
 * manual flight exactly as before.
 *
 * <p>{@code remap = false} at class level (the target is DMZ's own class name, matching SU's other DMZ mixins); the
 * {@code @Inject} carries {@code remap = true} so the refmap maps the vanilla method name {@code travel} to its SRG form
 * in the production DMZ jar. {@code require = 0} is MANDATORY per the standing rule for mixins into DMZ classes: a DMZ
 * change degrades this to "autopilot no longer drives" instead of crashing the client. Because {@code require = 0} fails
 * SILENTLY, the handler logs once when it first weaves; the ABSENCE of that line in the log is how we know it did not
 * bind.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.SpacePodEntity", remap = false)
public abstract class MixinDmzSpacePodTravel
{
    private static final AtomicBoolean SU_TRAVEL_BIND_LOGGED = new AtomicBoolean(false);

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$autopilotTravel(Vec3 travelVector, CallbackInfo ci)
    {
        // log-once on first weave. require = 0 fails SILENTLY, so this line appearing is the proof the injector bound;
        // its ABSENCE is the proof it did not. Guarded + latched so it prints exactly once and never disturbs movement.
        if (SU_TRAVEL_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[SpacePod] autopilot travel mixin bound");
            }
            catch (Throwable ignored)
            {
            }
        }

        try
        {
            LivingEntity self = (LivingEntity) (Object) this;
            Level level = self.level();
            // client only: the server drives the pod through SpaceTravelModule's own integration, never this mixin.
            if (!level.isClientSide)
            {
                return;
            }
            // only the LOCAL pilot's own pod. Other players' pods (and any pod the local player is not controlling) run
            // DMZ's travel untouched.
            LocalPlayer localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null)
            {
                return;
            }
            Entity controller = self.getControllingPassenger();
            if (controller != localPlayer)
            {
                return;
            }
            // active SU autopilot whose target lives in THIS pod's dimension. The dimension guard skips a fresh pod that
            // has just crossed into space until its space-cruise target arrives, so we never chase a stale target.
            if (!SpaceAutopilotClient.matches(level.dimension().location()))
            {
                return;
            }

            Vec3 pos = self.position();
            Vec3 target = SpaceAutopilotClient.target();
            double step = SpaceAutopilotClient.step();
            Vec3 delta = target.subtract(pos);
            double dist = delta.length();
            if (step <= 0.0 || dist < 1.0e-4)
            {
                // already at the target (or no step yet): hold still and let the server's arrival detection act. Zeroing
                // the delta stops any residual coast.
                self.setDeltaMovement(Vec3.ZERO);
                ci.cancel();
                return;
            }

            double travel = Math.min(step, dist);
            Vec3 move = delta.scale(travel / dist);
            float yaw = (float) Math.toDegrees(Math.atan2(-move.x, move.z));
            self.setYRot(yaw);
            self.setYBodyRot(yaw);
            self.setYHeadRot(yaw);
            self.setDeltaMovement(move);
            self.move(MoverType.SELF, move);
            ci.cancel();
        }
        catch (Throwable t)
        {
            // any trouble reading the pod/level/autopilot state: fall back to DMZ's own travel rather than freeze the
            // pod. Do NOT cancel, so DMZ moves it normally this tick.
        }
    }
}
