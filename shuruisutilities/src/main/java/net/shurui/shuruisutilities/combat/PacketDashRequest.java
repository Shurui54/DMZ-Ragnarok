package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * client -> server: "I double tapped the dash key while aiming at this entity, in this mode".
 *
 * <p>The client sends what it BELIEVES it is aiming at and which mode its movement keys selected. It is not trusted on
 * either count: {@link DashService} re-checks range and re-derives the heading itself, so a client that names an entity
 * across the map, or one that is on cooldown, simply gets no dash. There is nothing here worth spoofing.
 */
public class PacketDashRequest implements ISUPacket
{
    public int targetEntityId;
    public int modeId;

    public PacketDashRequest() {}

    public PacketDashRequest(int targetEntityId, int modeId)
    {
        this.targetEntityId = targetEntityId;
        this.modeId = modeId;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(targetEntityId);
        buf.writeVarInt(modeId);
    }

    public static PacketDashRequest decode(FriendlyByteBuf buf)
    {
        return new PacketDashRequest(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        if (DashService.request(player, targetEntityId, DashMode.byId(modeId)))
        {
            PacketDashState.broadcast(player, DashService.routeOf(player), DashService.plannedDuration(player));
        }
    }

    public static void handler(final PacketDashRequest message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
