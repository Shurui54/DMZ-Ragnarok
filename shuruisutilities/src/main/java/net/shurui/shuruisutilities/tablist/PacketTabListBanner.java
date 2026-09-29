package net.shurui.shuruisutilities.tablist;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the raw PNG bytes of the tab-list banner image that admins drop in
 * {@code <gamedir>/ShuruisUtilities/tablist/banner.png}. The client decodes it into a texture
 * ({@code TabListBannerClient}) and {@code MixinPlayerTabOverlay} draws it across the top of the tab list.
 * An empty payload clears the banner. Sent on join and on {@code /su reload}.
 */
public class PacketTabListBanner implements ISUPacket
{
    public byte[] png = new byte[0];

    public PacketTabListBanner() {}

    public PacketTabListBanner(byte[] png)
    {
        this.png = png == null ? new byte[0] : png;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeByteArray(png);
    }

    public static PacketTabListBanner decode(FriendlyByteBuf buf)
    {
        return new PacketTabListBanner(buf.readByteArray());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.tablist.client.TabListBannerClient.set(png));
    }

    public static void handler(final PacketTabListBanner message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
