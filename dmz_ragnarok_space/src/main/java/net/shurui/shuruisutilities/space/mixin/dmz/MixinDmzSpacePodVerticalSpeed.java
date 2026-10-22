package net.shurui.shuruisutilities.space.mixin.dmz;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import com.dragonminez.common.init.entities.SpacePodEntity;
import com.dragonminez.common.init.entities.vehicle.VehicleEntity;

import net.shurui.shuruisutilities.space.PodSpeedBoost;
import net.shurui.shuruisutilities.world.space.SpaceKeys;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Job 1: a ridden space pod in the SU space dimension cruises at the boosted speed {@link PodSpeedBoost} sets on its
 * FLYING_SPEED attribute (2x the pilot's max flight speed), on all three axes.
 *
 * <p>DragonMineZ 2.2 rebuilt the pod on its generic {@code VehicleEntity}: horizontal speed is now the fixed vehicle
 * property (1.08, 2.1 read {@code FLYING_SPEED * 0.45}) and vertical is the fixed {@code verticalSpeed} property (0.35),
 * so the attribute alone no longer moves anything. The driven motion is applied by exactly one
 * {@code setDeltaMovement(x, y, z)} call in {@code VehicleEntity.travelDriven}; we redirect it and, for a space pod in
 * space only, scale the horizontal part to {@code FLYING_SPEED * 0.45} (the 2.1 magnitude, so PodSpeedBoost's numbers
 * keep their meaning) and give the vertical part that same magnitude with DMZ's sign. Every other vehicle, and a pod
 * anywhere else, keeps DMZ's motion exactly. The pod has no acceleration blend (DMZ default 1.0), so the scale never
 * compounds across ticks.
 *
 * <p>{@code remap = false} at class level; the INVOKE target carries {@code remap = true} so {@code setDeltaMovement}
 * maps to SRG in production while the DMZ owner stays literal. {@code require = 0}: a DMZ change makes this a silent
 * no-op (the pod flies at DMZ's stock speed), so the handler logs once when it first runs.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.vehicle.VehicleEntity", remap = false)
public abstract class MixinDmzSpacePodVerticalSpeed
{
    private static final AtomicBoolean SU_VERTICAL_BIND_LOGGED = new AtomicBoolean(false);

    @Redirect(
        method = "travelDriven",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dragonminez/common/init/entities/vehicle/VehicleEntity;setDeltaMovement(DDD)V",
            remap = true
        ),
        require = 0,
        remap = false
    )
    private void su$boostInSpace(VehicleEntity self, double x, double y, double z)
    {
        if (SU_VERTICAL_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[SpacePod] vertical-speed boost mixin bound");
            }
            catch (Throwable ignored)
            {
            }
        }
        double nx = x, ny = y, nz = z;
        try
        {
            Level level = self.level();
            if (self instanceof SpacePodEntity && SpaceKeys.isSpace(level))
            {
                double speed = ((LivingEntity) self).getAttributeValue(Attributes.FLYING_SPEED)
                        * PodSpeedBoost.POD_HORIZONTAL_FACTOR;
                if (speed > 0.0D)
                {
                    double horizontal = Math.sqrt(x * x + z * z);
                    double stock = self.getProperties().speed();
                    if (horizontal > 1.0e-6D && stock > 0.0D)
                    {
                        double scale = speed / stock;
                        nx = x * scale;
                        nz = z * scale;
                    }
                    if (y != 0.0D)
                        ny = Math.copySign(speed, y);
                }
            }
        }
        catch (Throwable t)
        {
            nx = x;
            ny = y;
            nz = z;
        }
        self.setDeltaMovement(nx, ny, nz);
    }
}
