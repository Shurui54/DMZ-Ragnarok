package net.shurui.shuruisutilities.events.network;

import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client (fixed id 130): the active-event state a client needs to draw the event banner and hub. The
 * body is one NBT compound carrying a list of active events (id, name, theme key, colour, remaining ms). Sent on
 * login after the key sync and whenever the active set changes; cleared on logout. The id is pinned here so the
 * wire never shifts; the client HUD that consumes it lands in a later batch (E11), so for now the client sink is
 * an inert no-op and the payload is simply parsed and dropped. See the design notes.
 */
public class PacketEventState implements ISUPacket
{
    public CompoundTag data = new CompoundTag();

    public PacketEventState() {}

    public PacketEventState(CompoundTag data)
    {
        this.data = data == null ? new CompoundTag() : data;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeNbt(data);
    }

    public static PacketEventState decode(FriendlyByteBuf buf)
    {
        CompoundTag tag = buf.readNbt();
        return new PacketEventState(tag == null ? new CompoundTag() : tag);
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // E0: no client HUD yet. The client sink is wired in E11 and gates on ClientGate.feature("events").
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> { });
    }

    public static void handler(final PacketEventState message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
