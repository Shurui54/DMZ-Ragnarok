package net.shurui.shuruisutilities.corrupted;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * server -> client: play a sound as a flat, non-positional UI cue on the receiving client, exactly like the
 * vanilla ender-dragon-death sound. carries the sound event id plus volume and pitch. sent to every online player
 * via {@link NetworkUtils#sendTo} regardless of dimension, so the cinematic voice lines reach everyone at
 * identical volume no matter where they or the altar are.
 *
 * <p>A directed {@code ClientboundSoundPacket} could not do this: it anchors the sound to a fixed world position,
 * so it attenuates and pans as the player moves. A UI sound instance has no position at all.
 */
public class PacketGlobalSound implements ISUPacket
{
    public ResourceLocation soundId;
    public float volume;
    public float pitch;

    public PacketGlobalSound() {}

    public PacketGlobalSound(SoundEvent sound, float volume, float pitch)
    {
        this.soundId = sound == null ? null : BuiltInRegistries.SOUND_EVENT.getKey(sound);
        this.volume = volume;
        this.pitch = pitch;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeResourceLocation(soundId == null ? new ResourceLocation("minecraft", "empty") : soundId);
        buf.writeFloat(volume);
        buf.writeFloat(pitch);
    }

    public static PacketGlobalSound decode(FriendlyByteBuf buf)
    {
        PacketGlobalSound p = new PacketGlobalSound();
        p.soundId = buf.readResourceLocation();
        p.volume = buf.readFloat();
        p.pitch = buf.readFloat();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only: the actual UI-sound playback lives in GlobalSoundClient so no client-only class is
        // classloaded on a dedicated server (this handle body only references it inside the CLIENT branch).
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> GlobalSoundClient.play(soundId, volume, pitch));
    }

    public static void handler(final PacketGlobalSound message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
