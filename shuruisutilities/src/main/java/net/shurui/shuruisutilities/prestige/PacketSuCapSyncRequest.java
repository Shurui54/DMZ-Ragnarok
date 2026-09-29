package net.shurui.shuruisutilities.prestige;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

// client -> server: empty request to re-push this player's prestige stat-cap and TP-gain multipliers. sent
// when DMZ's stats screen opens (see SuCapSyncClient), so a dropped/late PacketSuCapMult can't leave the
// +stat buttons missing after a prestige; the client heals itself when the player looks at the screen.
// server handler just re-runs PrestigeManager.sendTpMult().
public class PacketSuCapSyncRequest implements ISUPacket
{
    public PacketSuCapSyncRequest() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        // No payload.
    }

    public static PacketSuCapSyncRequest decode(FriendlyByteBuf buf)
    {
        return new PacketSuCapSyncRequest();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer sender = context.getSender();
        if (sender != null)
            PrestigeManager.sendTpMult(sender);
    }

    public static void handler(final PacketSuCapSyncRequest message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
