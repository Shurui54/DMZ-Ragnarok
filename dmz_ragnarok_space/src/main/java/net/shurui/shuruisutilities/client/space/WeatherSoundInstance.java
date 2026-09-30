package net.shurui.shuruisutilities.client.space;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import net.shurui.shuruisutilities.space.PlanetWeather;

/**
 * Client-only looping weather ambience for a planet surface: the steady hiss of rain, or the low rush of wind in a
 * blizzard or dust storm. It follows the player and reads {@link SurfaceWeatherClient} every tick, easing its volume
 * with the weather intensity and self-stopping the instant the weather clears or the player leaves the surface, so it is
 * started once (like the pod flight loop) and never stacked.
 *
 * <p>Vanilla has one weather loop ({@link SoundEvents#WEATHER_RAIN}); a wet state plays it straight, while a blizzard or
 * dust storm plays it low and pitched down so it reads as wind rather than rain. Thunder is a separate one-shot fired by
 * {@link SurfaceWeatherClient} on each deterministic lightning flash, so it is not handled here.
 */
public final class WeatherSoundInstance extends AbstractTickableSoundInstance
{
    public WeatherSoundInstance()
    {
        super(SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, RandomSource.create());
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.relative = true;   // follows the listener, so it never localises to a point in the world
    }

    /** Whether this weather state has a looping ambience at all (clear/snow/ash/meteor are silent loops). */
    static boolean hasLoop(PlanetWeather.State state)
    {
        return state == PlanetWeather.State.RAIN || state == PlanetWeather.State.STORM
                || state == PlanetWeather.State.BLIZZARD || state == PlanetWeather.State.DUST;
    }

    @Override
    public void tick()
    {
        Minecraft mc = Minecraft.getInstance();
        PlanetWeather.State state = SurfaceWeatherClient.state();
        float intensity = SurfaceWeatherClient.intensity();
        if (mc.player == null || !SurfaceWeatherClient.onWeatheredSurface() || !hasLoop(state) || intensity <= 0.0F)
        {
            this.stop();
            return;
        }
        // rain and storm are the true rain loop; a blizzard or dust storm reuses it low and pitched down as wind.
        boolean wet = state == PlanetWeather.State.RAIN || state == PlanetWeather.State.STORM;
        float targetVolume = intensity * (wet ? 0.9F : 0.5F);
        float targetPitch = wet ? 1.0F : 0.7F;
        this.volume = Mth.lerp(0.1F, this.volume, targetVolume);
        this.pitch = targetPitch;
    }
}
