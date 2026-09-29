package net.shurui.shuruisutilities.racing.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.racing.RaceRegistries;

/**
 * A race ki-orb projectile: the Ki Blast (green shell), Hellzone Grenade (red shell), Spirit Bomb (blue shell) or
 * Ki Mine / Fake Ball marker (banana / fake box). A thin shell: plain, physics-free and never saved, its flight,
 * homing, edge bounce and hits all live in the Ragnarok Key through {@link RaceHooks}. Keyless, the hook's default
 * {@code orbTick} discards it on its first server tick.
 *
 * <p>It carries no DMZ technique or damage logic; the renderer (R8) borrows only DMZ's ki VISUALS. Its synched
 * {@link #KIND} and {@link #SIZE} drive the client look (colour by kind, radius by size).
 */
public class RaceKiOrbEntity extends Entity
{
    private static final EntityDataAccessor<Byte> KIND =
            SynchedEntityData.defineId(RaceKiOrbEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> SIZE =
            SynchedEntityData.defineId(RaceKiOrbEntity.class, EntityDataSerializers.FLOAT);

    public RaceKiOrbEntity(EntityType<? extends RaceKiOrbEntity> type, Level level)
    {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
        this.setNoGravity(true);
    }

    public RaceKiOrbEntity(Level level, double x, double y, double z)
    {
        this(RaceRegistries.RACE_KI_ORB.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData()
    {
        this.entityData.define(KIND, RaceOrbKind.GREEN.id());
        this.entityData.define(SIZE, 0.6F);
    }

    @Override
    public void tick()
    {
        super.tick();
        if (!this.level().isClientSide)
            RaceHooks.get().orbTick(this);
    }

    public RaceOrbKind getKind()
    {
        return RaceOrbKind.byId(this.entityData.get(KIND));
    }

    public void setKind(RaceOrbKind kind)
    {
        this.entityData.set(KIND, (kind == null ? RaceOrbKind.GREEN : kind).id());
    }

    public float getSize()
    {
        return this.entityData.get(SIZE);
    }

    public void setSize(float size)
    {
        this.entityData.set(SIZE, size);
    }

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
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag)
    {
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket()
    {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
