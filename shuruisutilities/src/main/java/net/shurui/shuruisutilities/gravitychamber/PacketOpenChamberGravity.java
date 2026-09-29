package net.shurui.shuruisutilities.gravitychamber;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client: open the guild gravity chamber's gravity + range GUI, seeded with the block's live values.
 */
public class PacketOpenChamberGravity implements ISUPacket
{
    public BlockPos pos;
    public double gravity;
    public int radius;

    public PacketOpenChamberGravity()
    {
    }

    public PacketOpenChamberGravity(BlockPos pos, double gravity, int radius)
    {
        this.pos = pos;
        this.gravity = gravity;
        this.radius = radius;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBlockPos(pos);
        buf.writeDouble(gravity);
        buf.writeVarInt(radius);
    }

    public static PacketOpenChamberGravity decode(FriendlyByteBuf buf)
    {
        PacketOpenChamberGravity p = new PacketOpenChamberGravity();
        p.pos = buf.readBlockPos();
        p.gravity = buf.readDouble();
        p.radius = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.client.gravitychamber.ChamberGravityScreen.open(pos, gravity, radius));
    }

    public static void handler(final PacketOpenChamberGravity message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
