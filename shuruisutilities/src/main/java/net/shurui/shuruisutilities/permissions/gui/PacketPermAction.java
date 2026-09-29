package net.shurui.shuruisutilities.permissions.gui;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: an edit from the permissions GUI. {@link #action} is one of {@code list},
 * {@code open}, {@code cycle}, {@code rank}, {@code prop}, {@code create}; {@link #a}/{@link #b} carry the
 * group name / node / value depending on the action. The server applies it and re-sends {@link PacketPermGui}.
 */
public class PacketPermAction implements ISUPacket
{
    public String action = "";
    public String a = "";
    public String b = "";
    public String c = "";

    public PacketPermAction() {}

    public PacketPermAction(String action, String a, String b)
    {
        this(action, a, b, "");
    }

    public PacketPermAction(String action, String a, String b, String c)
    {
        this.action = action;
        this.a = a == null ? "" : a;
        this.b = b == null ? "" : b;
        this.c = c == null ? "" : c;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(action);
        buf.writeUtf(a);
        buf.writeUtf(b);
        buf.writeUtf(c);
    }

    public static PacketPermAction decode(FriendlyByteBuf buf)
    {
        return new PacketPermAction(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        // The editor's server half lives in the Ragnarok Key; keyless the hook ignores the request.
        if (player != null)
            net.shurui.shuruisutilities.api.key.PermissionHooks.get().onPermAction(player, this);
    }

    public static void handler(final PacketPermAction message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
