package net.shurui.shuruisutilities.prestige;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

// server -> client: whether the receiving player may pick the HARD saga difficulty (prestige >= 1). The screen
// needs this to hide/lock HARD without knowing anything about prestige, so we send the single decided boolean
// rather than plumbing the prestige level into the saga screen. Sent whenever prestige/slot changes
// (piggybacked on sendTpMult: login, prestige, slot switch, respawn, dim change). The client handler hands it to
// sdu's HardSagaGate, the same gate the sdu screen and the sdu server-side packet mixin consult, so the offered
// option and the accepted option can never disagree.
public class PacketHardSagaGate implements ISUPacket
{
    public boolean mayUseHard;

    public PacketHardSagaGate() {}

    public PacketHardSagaGate(boolean mayUseHard)
    {
        this.mayUseHard = mayUseHard;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(mayUseHard);
    }

    public static PacketHardSagaGate decode(FriendlyByteBuf buf)
    {
        return new PacketHardSagaGate(buf.readBoolean());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only: push the local player's eligibility into sdu's neutral HardSagaGate. SU imports sdu (the
        // allowed direction); sdu never references any SU type. No-op if sdu isn't present in the container.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.saga.HardSagaGate.setClientMayUseHard(mayUseHard));
    }

    public static void handler(final PacketHardSagaGate message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
