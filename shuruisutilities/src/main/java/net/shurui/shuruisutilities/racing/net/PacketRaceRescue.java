package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client (fixed id 122): a Lakitu-style rescue. The server has already moved the racer; this tells the
 * client to play the freeze / fade, snap to the target facing and hold for the pause. R5 plays it; the client
 * never decides a rescue. Ignored unless the racing feature is synced.
 */
public class PacketRaceRescue implements ISUPacket
{
    public double x;
    public double y;
    public double z;
    public float yaw;
    public int pauseTicks;

    public PacketRaceRescue() {}

    public PacketRaceRescue(double x, double y, double z, float yaw, int pauseTicks)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pauseTicks = pauseTicks;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yaw);
        buf.writeVarInt(pauseTicks);
    }

    public static PacketRaceRescue decode(FriendlyByteBuf buf)
    {
        PacketRaceRescue p = new PacketRaceRescue();
        p.x = buf.readDouble();
        p.y = buf.readDouble();
        p.z = buf.readDouble();
        p.yaw = buf.readFloat();
        p.pauseTicks = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onRescue(this));
    }

    public static void handler(final PacketRaceRescue message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
