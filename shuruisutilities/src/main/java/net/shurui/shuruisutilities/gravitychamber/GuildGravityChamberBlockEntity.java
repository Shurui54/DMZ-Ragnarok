package net.shurui.shuruisutilities.gravitychamber;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.dragonminez.server.util.GravityDeviceManager;

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

/**
 * Backing data + area logic for the GUILD Gravity Chamber. It is a guild-owned training block: the placer's guild
 * owns it, only members of that owning guild (or the placer, when guildless) may configure it and start spars at it,
 * and it hosts non-lethal spars against a scaled copy of a chosen guild or party member.
 *
 * <p>Two numbers are player-editable through the gravity/range GUI: the gravity multiplier (1x..{@link
 * GuildGravityChamber#MAX_GRAVITY}x) and the effect radius in blocks (1..{@link GuildGravityChamber#MAX_RADIUS}). The
 * TP multiplier ({@value GuildGravityChamber#FIXED_TP_MULTIPLIER}x) and the guild share ratio ({@link
 * GuildGravityChamber#SHARE_RATIO}) are FIXED and never stored here.
 *
 * <p>The physical gravity is delegated wholesale to DragonMineZ: when the gravity is above 1x the effect {@link
 * #area()} is registered as a DMZ gravity zone through {@link GravityDeviceManager}, so DMZ's own per-tick gravity
 * pipeline (movement penalty, jump/fall feel, the client red overlay) applies inside it exactly as it does for
 * DragonMineZ's own gravity device, gated by DMZ's {@code gravity.machineGravityEnabled} config. This mirrors the SDU
 * gravity-chamber block, so there is one gravity mechanism across the suite, not two.
 *
 * <p>Loaded chambers register in the static {@link #ACTIVE} set on {@link #onLoad} and drop out on {@link
 * #setRemoved}, so the spar manager can find the chamber a player stands in without scanning every block entity.
 */
public class GuildGravityChamberBlockEntity extends BlockEntity
{
    private static final Set<GuildGravityChamberBlockEntity> ACTIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // gravity multiplier for players inside the area (1.0 = off, clamped to the guild-chamber maximum) and the cube
    // radius of the effect. persisted in NBT and synced to the client for GUI seeding.
    private double gravity = 1.0;
    private int radius = 8;

    // ownership, stamped once at placement. ownerGuildId is the guild that owns the chamber (empty when the placer
    // had no guild); ownerUuid is always the placer, so a guildless owner can still use their own chamber.
    private String ownerGuildId = "";
    private UUID ownerUuid;

    public GuildGravityChamberBlockEntity(BlockPos pos, BlockState state)
    {
        super(GuildGravityChamberBlocks.BLOCK_ENTITY.get(), pos, state);
    }

    public static Set<GuildGravityChamberBlockEntity> active()
    {
        return ACTIVE;
    }

    public double getGravity()
    {
        return gravity;
    }

    public int getRadius()
    {
        return radius;
    }

    public String getOwnerGuildId()
    {
        return ownerGuildId;
    }

    public UUID getOwnerUuid()
    {
        return ownerUuid;
    }

    /** Stamp ownership once, at placement. */
    public void setOwner(UUID owner, String guildId)
    {
        this.ownerUuid = owner;
        this.ownerGuildId = guildId == null ? "" : guildId;
        setChanged();
    }

    /** Apply an edited gravity + radius pair from the GUI, clamped to the guild-chamber limits, then persist + sync. */
    public void setConfig(double gravity, int radius)
    {
        this.gravity = Math.max(1.0, Math.min(GuildGravityChamber.MAX_GRAVITY, gravity));
        this.radius = Math.max(GuildGravityChamber.MIN_RADIUS, Math.min(GuildGravityChamber.MAX_RADIUS, radius));
        markDirtyAndSync();
    }

    private void markDirtyAndSync()
    {
        setChanged();
        if (level != null && !level.isClientSide)
        {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            syncGravityZone();
        }
    }

    // reconcile the DMZ gravity zone with the current config (server-only). registers area() when gravity > 1.0,
    // otherwise unregisters. every DMZ touch is wrapped so a DMZ drift degrades to "no zone", never a crash.
    private void syncGravityZone()
    {
        if (level == null || level.isClientSide)
        {
            return;
        }
        try
        {
            if (gravity > 1.0)
            {
                GravityDeviceManager.register(level, getBlockPos(), area(), gravity);
            }
            else
            {
                GravityDeviceManager.unregister(level, getBlockPos());
            }
        }
        catch (Throwable ignored)
        {
            // DMZ gravity API drift: the chamber still works as a spar block, just without the physical zone.
        }
    }

    /** The cube area of the effect, a {@link #radius}-block cube centred on the block. */
    public AABB area()
    {
        int r = Math.max(1, radius);
        BlockPos p = getBlockPos();
        return new AABB(
                p.getX() - r, p.getY() - r, p.getZ() - r,
                p.getX() + r + 1.0, p.getY() + r + 1.0, p.getZ() + r + 1.0);
    }

    public boolean contains(Vec3 worldPos)
    {
        return area().contains(worldPos);
    }

    @Override
    public void onLoad()
    {
        super.onLoad();
        if (level != null && !level.isClientSide)
        {
            ACTIVE.add(this);
            syncGravityZone();
        }
    }

    @Override
    public void setRemoved()
    {
        super.setRemoved();
        ACTIVE.remove(this);
        if (level != null && !level.isClientSide)
        {
            try
            {
                GravityDeviceManager.unregister(level, getBlockPos());
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        tag.putDouble("Gravity", gravity);
        tag.putInt("Radius", radius);
        tag.putString("OwnerGuild", ownerGuildId);
        if (ownerUuid != null)
        {
            tag.putUUID("OwnerUuid", ownerUuid);
        }
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        if (tag.contains("Gravity"))
        {
            gravity = Math.max(1.0, Math.min(GuildGravityChamber.MAX_GRAVITY, tag.getDouble("Gravity")));
        }
        if (tag.contains("Radius"))
        {
            radius = Math.max(GuildGravityChamber.MIN_RADIUS, Math.min(GuildGravityChamber.MAX_RADIUS, tag.getInt("Radius")));
        }
        ownerGuildId = tag.getString("OwnerGuild");
        ownerUuid = tag.hasUUID("OwnerUuid") ? tag.getUUID("OwnerUuid") : null;
    }

    @Override
    public CompoundTag getUpdateTag()
    {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag)
    {
        load(tag);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt)
    {
        CompoundTag tag = pkt.getTag();
        if (tag != null)
        {
            load(tag);
        }
    }
}
