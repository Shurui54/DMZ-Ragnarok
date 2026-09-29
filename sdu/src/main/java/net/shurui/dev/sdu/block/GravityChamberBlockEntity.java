package net.shurui.dev.sdu.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.sdu.Config;
import net.shurui.dev.sdu.registry.ModBlockEntities;

import com.dragonminez.server.util.GravityDeviceManager;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// backing data + area logic for the Gravity Chamber (TP-multiplier / shared-pool block). effect area is either
// an op-captured WorldEdit region (regionMin/regionMax in NBT) or, when unset, a radius cube around the block.
// loaded chambers register in the static ACTIVE set on onLoad and drop out on setRemoved; the TP handler
// iterates that instead of scanning block entities.
public class GravityChamberBlockEntity extends BlockEntity {

    private static final Set<GravityChamberBlockEntity> ACTIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // WorldEdit region override. both null = radius mode.
    private BlockPos regionMin;
    private BlockPos regionMax;

    // per-block effect config, defaulting from the global Config values so old/fresh blocks behave as before
    // until an op edits them. persisted in NBT and synced to the client for GUI seeding + pick-block clone.
    private double multiplier = Config.chamberMultiplier;
    private double shareFraction = Config.chamberShareFraction;
    private int radius = Config.chamberRadius;

    // extra gravity for players inside the area. 1.0 = off. when >1.0 (server-side) area() is registered as a
    // DMZ gravity zone via GravityDeviceManager and DMZ's tick applies the penalties (only if DMZ's
    // gravity.machineGravityEnabled is true).
    private double gravity = Config.chamberGravity;

    public GravityChamberBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GRAVITY_CHAMBER.get(), pos, state);
    }

    public static Set<GravityChamberBlockEntity> active() {
        return ACTIVE;
    }

    public boolean hasRegion() {
        return regionMin != null && regionMax != null;
    }

    public BlockPos getRegionMin() {
        return regionMin;
    }

    public BlockPos getRegionMax() {
        return regionMax;
    }

    // normalised to min/max corners.
    public void setRegion(BlockPos a, BlockPos b) {
        this.regionMin = new BlockPos(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.regionMax = new BlockPos(
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        setChanged();
    }

    // revert to radius mode.
    public void clearRegion() {
        this.regionMin = null;
        this.regionMax = null;
        setChanged();
    }

    public double getMultiplier() {
        return multiplier;
    }

    public double getShareFraction() {
        return shareFraction;
    }

    public int getRadius() {
        return radius;
    }

    public double getGravity() {
        return gravity;
    }

    public void setGravity(double v) {
        this.gravity = Math.max(1.0, v);
        markDirtyAndSync();
    }

    public void setMultiplier(double v) {
        this.multiplier = v;
        markDirtyAndSync();
    }

    public void setShareFraction(double v) {
        this.shareFraction = Math.max(0.0, Math.min(1.0, v));
        markDirtyAndSync();
    }

    public void setRadius(int v) {
        this.radius = Math.max(1, v);
        markDirtyAndSync();
    }

    // all four values at once (config-save packet), then persist + sync.
    public void setConfig(double multiplier, double shareFraction, int radius, double gravity) {
        this.multiplier = multiplier;
        this.shareFraction = Math.max(0.0, Math.min(1.0, shareFraction));
        this.radius = Math.max(1, radius);
        this.gravity = Math.max(1.0, gravity);
        markDirtyAndSync();
    }

    private void markDirtyAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            // bounds may have changed; re-register the DMZ zone.
            syncGravityZone();
        }
    }

    // reconcile the DMZ gravity zone with the current config (server-only; GravityDeviceManager is in DMZ's
    // server package). registers area() when gravity > 1.0, else unregisters.
    private void syncGravityZone() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (gravity > 1.0) {
            GravityDeviceManager.register(level, getBlockPos(), area(), gravity);
        } else {
            GravityDeviceManager.unregister(level, getBlockPos());
        }
    }

    // WE region if set, else the radius cube around the block.
    public AABB area() {
        if (hasRegion()) {
            return new AABB(
                    regionMin.getX(), regionMin.getY(), regionMin.getZ(),
                    regionMax.getX() + 1.0, regionMax.getY() + 1.0, regionMax.getZ() + 1.0);
        }
        int r = Math.max(1, radius);
        BlockPos p = getBlockPos();
        return new AABB(
                p.getX() - r, p.getY() - r, p.getZ() - r,
                p.getX() + r + 1.0, p.getY() + r + 1.0, p.getZ() + r + 1.0);
    }

    public boolean contains(Vec3 worldPos) {
        return area().contains(worldPos);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            ACTIVE.add(this);
            syncGravityZone();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        ACTIVE.remove(this);
        if (level != null && !level.isClientSide) {
            GravityDeviceManager.unregister(level, getBlockPos());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (hasRegion()) {
            tag.putLong("RegionMin", regionMin.asLong());
            tag.putLong("RegionMax", regionMax.asLong());
        }
        tag.putDouble("Multiplier", multiplier);
        tag.putDouble("ShareFraction", shareFraction);
        tag.putInt("Radius", radius);
        tag.putDouble("Gravity", gravity);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("RegionMin") && tag.contains("RegionMax")) {
            regionMin = BlockPos.of(tag.getLong("RegionMin"));
            regionMax = BlockPos.of(tag.getLong("RegionMax"));
        } else {
            regionMin = null;
            regionMax = null;
        }
        // absent key -> keep the global default (old blocks / freshly placed cubes).
        if (tag.contains("Multiplier")) {
            multiplier = tag.getDouble("Multiplier");
        }
        if (tag.contains("ShareFraction")) {
            shareFraction = Math.max(0.0, Math.min(1.0, tag.getDouble("ShareFraction")));
        }
        if (tag.contains("Radius")) {
            radius = Math.max(1, tag.getInt("Radius"));
        }
        if (tag.contains("Gravity")) {
            gravity = Math.max(1.0, tag.getDouble("Gravity"));
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
