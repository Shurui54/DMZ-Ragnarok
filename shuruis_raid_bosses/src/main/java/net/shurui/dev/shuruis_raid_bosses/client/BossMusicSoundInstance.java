package net.shurui.dev.shuruis_raid_bosses.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The looping boss track, client only.
 *
 * <p>Non positional and relative, so it plays at a flat volume like a music track wherever the fighter stands,
 * on {@link SoundSource#MUSIC} so the player's own music volume slider and the {@code /bossmusic} toggle both
 * apply. It loops with no gap: when the sample ends it restarts, so a five minute track carries a fight of any
 * length. It self stops the moment {@link BossMusicClient} says this instance is no longer the current one,
 * which is how a stop packet, a disconnect, or a replacement track cut it cleanly instead of leaving a loop
 * running.
 */
public final class BossMusicSoundInstance extends AbstractTickableSoundInstance {

    public BossMusicSoundInstance(SoundEvent event) {
        super(event, SoundSource.MUSIC, RandomSource.create());
        this.looping = true;
        this.delay = 0;
        this.volume = 1.0F;
        this.pitch = 1.0F;
        // A music style, non positional sound: relative + no attenuation means position never changes its volume.
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
    }

    @Override
    public void tick() {
        // The client owns the lifetime: once this is not the loop BossMusicClient wants playing, end it. Covers a
        // stop packet, a track swap, the toggle going off, and leaving the world.
        if (!BossMusicClient.isCurrent(this)) {
            this.stop();
        }
    }
}
