package net.shurui.shuruisutilities.hoverbike.client;

import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;
import net.shurui.shuruisutilities.hoverbike.HoverbikeSounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// looping per-variant engine loop following a ridden bike. vol/pitch scale with horizontal speed (soft hum:
// idle ~0.45p/0.25v, full ~0.85p/0.5v, slightly higher sprinting). self-stops on removal / dismount.
public class HoverbikeSoundInstance extends AbstractTickableSoundInstance
{
    private final HoverbikeEntity bike;

    public HoverbikeSoundInstance(HoverbikeEntity bike)
    {
        super(HoverbikeSounds.REV[Mth.clamp(bike.getVariant(), 1, 4)].get(), SoundSource.NEUTRAL,
                Minecraft.getInstance().level != null ? Minecraft.getInstance().level.random
                        : net.minecraft.util.RandomSource.create());
        this.bike = bike;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.x = bike.getX();
        this.y = bike.getY();
        this.z = bike.getZ();
    }

    @Override
    public boolean canPlaySound()
    {
        return !this.bike.isSilent();
    }

    @Override
    public boolean canStartSilent()
    {
        return true;
    }

    @Override
    public void tick()
    {
        Minecraft mc = Minecraft.getInstance();
        // stop when the bike's gone or the player no longer rides it
        if (this.bike.isRemoved() || mc.player == null || !mc.player.isPassengerOfSameVehicle(this.bike)
                && mc.player.getVehicle() != this.bike)
        {
            this.stop();
            return;
        }

        this.x = this.bike.getX();
        this.y = this.bike.getY();
        this.z = this.bike.getZ();

        Vec3 m = this.bike.getDeltaMovement();
        double speed = Math.sqrt(m.x * m.x + m.z * m.z);
        // normalize against ~top speed (0.5-0.8 blocks/tick sprinting)
        float t = (float) Mth.clamp(speed / 0.7D, 0.0D, 1.0D);
        boolean sprinting = mc.player.isSprinting();

        // halved from the old 0.5->1.0 vol / 0.6->1.2 pitch so it's a soft hum that still scales with speed
        this.volume = 0.25F + 0.25F * t;
        this.pitch = 0.45F + 0.40F * t + (sprinting ? 0.05F : 0.0F);
    }
}
