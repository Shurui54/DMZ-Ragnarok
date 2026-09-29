package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// S2C reply to /rg dungeon highlight <radius>: every disguised spawner in range + how long to draw it.
public class HighlightSpawnersPacket {

    private final List<BlockPos> positions;
    private final int durationTicks;

    public HighlightSpawnersPacket(List<BlockPos> positions, int durationTicks) {
        this.positions = positions;
        this.durationTicks = durationTicks;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(positions.size());
        for (BlockPos p : positions) {
            buf.writeBlockPos(p);
        }
        buf.writeVarInt(durationTicks);
    }

    public static HighlightSpawnersPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<BlockPos> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readBlockPos());
        }
        return new HighlightSpawnersPacket(list, buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        net.shurui.dev.shuruis_dmz_dungeons.client.ClientPacketHandler.highlightSpawners(positions, durationTicks)));
        ctx.get().setPacketHandled(true);
    }
}
