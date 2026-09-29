package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client. Plays the cosmetic dodge twist on the given player: their upper body turns 45
 * degrees to one side and returns. {@code left} is rolled once on the server so every viewer twists
 * the same way. Purely visual - the dodge itself was already resolved on the server.
 */
public class DodgeAnimPacket {

    private final int entityId;
    private final boolean left;

    public DodgeAnimPacket(int entityId, boolean left) {
        this.entityId = entityId;
        this.left = left;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(left);
    }

    public static DodgeAnimPacket decode(FriendlyByteBuf buf) {
        return new DodgeAnimPacket(buf.readVarInt(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        int id = entityId;
        boolean l = left;
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.dev.sdu.client.DodgeAnimator.trigger(id, l)));
        ctx.get().setPacketHandled(true);
    }
}
