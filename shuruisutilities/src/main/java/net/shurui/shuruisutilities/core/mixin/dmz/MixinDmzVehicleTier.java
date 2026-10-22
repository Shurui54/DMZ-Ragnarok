package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.init.entities.vehicle.VehicleEntity;
import com.dragonminez.common.init.entities.vehicle.VehicleProperties;

import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.vehicleworkbench.VehicleTiers;

/**
 * Vehicle tiers for DragonMineZ's vehicles (car, Capsule Corp. 576, Frieza capsule). DMZ reads top speed, acceleration
 * and climb speed straight from the type's shared, immutable {@link VehicleProperties}, so a per-vehicle tier can only
 * scale them where they are read. The multipliers come from attribute modifiers that {@code VehicleTiers} stamps on a
 * tiered vehicle; a vehicle without them (every untiered one, the space pod) reads exactly DMZ's values.
 *
 * <p>Runs wherever DMZ simulates the ride, which is the driver's client, so the values come from synced attributes,
 * never from config. {@code require = 0} per the DMZ mixin rule: if DMZ reshapes its movement, tiers stop changing DMZ
 * vehicle speed and nothing else breaks.
 */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class MixinDmzVehicleTier
{
    @Redirect(method = "horizontalMotion", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/dragonminez/common/init/entities/vehicle/VehicleProperties;speed()D"))
    private double ragnarok$tierSpeed(VehicleProperties props)
    {
        return props.speed() * VehicleTiers.factor((LivingEntity) (Object) this, VehicleTiers.SPEED_ID);
    }

    // Below 1 DMZ eases toward the target speed; 1 or more snaps, so the scaled value is capped there.
    @Redirect(method = "horizontalMotion", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/dragonminez/common/init/entities/vehicle/VehicleProperties;acceleration()D"))
    private double ragnarok$tierAcceleration(VehicleProperties props)
    {
        double a = props.acceleration();
        return a >= 1.0D ? a : Math.min(1.0D, a * VehicleTiers.factor((LivingEntity) (Object) this, VehicleTiers.ACCEL_ID));
    }

    @Redirect(method = "flightVertical", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/dragonminez/common/init/entities/vehicle/VehicleProperties;verticalSpeed()D"))
    private double ragnarok$tierClimb(VehicleProperties props)
    {
        return props.verticalSpeed() * VehicleTiers.factor((LivingEntity) (Object) this, VehicleTiers.CLIMB_ID);
    }
}
