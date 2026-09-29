package net.shurui.dev.sdu.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.block.BarrierBlockEntity;

import java.util.function.Supplier;

/**
 * Client -> server. Persists an edited Level Barrier required DMZ level. Server-authoritative, mirroring
 * {@link SaveGravityChamberPacket}: re-gate (creative), reach check (&le;64 blocks), verify the target really
 * is a Level Barrier, then apply the level to the WHOLE face-adjacent connected group via
 * {@link BarrierBlockEntity#propagateLevel}. Any failure is ignored silently.
 */
public class SaveBarrierPacket {

    private final BlockPos pos;
    private final int level;
    private final String race;

    public SaveBarrierPacket(BlockPos pos, int level, String race) {
        this.pos = pos;
        this.level = level;
        this.race = race;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(level);
        buf.writeUtf(race == null ? "" : race);
    }

    public static SaveBarrierPacket decode(FriendlyByteBuf buf) {
        return new SaveBarrierPacket(buf.readBlockPos(), buf.readVarInt(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.isCreative()) {
                return;
            }
            ServerLevel serverLevel = player.serverLevel();
            if (!serverLevel.isLoaded(pos)
                    || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64 * 64) {
                return;
            }
            if (!(serverLevel.getBlockEntity(pos) instanceof BarrierBlockEntity)) {
                return;
            }
            // race: lowercase + trim; null/blank -> "" (any race). Never touch client-only DmzRaces here.
            String sanitizedRace = race == null ? "" : race.trim().toLowerCase(java.util.Locale.ROOT);
            BarrierBlockEntity.propagateLevel(serverLevel, pos, Math.max(0, level), sanitizedRace);
        });
        context.setPacketHandled(true);
    }
}
