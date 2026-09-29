package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.AdvancedSpawnerBlockEntity;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerConfig;

import java.util.function.Supplier;

// C2S: save an edited spawner config onto the BE. server-authoritative: op check, reach check, verify the
// chosen entity type actually exists, then write. never trusts the client for spawning.
public class SaveSpawnerPacket {

    private final BlockPos pos;
    private final SpawnerConfig config;

    public SaveSpawnerPacket(BlockPos pos, SpawnerConfig config) {
        this.pos = pos;
        this.config = config;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        config.encode(buf);
    }

    public static SaveSpawnerPacket decode(FriendlyByteBuf buf) {
        return new SaveSpawnerPacket(buf.readBlockPos(), SpawnerConfig.decode(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            // creative-only, mirrors the block interaction gate
            if (player == null || !player.isCreative() || !Config.canEdit(player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            // only accept edits within reach, and only to an actual spawner BE
            if (!level.isLoaded(pos) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64 * 64) {
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof AdvancedSpawnerBlockEntity be)) {
                return;
            }
            // bad entity id, tell them and bail rather than saving junk
            if (config.entityType() == null) {
                player.displayClientMessage(Component.translatable(
                        "message.dmz_ragnarok.dungeons.unknown_entity", config.entityTypeId), true);
                return;
            }
            be.setConfig(config);
        });
        context.setPacketHandled(true);
    }
}
