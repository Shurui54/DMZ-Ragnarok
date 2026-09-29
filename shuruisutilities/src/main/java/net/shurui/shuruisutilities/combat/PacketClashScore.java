package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * client to server: the score this player finished the clash minigame on.
 *
 * <p>The client judges its own presses, because it is the only side that knows when a key actually went down: routing
 * every press through the server would put a round trip inside a seven tick hit window and make the game feel late on
 * any real connection.
 *
 * <p>That does mean the number is self-reported, so the server does not take it on trust. It clamps to the chart's own
 * maximum (see {@link ClashRhythm#maxScore(long)}), ignores a score for a clash the player is not actually in, and
 * ignores a second report for a clash already scored. A modified client can still under-report to throw a fight, which
 * is not worth defending against, and cannot report more than a perfect run, which is the part that matters.
 */
public class PacketClashScore implements ISUPacket
{
    public int score;

    public PacketClashScore() {}

    public PacketClashScore(int score)
    {
        this.score = Math.max(0, score);
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(score);
    }

    public static PacketClashScore decode(FriendlyByteBuf buf)
    {
        PacketClashScore p = new PacketClashScore();
        p.score = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer sender = context.getSender();
        if (sender != null)
        {
            MeleeClashService.reportScore(sender, score);
        }
    }

    public static void handler(final PacketClashScore message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
