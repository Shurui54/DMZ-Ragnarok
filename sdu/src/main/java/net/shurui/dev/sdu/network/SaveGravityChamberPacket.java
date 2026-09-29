package net.shurui.dev.sdu.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.block.GravityChamberBlockEntity;

import java.util.function.Supplier;

/**
 * Client -> server. Persists an edited Gravity Chamber effect config (multiplier, share fraction, radius) to
 * the {@link GravityChamberBlockEntity}. Server-authoritative, mirroring the dungeon spawner's save template:
 * re-gate (creative), reach check (&le;64 blocks), verify the target really is a Gravity Chamber, then apply
 * via the BE setters (which persist + push a block update). Any failure is ignored silently.
 */
public class SaveGravityChamberPacket {

    private final BlockPos pos;
    private final double multiplier;
    private final double shareFraction;
    private final int radius;
    private final double gravity;

    public SaveGravityChamberPacket(BlockPos pos, double multiplier, double shareFraction, int radius,
                                    double gravity) {
        this.pos = pos;
        this.multiplier = multiplier;
        this.shareFraction = shareFraction;
        this.radius = radius;
        this.gravity = gravity;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(multiplier);
        buf.writeDouble(shareFraction);
        buf.writeVarInt(radius);
        buf.writeDouble(gravity);
    }

    public static SaveGravityChamberPacket decode(FriendlyByteBuf buf) {
        return new SaveGravityChamberPacket(
                buf.readBlockPos(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readDouble());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.isCreative()) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!level.isLoaded(pos)
                    || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64 * 64) {
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof GravityChamberBlockEntity be)) {
                return;
            }
            be.setConfig(multiplier, shareFraction, radius, gravity);
        });
        context.setPacketHandled(true);
    }
}
