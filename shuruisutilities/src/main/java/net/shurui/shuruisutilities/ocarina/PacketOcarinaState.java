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
 * <p>The menu carries the songs as a BITMASK of ordinals rather than a list, because which songs a player knows is
 * the server's answer and the client should not be able to invent a fourth one by mishandling a list.
 */
public class PacketOcarinaState implements ISUPacket
{
    /** Wire values. Do not renumber. */
    public static final int MENU = 0;
    public static final int PLAY = 1;

    public int kind;
    public int songMask;
    public int level;
    public int song;
    public long seed;

    public PacketOcarinaState() {}

    public PacketOcarinaState(int kind, int songMask, int level, int song, long seed)
    {
        this.kind = kind;
        this.songMask = songMask;
        this.level = level;
        this.song = song;
        this.seed = seed;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(kind);
        buf.writeVarInt(songMask);
        buf.writeVarInt(level);
        buf.writeVarInt(song);
        buf.writeLong(seed);
    }

    public static PacketOcarinaState decode(FriendlyByteBuf buf)
    {
        return new PacketOcarinaState(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readLong());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient);
    }

    private void applyClient()
    {
        if (kind == MENU)
            net.shurui.shuruisutilities.client.ocarina.OcarinaScreen.open(songMask, level);
        else
            net.shurui.shuruisutilities.client.ocarina.OcarinaScreen.startSong(song, seed);
    }

    public static void handler(final PacketOcarinaState message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
