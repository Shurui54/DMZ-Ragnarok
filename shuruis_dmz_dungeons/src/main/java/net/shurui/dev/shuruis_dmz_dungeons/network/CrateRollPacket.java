package net.shurui.dev.shuruis_dmz_dungeons.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Play the loot roll above a crate one client just opened.
 *
 * <p>PRESENTATION ONLY. The reward is already rolled, granted and banked server side before this is sent, so a
 * player who disconnects mid roll is still paid and one who never sees it has lost nothing.
 *
 * <p>Sent to the OPENER alone: a dungeon crate's loot is instanced per player, so showing the roll to bystanders
 * would show them someone else's reward.
 */
public class CrateRollPacket {

    // how many candidates are worth sending. The roll is a blur, so a long pool adds bytes rather than spectacle.
    public static final int MAX_CANDIDATES = 10;

    private final BlockPos pos;
    private final List<ItemStack> candidates;
    private final ItemStack result;
    private final int durationTicks;

    public CrateRollPacket(BlockPos pos, List<ItemStack> candidates, ItemStack result, int durationTicks) {
        this.pos = pos;
        this.candidates = candidates;
        this.result = result;
        this.durationTicks = durationTicks;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        int n = Math.min(MAX_CANDIDATES, candidates.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeItem(candidates.get(i));
        }
        buf.writeItem(result);
        buf.writeVarInt(durationTicks);
    }

    public static CrateRollPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<ItemStack> candidates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            candidates.add(buf.readItem());
        }
        ItemStack result = buf.readItem();
        int duration = buf.readVarInt();
        return new CrateRollPacket(pos, candidates, result, duration);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        net.shurui.dev.shuruis_dmz_dungeons.client.ClientPacketHandler.startCrateRoll(
                                pos, candidates, result, durationTicks)));
        ctx.get().setPacketHandled(true);
    }
}
