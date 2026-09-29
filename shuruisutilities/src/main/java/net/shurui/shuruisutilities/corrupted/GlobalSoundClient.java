package net.shurui.shuruisutilities.corrupted;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-only playback for {@link PacketGlobalSound}. Kept in a separate class and only ever reached from inside a
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} branch, so it is never classloaded on a dedicated server.
 *
 * <p>{@link SimpleSoundInstance#forUI} builds a MASTER-source, non-attenuated, non-positional instance: the sound
 * plays at a flat volume with no distance falloff and no panning, exactly like the vanilla ender-dragon-death cue.
 */
@OnlyIn(Dist.CLIENT)
final class GlobalSoundClient
{
    private GlobalSoundClient() {}

    static void play(ResourceLocation soundId, float volume, float pitch)
    {
        if (soundId == null)
            return;
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(soundId);
        if (sound == null)
            return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
