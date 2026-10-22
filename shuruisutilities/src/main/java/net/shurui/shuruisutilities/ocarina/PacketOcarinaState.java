package net.shurui.shuruisutilities.ocarina;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * server -> client: open the ocarina, or start a song on it.
 *
 * <p>The menu carries each song's skill level by ordinal (0 = not learned) and the seconds left on each song's
 * cooldown, because which songs a player knows is the server's answer and the client only draws it.
 */
public class PacketOcarinaState implements ISUPacket
{
    /** Wire values. Do not renumber. */
    public static final int MENU = 0;
    public static final int PLAY = 1;

    public int kind;
    /** Per song ordinal: the DMZ skill level, 0 when not learned. */
    public int[] levels;
    /** Per song ordinal: seconds until it can be played again. */
    public int[] cooldowns;
    /** Per song ordinal, four each: radius, power, secondary, duration at the player's level (the server's config). */
    public float[] stats;
    public int song;
    public long seed;

    public PacketOcarinaState() {}

    public PacketOcarinaState(int kind, int[] levels, int[] cooldowns, float[] stats, int song, long seed)
    {
        this.kind = kind;
        this.levels = levels == null ? new int[0] : levels;
        this.cooldowns = cooldowns == null ? new int[0] : cooldowns;
        this.stats = stats == null ? new float[0] : stats;
        this.song = song;
        this.seed = seed;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(kind);
        buf.writeVarIntArray(levels);
        buf.writeVarIntArray(cooldowns);
        buf.writeVarInt(stats.length);
        for (float f : stats)
            buf.writeFloat(f);
        buf.writeVarInt(song);
        buf.writeLong(seed);
    }

    public static PacketOcarinaState decode(FriendlyByteBuf buf)
    {
        int kind = buf.readVarInt();
        int[] levels = buf.readVarIntArray(64);
        int[] cooldowns = buf.readVarIntArray(64);
        float[] stats = new float[Math.min(256, Math.max(0, buf.readVarInt()))];
        for (int i = 0; i < stats.length; i++)
            stats[i] = buf.readFloat();
        return new PacketOcarinaState(kind, levels, cooldowns, stats, buf.readVarInt(), buf.readLong());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient);
    }

    private void applyClient()
    {
        if (kind == MENU)
            net.shurui.shuruisutilities.client.ocarina.OcarinaScreen.open(levels, cooldowns, stats);
        else
            net.shurui.shuruisutilities.client.ocarina.OcarinaScreen.startSong(song, seed);
    }

    public static void handler(final PacketOcarinaState message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
