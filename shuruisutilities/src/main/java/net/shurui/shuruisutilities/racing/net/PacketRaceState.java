package net.shurui.shuruisutilities.racing.net;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client (fixed id 110): the recipient's per-tick race state (place, lap, timer, wrong-way) plus every
 * racer's xz for the minimap. Server authoritative: the client only draws it. R5 fills the HUD from this.
 */
public class PacketRaceState implements ISUPacket
{
    public int place;
    public int totalRacers;
    public int lapCurrent;
    public int lapTotal;
    public int timeTicks;
    /** Seconds-scale countdown to GO in TICKS (0 = racing). Drives the centre "3 2 1 GO!" and the rocket start. */
    public int countdown;
    public boolean wrongWay;
    public boolean finalLap;
    public boolean finished;
    /** Zeni carried (0..10): the HUD counter and the client's Zeni top-speed multiplier. */
    public int zeni;
    /** The dominant active self powerup for the HUD ({@link net.shurui.shuruisutilities.racing.physics.PowerupKind}
     *  ordinal, or {@code -1} for none): drives the Kaioken x20 timer ring and the small aura indicator. */
    public int selfEffect = -1;
    /** Ticks left on {@link #selfEffect} (for the x20 ring), 0 when none. */
    public int selfEffectTicks;
    /** Minimap dots: one {x, z} per racer, world coordinates. */
    public final List<float[]> racerDots = new ArrayList<>();
    /** The UUID of each racer, parallel to {@link #racerDots}, so the minimap can draw each as its skin face. */
    public final List<java.util.UUID> racerUuids = new ArrayList<>();
    /** The recipient's own racer UUID, so its face is drawn bigger; null if unknown. */
    public java.util.UUID selfId;

    public PacketRaceState() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(place);
        buf.writeVarInt(totalRacers);
        buf.writeVarInt(lapCurrent);
        buf.writeVarInt(lapTotal);
        buf.writeVarInt(timeTicks);
        buf.writeVarInt(countdown);
        buf.writeBoolean(wrongWay);
        buf.writeBoolean(finalLap);
        buf.writeBoolean(finished);
        buf.writeVarInt(zeni);
        buf.writeVarInt(selfEffect + 1); // +1 so -1 (none) stays non-negative on the wire
        buf.writeVarInt(selfEffectTicks);
        int n = racerDots.size();
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++)
        {
            buf.writeFloat(racerDots.get(i)[0]);
            buf.writeFloat(racerDots.get(i)[1]);
            java.util.UUID u = i < racerUuids.size() ? racerUuids.get(i) : new java.util.UUID(0L, 0L);
            buf.writeLong(u.getMostSignificantBits());
            buf.writeLong(u.getLeastSignificantBits());
        }
        buf.writeLong(selfId == null ? 0L : selfId.getMostSignificantBits());
        buf.writeLong(selfId == null ? 0L : selfId.getLeastSignificantBits());
    }

    public static PacketRaceState decode(FriendlyByteBuf buf)
    {
        PacketRaceState p = new PacketRaceState();
        p.place = buf.readVarInt();
        p.totalRacers = buf.readVarInt();
        p.lapCurrent = buf.readVarInt();
        p.lapTotal = buf.readVarInt();
        p.timeTicks = buf.readVarInt();
        p.countdown = buf.readVarInt();
        p.wrongWay = buf.readBoolean();
        p.finalLap = buf.readBoolean();
        p.finished = buf.readBoolean();
        p.zeni = buf.readVarInt();
        p.selfEffect = buf.readVarInt() - 1;
        p.selfEffectTicks = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            p.racerDots.add(new float[] { buf.readFloat(), buf.readFloat() });
            p.racerUuids.add(new java.util.UUID(buf.readLong(), buf.readLong()));
        }
        long sm = buf.readLong(), sl = buf.readLong();
        p.selfId = (sm == 0L && sl == 0L) ? null : new java.util.UUID(sm, sl);
        return p;

    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onState(this));
    }

    public static void handler(final PacketRaceState message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
