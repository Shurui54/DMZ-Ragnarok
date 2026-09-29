package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client (fixed id 112): a race effect landing on the recipient (spin-out, squash, launch, frozen,
 * autopilot, blinded, flash). {@code effect} is a small enum-like id, {@code durationTicks} how long it lasts,
 * {@code fxFlags} the {@link net.shurui.shuruisutilities.racing.physics.RaceFx} bits to set on the bike layer.
 * R5+ applies it; the client never invents these.
 */
public class PacketRaceEffect implements ISUPacket
{
    // Pinned effect ids (the wire contract). Append only.
    public static final int SPIN_OUT = 0;
    public static final int SQUASH = 1;
    public static final int LAUNCH = 2;
    public static final int FROZEN = 3;
    public static final int AUTOPILOT = 4;
    public static final int BLIND = 5;
    public static final int FLASH = 6;
    public static final int CLEAR = 7;
    // R7 self powerups: a speed boost carrying its multiplier in {@link #magnitude}, and the aura effect windows.
    public static final int BOOST = 8;
    public static final int DESTROYER = 9;
    public static final int KAIOKEN_FLASH = 10;
    public static final int KAIOKEN_X20 = 11;
    public static final int AFTERIMAGE = 12;
    // R9 globals: BLIND (Solar Flare white overlay) reuses id 5; GRAVITY is the Gravity Crush red flash + shake.
    public static final int GRAVITY = 13;
    // R11: the Kiai shockwave ring drawn under the user's own bike (an expanding kiwave ring, client visual only).
    public static final int KIAI = 14;

    public int effect;
    public int durationTicks;
    public int fxFlags;
    /** A scalar the effect carries: the top-speed multiplier for {@link #BOOST} (else unused, default 0). */
    public float magnitude;

    public PacketRaceEffect() {}

    public PacketRaceEffect(int effect, int durationTicks, int fxFlags)
    {
        this(effect, durationTicks, fxFlags, 0.0F);
    }

    public PacketRaceEffect(int effect, int durationTicks, int fxFlags, float magnitude)
    {
        this.effect = effect;
        this.durationTicks = durationTicks;
        this.fxFlags = fxFlags;
        this.magnitude = magnitude;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(effect);
        buf.writeVarInt(durationTicks);
        buf.writeInt(fxFlags);
        buf.writeFloat(magnitude);
    }

    public static PacketRaceEffect decode(FriendlyByteBuf buf)
    {
        PacketRaceEffect p = new PacketRaceEffect();
        p.effect = buf.readVarInt();
        p.durationTicks = buf.readVarInt();
        p.fxFlags = buf.readInt();
        p.magnitude = buf.readFloat();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onEffect(this));
    }

    public static void handler(final PacketRaceEffect message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
