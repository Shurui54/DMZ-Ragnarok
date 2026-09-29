package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Client -&gt; server (fixed id 115): a drift state change. {@code state} = started / released (pinned by the client
 * physics), {@code charge} = the client's mini-turbo tier hint at release. The server validates start/release
 * pairs against the tier durations before granting a mini-turbo (client-authoritative motion, server-authorised
 * boosts). Keyless the hook default is a no-op.
 */
public class PacketRaceDrift implements ISUPacket
{
    // Pinned drift states.
    public static final int START = 0;
    public static final int RELEASE = 1;
    public static final int CANCEL = 2;

    public int state;
    public int charge;

    public PacketRaceDrift() {}

    public PacketRaceDrift(int state, int charge)
    {
        this.state = state;
        this.charge = charge;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(state);
        buf.writeVarInt(charge);
    }

    public static PacketRaceDrift decode(FriendlyByteBuf buf)
    {
        return new PacketRaceDrift(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        RaceHooks.get().drift(player, state, charge);
    }

    public static void handler(final PacketRaceDrift message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
