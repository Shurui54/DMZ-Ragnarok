package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * server to client: the clash is over, here is how it went.
 *
 * <p>Sent even when the clash ended early (an opponent logged out, someone died, the two were separated), so the
 * overlay always has a reason to close. A client that only ever closed on its own timer would leave the minigame
 * drawn over a fight that had already finished.
 */
public class PacketClashEnd implements ISUPacket
{
    /** 0 lost, 1 won, 2 drew, 3 cancelled with no result. */
    public int outcome;
    public int ownScore;
    public int opponentScore;

    public PacketClashEnd() {}

    public PacketClashEnd(int outcome, int ownScore, int opponentScore)
    {
        this.outcome = outcome;
        this.ownScore = ownScore;
        this.opponentScore = opponentScore;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(outcome);
        buf.writeVarInt(Math.max(0, ownScore));
        buf.writeVarInt(Math.max(0, opponentScore));
    }

    public static PacketClashEnd decode(FriendlyByteBuf buf)
    {
        PacketClashEnd p = new PacketClashEnd();
        p.outcome = buf.readVarInt();
        p.ownScore = buf.readVarInt();
        p.opponentScore = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.combat.ClashRhythmOverlay.finish(outcome, ownScore,
                        opponentScore));
    }

    public static void handler(final PacketClashEnd message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
