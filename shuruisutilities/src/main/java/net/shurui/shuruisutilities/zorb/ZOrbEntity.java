package net.shurui.shuruisutilities.zorb;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import net.shurui.shuruisutilities.api.key.ZOrbHooks;

/**
 * A single Z orb: a plain, physics-free {@link Entity} that a chain of them makes up. It has NO AI, no gravity and
 * no collision, is never pushed, and is NEVER SAVED ({@link #shouldBeSaved()} is false), so it leaves zero world
 * data behind and nothing for a module-absence guard to reap. All its behaviour (chains, pickup, rewards, despawn)
 * lives in the Ragnarok Key through {@link ZOrbHooks}; on a keyless server the hook's default {@code entityTick}
 * discards it on its first server tick, so a stray or summoned orb never survives.
 *
 * <p>Its client-visible state is entirely synched: the kind, the rolled item stack (for the last orb of a chain),
 * the chain index, whether it is the currently collectible orb, the claimant, and the shell colour. The renderer
 * (Z3) reads only these.
 */
public class ZOrbEntity extends Entity
{
    private static final EntityDataAccessor<Byte> KIND =
            SynchedEntityData.defineId(ZOrbEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<ItemStack> STACK =
            SynchedEntityData.defineId(ZOrbEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> INDEX =
            SynchedEntityData.defineId(ZOrbEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ACTIVE =
            SynchedEntityData.defineId(ZOrbEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> CLAIMANT =
            SynchedEntityData.defineId(ZOrbEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> COLOUR =
            SynchedEntityData.defineId(ZOrbEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> LAST =
            SynchedEntityData.defineId(ZOrbEntity.class, EntityDataSerializers.BOOLEAN);

    public ZOrbEntity(EntityType<? extends ZOrbEntity> type, Level level)
    {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
        this.setNoGravity(true);
    }

    public ZOrbEntity(Level level, double x, double y, double z)
    {
        this(ZOrbEntities.Z_ORB.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData()
    {
        this.entityData.define(KIND, ZOrbKind.TP.id());
        this.entityData.define(STACK, ItemStack.EMPTY);
        this.entityData.define(INDEX, 0);
        this.entityData.define(ACTIVE, true);
        this.entityData.define(CLAIMANT, Optional.empty());
        this.entityData.define(COLOUR, ZOrbKind.TP.defaultColour());
        this.entityData.define(LAST, false);
    }

    @Override
    public void tick()
    {
        super.tick();
        // All real behaviour is the key's. Keyless, the default hook discards this on its first server tick.
        if (!this.level().isClientSide)
            ZOrbHooks.get().entityTick(this);
    }

    // --- synched accessors (server sets, client reads) ---

    public ZOrbKind getKind()
    {
        return ZOrbKind.byId(this.entityData.get(KIND));
    }

    public void setKind(ZOrbKind kind)
    {
        this.entityData.set(KIND, (kind == null ? ZOrbKind.TP : kind).id());
    }

    public ItemStack getStack()
    {
        return this.entityData.get(STACK);
    }

    public void setStack(ItemStack stack)
    {
        this.entityData.set(STACK, stack == null ? ItemStack.EMPTY : stack);
    }

    public int getIndex()
    {
        return this.entityData.get(INDEX);
    }

    public void setIndex(int index)
    {
        this.entityData.set(INDEX, index);
    }

    public boolean isActiveOrb()
    {
        return this.entityData.get(ACTIVE);
    }

    public void setActiveOrb(boolean active)
    {
        this.entityData.set(ACTIVE, active);
    }

    public Optional<UUID> getClaimant()
    {
        return this.entityData.get(CLAIMANT);
    }

    public void setClaimant(UUID uuid)
    {
        this.entityData.set(CLAIMANT, Optional.ofNullable(uuid));
    }

    public int getColour()
    {
        return this.entityData.get(COLOUR);
    }

    public void setColour(int colour)
    {
        this.entityData.set(COLOUR, colour);
    }

    public boolean isLast()
    {
        return this.entityData.get(LAST);
    }

    public void setLast(boolean last)
    {
        this.entityData.set(LAST, last);
    }

    // --- physics-free, never-saved entity plumbing ---

    @Override
    public boolean isPushable()
    {
        return false;
    }

    @Override
    public boolean isPickable()
    {
        return false;
    }

    @Override
    public boolean canBeCollidedWith()
    {
        return false;
    }

    @Override
    public boolean shouldBeSaved()
    {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag)
    {
        // Never saved.
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag)
    {
        // Never saved.
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket()
    {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
