package net.shurui.dev.sdu.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.shurui.dev.sdu.Config;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.registry.ModBlockEntities;
import net.shurui.dev.sdu.registry.ModBlocks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

// backing data for a BarrierBlock: the DMZ level+race gate for this specific block. defaults to
// Config.barrierDefaultLevel on placement; ops retune in-world by right-clicking. synced to the client so
// BarrierBER sees the current value and pick-block clone can stamp it.
public class BarrierBlockEntity extends BlockEntity {

    // cap on the connected-group flood fill so a pathological wall can't stall the server thread.
    private static final int PROPAGATE_CAP = 4096;

    private int requiredLevel = Config.barrierDefaultLevel;
    private String requiredRace = "";  // lowercase DMZ race id; empty = any race

    public BarrierBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEVEL_BARRIER.get(), pos, state);
    }

    public int getRequiredLevel() {
        return requiredLevel;
    }

    public void setRequiredLevel(int level) {
        this.requiredLevel = Math.max(0, level);
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public String getRequiredRace() {
        return requiredRace;
    }

    public void setRequiredRace(String race) {
        this.requiredRace = race == null ? "" : race.toLowerCase(java.util.Locale.ROOT);
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // set the level+race on the barrier at start and every barrier reachable by face-adjacent steps through
    // LEVEL_BARRIER. only loaded positions visited; capped at PROPAGATE_CAP so a huge wall can't freeze the
    // server thread. server-only.
    public static void propagateLevel(ServerLevel level, BlockPos start, int newLevel, String newRace) {
        if (level == null || start == null) {
            return;
        }
        if (!(level.getBlockState(start).getBlock() == ModBlocks.LEVEL_BARRIER.get())) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start.immutable());
        queue.add(start.immutable());
        while (!queue.isEmpty()) {
            if (visited.size() > PROPAGATE_CAP) {
                DmzNpc.LOGGER.debug("[{}] Level Barrier flood fill exceeded {} positions from {}; stopping.",
                        DmzNpc.MODID, PROPAGATE_CAP, start);
                break;
            }
            BlockPos cur = queue.poll();
            if (level.getBlockEntity(cur) instanceof BarrierBlockEntity be) {
                be.setRequiredLevel(newLevel);
                be.setRequiredRace(newRace);
            }
            for (Direction dir : Direction.values()) {
                BlockPos n = cur.relative(dir).immutable();
                if (visited.contains(n) || !level.isLoaded(n)) {
                    continue;
                }
                if (level.getBlockState(n).getBlock() == ModBlocks.LEVEL_BARRIER.get()) {
                    visited.add(n);
                    queue.add(n);
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("RequiredLevel", requiredLevel);
        tag.putString("RequiredRace", requiredRace);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("RequiredLevel")) {
            requiredLevel = tag.getInt("RequiredLevel");
        }
        if (tag.contains("RequiredRace")) {
            requiredRace = tag.getString("RequiredRace");
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        }
    }
}
