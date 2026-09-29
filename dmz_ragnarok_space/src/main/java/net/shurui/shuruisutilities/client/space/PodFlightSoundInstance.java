package net.shurui.shuruisutilities.client.space;

import com.dragonminez.common.init.entities.SpacePodEntity;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only looping flight loop for the DragonMineZ space pod during autopilot travel. Rides the pod, not the player,
 * and lerps its volume and pitch from the pod's per-tick speed using the exact curve DragonMineZ's
 * {@code com.dragonminez.client.flight.FlightSoundInstance} uses, so a travelling pod sounds identical to normal DMZ
 * flight. The sound is the vanilla elytra loop ({@link SoundEvents#ELYTRA_FLYING}, {@code item.elytra.flying}); DMZ has
 * no registered fly sound of its own and reuses this same vanilla event, so referencing it here needs no DMZ dependency.
 *
 * <p>Self-stops the moment the pilot dismounts, the pod is removed, travel ends (drops below the speed gate), or the
 * pilot leaves the SU space dimension, via {@link SpaceClientBusEvents#podTravelActive}. The single DMZ-owned type it
 * touches is {@link SpacePodEntity}, a mandatory-dependency common class (the same one {@code space/PodSpeedBoost} binds
 * against), so there is no version-fragile internal to guard here.
 */
public final class PodFlightSoundInstance extends AbstractTickableSoundInstance
{
    // DMZ FlightSoundInstance normalises speed against a 2.0 blocks/tick reference. Kept identical so the pod loop's
    // fade-in point and full-volume point line up with the flight loop the player already knows.
    private static final float SPEED_REFERENCE = 2.0F;

    private final LocalPlayer player;

    public PodFlightSoundInstance(LocalPlayer player)
    {
        super(SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, RandomSource.create());
        this.player = player;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.1F;
    }

    @Override
    public void tick()
    {
        SpacePodEntity pod = player.getVehicle() instanceof SpacePodEntity sp ? sp : null;
        if (player.isRemoved() || !SpaceClientBusEvents.podTravelActive(player, pod))
        {
            this.stop();
            return;
        }

        this.x = pod.getX();
        this.y = pod.getY();
        this.z = pod.getZ();

        // whole-motion speed (matches DMZ's getDeltaMovement().lengthSqr()), so vertical descent into a planet still
        // colours the loop the same way DMZ flight would.
        Vec3 motion = pod.getDeltaMovement();
        float speedSq = (float) motion.lengthSqr();
        float speed = speedSq >= 1.0E-7F ? (float) Math.sqrt(speedSq) : 0.0F;
        float ratio = speed / SPEED_REFERENCE;

        float targetVolume;
        float targetPitch;
        if (ratio < 0.33F)
        {
            targetVolume = 0.0F;
            targetPitch = 1.0F;
        }
        else if (ratio < 0.5F)
        {
            float t = (ratio - 0.33F) / 0.17F;
            targetVolume = Mth.lerp(t, 0.1F, 0.4F);
            targetPitch = 1.0F;
        }
        else if (ratio < 0.75F)
        {
            float t = (ratio - 0.5F) / 0.25F;
            targetVolume = Mth.lerp(t, 0.4F, 0.8F);
            targetPitch = Mth.lerp(t, 1.0F, 1.2F);
        }
        else
        {
            targetVolume = 1.0F;
            targetPitch = 1.2F + (ratio - 0.75F) * 0.5F;
        }

        // ease toward the target at 0.1 per tick, exactly as DMZ does, so speed changes do not pop.
        this.volume = Mth.lerp(0.1F, this.volume, targetVolume);
        this.pitch = Mth.lerp(0.1F, this.pitch, targetPitch);
        if (this.volume > 1.0F)
        {
            this.volume = 1.0F;
        }
    }
}
