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
 * A race item box: a Namek dragon ball at 1.5x that spins and bobs, giving a racer a powerup when driven through.
 * A thin shell: it is a plain, physics-free, never-saved {@link Entity}, and ALL its behaviour (respawn timing,
 * pickup, roulette) lives in the Ragnarok Key through {@link RaceHooks}. On a keyless server the hook's default
 * {@code itemBoxTick} discards it on its first server tick, so a stray or summoned box never survives.
 *
 * <p>The renderer (R6) reads the synched {@link #HIDDEN} flag (true during the 40-tick respawn) to draw the pop;
 * the box is placed by the item spawner or a track's item points, so it carries no other client-visible state in
 * R0.
 */
public class RaceItemBoxEntity extends Entity
{
    private static final EntityDataAccessor<Boolean> HIDDEN =
            SynchedEntityData.defineId(RaceItemBoxEntity.class, EntityDataSerializers.BOOLEAN);

    /** Client-only: the last {@link #isHidden()} value seen on tick, to detect the hidden -&gt; visible transition. */
    private boolean clientHiddenPrev;
    /** Client-only: the {@link #tickCount} at which the box last became visible, for the reappear pop animation (-1 = none). */
    private int clientPopStartAge = -1;

    public RaceItemBoxEntity(EntityType<? extends RaceItemBoxEntity> type, Level level)
    {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
        this.setNoGravity(true);
    }

    public RaceItemBoxEntity(Level level, double x, double y, double z)
    {
        this(RaceRegistries.RACE_ITEM_BOX.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData()
    {
        this.entityData.define(HIDDEN, false);
    }

    @Override
    public void tick()
    {
        super.tick();
        if (this.level().isClientSide)
        {
            // Detect the respawn pop: the tick the box turns from hidden back to visible arms the renderer's pop.
            boolean h = isHidden();
            if (this.clientHiddenPrev && !h)
                this.clientPopStartAge = this.tickCount;
            this.clientHiddenPrev = h;
        }
        else
        {
            RaceHooks.get().itemBoxTick(this);
        }
    }

    /** True while the box is respawning (hidden with a pop). Read by the renderer. */
    public boolean isHidden()
    {
        return this.entityData.get(HIDDEN);
    }

    public void setHidden(boolean hidden)
    {
        this.entityData.set(HIDDEN, hidden);
    }

    /**
     * The client-side reappear-pop progress in {@code [0,1]} at this render frame: 0 the instant the box pops back,
     * 1 once the pop is over (and whenever no pop is armed). The renderer eases a scale overshoot from this.
     *
     * @param partialTick the render partial tick, so the pop advances smoothly between ticks.
     * @param popTicks    how many ticks the pop lasts.
     */
    public float clientPopProgress(float partialTick, int popTicks)
    {
        if (this.clientPopStartAge < 0 || popTicks <= 0)
            return 1.0F;
        float elapsed = (this.tickCount - this.clientPopStartAge) + partialTick;
        if (elapsed >= popTicks)
            return 1.0F;
        return Math.max(0.0F, elapsed / popTicks);
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
