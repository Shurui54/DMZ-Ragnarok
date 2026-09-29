package net.shurui.shuruisutilities.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: opens one of the two hub menus with the exact set of entries the player is allowed to
 * see (computed server-side by {@link HubServer}). {@link #admin} selects the admin editor hub vs. the
 * player tool hub; {@link #which}/{@link #labelKeys} are parallel lists of entry ids and their label keys.
 */
public class PacketOpenHub implements ISUPacket
{
    public boolean admin;
    public List<String> which = new ArrayList<>();
    public List<String> labelKeys = new ArrayList<>();

    public PacketOpenHub() {}

    public PacketOpenHub(boolean admin, List<String> which, List<String> labelKeys)
    {
        this.admin = admin;
        this.which = which == null ? new ArrayList<>() : which;
        this.labelKeys = labelKeys == null ? new ArrayList<>() : labelKeys;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(admin);
        buf.writeVarInt(which.size());
        for (String s : which)
            buf.writeUtf(s);
        buf.writeVarInt(labelKeys.size());
        for (String s : labelKeys)
            buf.writeUtf(s);
    }

    public static PacketOpenHub decode(FriendlyByteBuf buf)
    {
        PacketOpenHub p = new PacketOpenHub();
        p.admin = buf.readBoolean();
        int wc = buf.readVarInt();
        for (int i = 0; i < wc; i++)
            p.which.add(buf.readUtf());
        int lc = buf.readVarInt();
        for (int i = 0; i < lc; i++)
            p.labelKeys.add(buf.readUtf());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.SUHubScreen.open(admin, which, labelKeys));
    }

    public static void handler(final PacketOpenHub message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
