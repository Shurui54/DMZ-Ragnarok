package net.shurui.dev.sdu.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.entity.ShenronDisplayEntity;
import net.shurui.dev.sdu.registry.ModBlocks;
import net.shurui.dev.sdu.shenron.ShenronShrineBlock;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineConfig;
import net.shurui.dev.sdu.shenron.ShrineSummon;
import net.shurui.dev.sdu.shenron.ShrineWish;

import java.util.List;
import java.util.function.Supplier;

/**
 * Client -> server. The player pressed "Summon Shenron" in the {@code ShrineScreen}. The server re-validates
 * the requirements, consumes them, spawns the display entity and (if a spawn succeeded) pushes the wish-select
 * GUI to the summoner only.
 */
public class ShrineSummonC2S {

    private final BlockPos pos;
    private final ShrineColor color;

    public ShrineSummonC2S(BlockPos pos, ShrineColor color) {
        this.pos = pos;
        this.color = color;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(color);
    }

    public static ShrineSummonC2S decode(FriendlyByteBuf buf) {
        return new ShrineSummonC2S(buf.readBlockPos(), buf.readEnum(ShrineColor.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            // Anti-spoof: the block at pos must actually be this shrine colour and within reach.
            if (!level.isLoaded(pos) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
                return;
            }
            BlockState state = level.getBlockState(pos);
            var expected = ModBlocks.SHRINES.get(color);
            if (expected == null || !(state.getBlock() instanceof ShenronShrineBlock shrine)
                    || shrine != expected.get()) {
                return;
            }

            ShenronDisplayEntity shenron = ShrineSummon.summon(player, pos, color);
            if (shenron == null) {
                player.displayClientMessage(Component.translatable("gui.dmz_ragnarok.npc.shrine.missing_items"), true);
                return;
            }
            List<ShrineWish> wishes = ShrineConfig.wishesFor(color);
            DmzNet.sendToPlayer(new OpenWishSelectPacket(shenron.getId(), color, wishes), player);
        });
        context.setPacketHandled(true);
    }
}
