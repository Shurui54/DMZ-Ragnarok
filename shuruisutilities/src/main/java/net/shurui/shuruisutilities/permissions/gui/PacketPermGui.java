package net.shurui.shuruisutilities.permissions.gui;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: opens/refreshes the DragonMineZ-styled permissions editor with a {@link PermGuiData}
 * snapshot. The client opens or updates the screen via DistExecutor.
 */
public class PacketPermGui implements ISUPacket
{
    public PermGuiData data = new PermGuiData();

    public PacketPermGui() {}

    public PacketPermGui(PermGuiData data)
    {
        this.data = data;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        data.write(buf);
    }

    public static PacketPermGui decode(FriendlyByteBuf buf)
    {
        return new PacketPermGui(PermGuiData.read(buf));
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.permissions.gui.client.PermScreen.openOrUpdate(data));
    }

    public static void handler(final PacketPermGui message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
