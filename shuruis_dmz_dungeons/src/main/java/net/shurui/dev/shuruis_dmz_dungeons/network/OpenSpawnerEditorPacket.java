package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerConfig;

import java.util.function.Supplier;

// S2C: sent after an op right-clicks the spawner and passes the perm check. carries pos + current
// config so the editor opens prefilled.
public class OpenSpawnerEditorPacket {

    private final BlockPos pos;
    private final SpawnerConfig config;

    public OpenSpawnerEditorPacket(BlockPos pos, SpawnerConfig config) {
        this.pos = pos;
        this.config = config;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        config.encode(buf);
    }

    public static OpenSpawnerEditorPacket decode(FriendlyByteBuf buf) {
        return new OpenSpawnerEditorPacket(buf.readBlockPos(), SpawnerConfig.decode(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        net.shurui.dev.shuruis_dmz_dungeons.client.ClientPacketHandler.openSpawnerEditor(pos, config)));
        ctx.get().setPacketHandled(true);
    }
}
