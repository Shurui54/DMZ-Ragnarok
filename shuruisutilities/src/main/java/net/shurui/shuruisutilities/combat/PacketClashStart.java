package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * server to client: a melee clash has started, here is the seed and who you are fighting.
 *
 * <p>Only the seed travels, never the notes. Both sides build the chart from it through
 * {@link ClashRhythm#timeline(long)}, so the packet stays a handful of bytes however long the clash is and the client
 * cannot end up drawing a pattern the server did not score.
 */
public class PacketClashStart implements ISUPacket
{
    public long seed;
    public String opponent = "";

    public PacketClashStart() {}

    public PacketClashStart(long seed, String opponent)
    {
        this.seed = seed;
        this.opponent = opponent == null ? "" : opponent;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeLong(seed);
        buf.writeUtf(opponent);
    }

    public static PacketClashStart decode(FriendlyByteBuf buf)
    {
        PacketClashStart p = new PacketClashStart();
        p.seed = buf.readLong();
        p.opponent = buf.readUtf();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.combat.ClashRhythmOverlay.begin(seed, opponent));
    }

    public static void handler(final PacketClashStart message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
