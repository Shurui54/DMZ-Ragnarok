package net.shurui.dev.sdu.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client. Opens the {@code BarrierConfigScreen} for an op-right-clicked Level Barrier, seeded
 * with the block's current required DMZ level. Mirrors {@link OpenGravityChamberConfigPacket}: carries the
 * {@link BlockPos} so the C2S save can re-target the same block (and propagate to its connected group).
 */
public class OpenBarrierConfigPacket {

    private final BlockPos pos;
    private final int requiredLevel;
    private final String requiredRace;

    public OpenBarrierConfigPacket(BlockPos pos, int requiredLevel, String requiredRace) {
        this.pos = pos;
        this.requiredLevel = requiredLevel;
        this.requiredRace = requiredRace;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(requiredLevel);
        buf.writeUtf(requiredRace == null ? "" : requiredRace);
    }

    public static OpenBarrierConfigPacket decode(FriendlyByteBuf buf) {
        return new OpenBarrierConfigPacket(buf.readBlockPos(), buf.readVarInt(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.client.gui.block.BarrierConfigScreen.open(pos, requiredLevel, requiredRace)));
        context.setPacketHandled(true);
    }
}
