package net.shurui.shuruisutilities.racing.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.racing.RaceRegistries;

/**
 * A race Saibaman (Bob-omb): runs the track centreline and self-destructs near a racer for a spin-out. A thin
 * shell: physics-free and never saved, its pathing and detonation live in the Ragnarok Key through
 * {@link RaceHooks}. Keyless, the hook's default {@code saibamanTick} discards it on its first server tick.
 *
 * <p>It is a {@link GeoEntity} only so its renderer (R9) can reuse DragonMineZ's saga-saibaman geo / texture /
 * animation (the same art {@code SaibamanPetModel} uses); it carries no DMZ AI or state, and always plays the walk
 * animation because it is always charging down the track.
 */
public class RaceSaibamanEntity extends Entity implements GeoEntity
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public RaceSaibamanEntity(EntityType<? extends RaceSaibamanEntity> type, Level level)
    {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
        this.setNoGravity(true);
    }

    public RaceSaibamanEntity(Level level, double x, double y, double z)
    {
        this(RaceRegistries.RACE_SAIBAMAN.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData()
    {
    }

    @Override
    public void tick()
    {
        super.tick();
        if (!this.level().isClientSide)
            RaceHooks.get().saibamanTick(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "controller", 4, this::predicate));
    }

    // Always charging: DMZ's saga_saibaman animation ships a "walk" loop; the race saibaman runs the whole time.
    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state)
    {
        state.getController().setAnimation(RawAnimation.begin().then("walk", Animation.LoopType.LOOP));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
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
