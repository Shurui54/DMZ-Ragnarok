package net.shurui.shuruisutilities.senzu;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * client -> server: "the bean I am about to right click should be thrown to this player, not eaten."
 *
 * <p>It carries only the locked-on target's entity id, because the lock-on itself is a CLIENT structure
 * ({@code LockOnEvent.getLockedTarget()}) that the server has no copy of. Everything else, whether the sender is
 * really sneaking, really holding a bean, off cooldown, and whether that id is really a living player within
 * range, is re-checked in {@link SenzuThrow}: a client that can name any entity id will name one it should not
 * be able to reach.</p>
 *
 * <p>This packet does not itself throw anything. It records a short-lived intent, and the throw happens in the
 * server's own {@code RightClickItem} handler a moment later. See {@link SenzuThrow} for why it is split that
 * way; the short version is that cancelling the eat is only possible from inside that event.</p>
 */
public class PacketSenzuThrow implements ISUPacket
{
    private final int targetEntityId;

    public PacketSenzuThrow(int targetEntityId)
    {
        this.targetEntityId = targetEntityId;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(targetEntityId);
    }

    public static PacketSenzuThrow decode(FriendlyByteBuf buf)
    {
        return new PacketSenzuThrow(buf.readVarInt());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player != null)
            SenzuThrow.requestThrow(player, targetEntityId);
    }

    public static void handler(final PacketSenzuThrow message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
