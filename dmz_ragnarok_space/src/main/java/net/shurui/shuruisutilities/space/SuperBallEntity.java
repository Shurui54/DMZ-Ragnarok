package net.shurui.shuruisutilities.space;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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

/**
 * The collectible SUPER Dragon Ball as a real, full-size world entity (roughly 2.9 blocks across), rendered through
 * DragonMineZ's ball art at the 4x scale baked into {@code geo/block/dball_super4x.geo.json}. This is the entity form
 * of the {@code dragonminez:dball<star>_super} ball BLOCK: a solid, un-walk-through, clickable prop a player finds on a
 * Super Dragon Ball planet's surface.
 *
 * <h3>What this class owns (migration step 1)</h3>
 * The entity itself: its identity ({@link #getStar() star} 1..7, which maps 1:1 to the persisted
 * {@link SuperPlanetPositions#keyFor(int) super id}), its solid collision, its loss-proofing and its NBT round-trip.
 * It deliberately does NOT own the spawn path (the Destroyer God still drops a ball BLOCK today), the summon /
 * completeness check, the consumption, the item round-trip, or the radar. Those are later steps and are left as clean
 * seams: {@link #interact(Player, InteractionHand)} is a marked stub, and {@link #setStar(int)} / {@link #getSuperId()}
 * are the identity handles the spawn and consumption steps will call.
 *
 * <h3>Why a plain {@link Entity}, not a {@link net.minecraft.world.entity.Mob}</h3>
 * A ball has no AI and must never despawn, never be pushed, and never take knockback. {@code Mob} brings a despawn
 * timer and {@code isPushable() == true}; a bare {@code Entity} starts with neither, so it is the correct chassis.
 *
 * <h3>Solid collision (verified against vanilla {@code Entity} / {@code EntityGetter} / {@code Boat})</h3>
 * A moving entity only treats another as solid when {@code mover.canCollideWith(other)} passes, and that is
 * {@code other.canBeCollidedWith() && !isPassengerOfSameVehicle}. {@code Entity.canBeCollidedWith()} returns
 * {@code false} by default (Boat overrides it to true), and {@code EntityGetter.getEntityCollisions} builds the
 * hard-collision shape list through exactly that predicate. So {@link #canBeCollidedWith()} returning true is the one
 * load-bearing override; without it a player walks straight through. {@link #isPickable()} (default false) is
 * overridden true so the ball can be clicked. {@code isPushable()} is left at the plain-{@code Entity} default of
 * false, so nothing shoves it off its spot.
 */
public class SuperBallEntity extends Entity implements GeoEntity
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // star number 1..7. Synced so the client renderer can pick the matching per-star texture (dballblock_super1..7),
    // persisted so a reloaded ball keeps its identity. The star maps 1:1 to SuperPlanetPositions.keyFor(star).
    private static final EntityDataAccessor<Integer> STAR =
            SynchedEntityData.defineId(SuperBallEntity.class, EntityDataSerializers.INT);

    private static final String KEY_STAR = "su_super_star";
    // the persisted super-body id (SuperPlanetPositions.keyFor(star)). Derivable from the star, but stored too so the
    // identity model matches SuperPlanetGod / SuperPlanetData exactly and the summon step has it ready.
    private static final String KEY_PLANET = "su_super_planet";

    // DragonMineZ's ball geo animation and its single looping idle clip name (assets/dragonminez/animations/block/
    // dball.animation.json defines exactly "animation.dball1.idle"). Reused as-is so the entity floats like the block.
    private static final RawAnimation IDLE =
            RawAnimation.begin().then("animation.dball1.idle", Animation.LoopType.LOOP);

    public SuperBallEntity(EntityType<? extends SuperBallEntity> type, Level level)
    {
        super(type, level);
        // never fall, never drift: a collectible must hold its exact spot so it can never slide into the void.
        this.setNoGravity(true);
        this.noCulling = true;
    }

    @Override
    protected void defineSynchedData()
    {
        this.entityData.define(STAR, 1);
    }

    public int getStar()
    {
        return this.entityData.get(STAR);
    }

    /** Set the star (1..7); also refreshes the persisted super id. Called by the spawn step (a later migration task). */
    public void setStar(int star)
    {
        this.entityData.set(STAR, Mth.clamp(star, 1, SuperPlanetPositions.COUNT));
    }

    /** The persisted super-body id this ball belongs to, e.g. {@code susuper:1}. Derived from the current star. */
    public String getSuperId()
    {
        return SuperPlanetPositions.keyFor(getStar());
    }

    // THE load-bearing override: Entity.canBeCollidedWith() is false by default, so a moving player's
    // canCollideWith(this) fails and the ball is walk-through. Returning true (as Boat does) puts the ball into
    // EntityGetter.getEntityCollisions' hard-collision list, so players cannot pass through it.
    @Override
    public boolean canBeCollidedWith()
    {
        return true;
    }

    // needed so the ball can be clicked anywhere on its model (Entity.isPickable() is false by default).
    @Override
    public boolean isPickable()
    {
        return true;
    }

    // isPushable() is left at the plain-Entity default (false); a collectible must never be shoved off its spot.

    @Override
    public boolean isPushable()
    {
        return false;
    }

    // immune to every DamageSource path (mob attacks, fire, explosions, and the DamageSource-routed void/kill checks).
    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source)
    {
        return true;
    }

    // /kill routes through Entity.kill() -> remove(KILLED), NOT through hurt(), so hurt() alone would not stop it.
    // No-op here so a stray /kill @e cannot wipe an endgame collectible. The summon/consumption step (a later task)
    // removes the ball deliberately with discard(), which is intentionally NOT blocked.
    @Override
    public void kill()
    {
    }

    // Entity.onBelowWorld() discards directly (it does not go through hurt), so overriding hurt would not cover a ball
    // that somehow ends up under the world. With no gravity it can never fall there on its own; this is belt-and-braces
    // for a teleport/paste that drops it below the build floor. Do nothing rather than let it be destroyed.
    @Override
    protected void onBelowWorld()
    {
    }

    @Override
    public void tick()
    {
        super.tick();
        // hard-pin the ball: even if something imparts motion (fluid push, a mod), it never moves off its spot.
        if (!this.getDeltaMovement().equals(Vec3.ZERO))
        {
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * STUB for a later migration step. When the summon path is redirected off the ball BLOCK onto this entity, this is
     * where a click will run the seven-ball completeness check, trigger the Super Shenron summon, and hand the ball
     * item back to the player (the item round-trip). It does nothing yet and consumes no click, so today the ball is a
     * solid, clickable prop with no behaviour.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand)
    {
        return InteractionResult.PASS;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag)
    {
        if (tag.contains(KEY_STAR))
        {
            setStar(tag.getInt(KEY_STAR));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag)
    {
        tag.putInt(KEY_STAR, getStar());
        tag.putString(KEY_PLANET, getSuperId());
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state)
    {
        state.getController().setAnimation(IDLE);
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }
}
