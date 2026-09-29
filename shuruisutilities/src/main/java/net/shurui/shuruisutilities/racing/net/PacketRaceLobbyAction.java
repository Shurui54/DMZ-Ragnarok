package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Client -&gt; server (fixed id 116): a lobby button. {@code action} = join / leave / ready / pick a cosmetic bike
 * variant, {@code arg} = the variant or option index. The server owns the roster; it routes to the racing hook,
 * which validates lobby membership. Keyless the hook default is a no-op.
 */
public class PacketRaceLobbyAction implements ISUPacket
{
    // Pinned lobby actions.
    public static final int JOIN = 0;
    public static final int LEAVE = 1;
    public static final int READY = 2;
    public static final int PICK_VARIANT = 3;

    public int action;
    public int arg;

    public PacketRaceLobbyAction() {}

    public PacketRaceLobbyAction(int action, int arg)
    {
        this.action = action;
        this.arg = arg;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(action);
        buf.writeVarInt(arg);
    }

    public static PacketRaceLobbyAction decode(FriendlyByteBuf buf)
    {
        return new PacketRaceLobbyAction(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        RaceHooks.get().lobbyAction(player, action, arg);
    }

    public static void handler(final PacketRaceLobbyAction message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
