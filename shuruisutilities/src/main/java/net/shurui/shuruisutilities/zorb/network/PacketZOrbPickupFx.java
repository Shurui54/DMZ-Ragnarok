package net.shurui.shuruisutilities.zorb.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.zorb.ZOrbKind;

/**
 * Server -&gt; client (fixed id 107): a Z orb was just collected, so the collector can play the pickup popup
 * (a "+N" and, on completion, the combo toast). Carries the orb's entity id (to locate the burst), its kind, the
 * amount awarded, and the orb's index and the chain length (for the "(5/8)" readout).
 *
 * <p>Registered in Z1 so the wire id is fixed forever; the key starts SENDING it in Z2, and the client-side popup
 * ({@code ZOrbPickupFxClient}) is wired in Z3. Until then the client handler is a no-op.
 */
public class PacketZOrbPickupFx implements ISUPacket
{
    public int entityId;
    public byte kind;
    public int amount;
    public int index;
    public int length;

    public PacketZOrbPickupFx() {}

    public PacketZOrbPickupFx(int entityId, ZOrbKind kind, int amount, int index, int length)
    {
        this.entityId = entityId;
        this.kind = (kind == null ? ZOrbKind.TP : kind).id();
        this.amount = amount;
        this.index = index;
        this.length = length;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(entityId);
        buf.writeByte(kind);
        buf.writeVarInt(amount);
        buf.writeVarInt(index);
        buf.writeVarInt(length);
    }

    public static PacketZOrbPickupFx decode(FriendlyByteBuf buf)
    {
        PacketZOrbPickupFx p = new PacketZOrbPickupFx();
        p.entityId = buf.readVarInt();
        p.kind = buf.readByte();
        p.amount = buf.readVarInt();
        p.index = buf.readVarInt();
        p.length = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Hand off to the client-only popup manager. DistExecutor keeps the client class off a dedicated server.
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.zorb.ZOrbPickupFxClient.onPickup(
                        entityId, ZOrbKind.byId(kind), amount, index, length));
    }

    public static void handler(final PacketZOrbPickupFx message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
