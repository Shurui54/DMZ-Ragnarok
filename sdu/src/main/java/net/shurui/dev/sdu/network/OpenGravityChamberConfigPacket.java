package net.shurui.dev.sdu.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client. Opens the {@code GravityChamberConfigScreen} for an op-right-clicked Gravity Chamber,
 * seeded with the block's current per-block config (multiplier, share fraction, radius) and a flag for
 * whether a WorldEdit region override is set (so the screen can note that radius is inactive). Mirrors
 * {@link OpenShrineGuiPacket}: carries the {@link BlockPos} so the C2S save can re-target the same block.
 */
public class OpenGravityChamberConfigPacket {

    private final BlockPos pos;
    private final double multiplier;
    private final double shareFraction;
    private final int radius;
    private final double gravity;
    private final boolean hasRegion;

    public OpenGravityChamberConfigPacket(BlockPos pos, double multiplier, double shareFraction,
                                          int radius, double gravity, boolean hasRegion) {
        this.pos = pos;
        this.multiplier = multiplier;
        this.shareFraction = shareFraction;
        this.radius = radius;
        this.gravity = gravity;
        this.hasRegion = hasRegion;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(multiplier);
        buf.writeDouble(shareFraction);
        buf.writeVarInt(radius);
        buf.writeDouble(gravity);
        buf.writeBoolean(hasRegion);
    }

    public static OpenGravityChamberConfigPacket decode(FriendlyByteBuf buf) {
        return new OpenGravityChamberConfigPacket(
                buf.readBlockPos(), buf.readDouble(), buf.readDouble(), buf.readVarInt(),
                buf.readDouble(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.client.gui.block.GravityChamberConfigScreen.open(
                        pos, multiplier, shareFraction, radius, gravity, hasRegion)));
        context.setPacketHandled(true);
    }
}
