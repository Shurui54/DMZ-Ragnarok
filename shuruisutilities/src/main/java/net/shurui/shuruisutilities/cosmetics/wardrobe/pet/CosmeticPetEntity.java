package net.shurui.shuruisutilities.cosmetics.wardrobe.pet;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import net.shurui.shuruisutilities.compat.dmz.DmzFlightState;

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
 * A cosmetic pet: a GeckoLib creature summoned from the wardrobe that follows its owner closely, takes and deals no
 * damage, cannot be ridden, pushed, leashed or attacked, and never touches the save file.
 *
 * <h2>Movement is server-authoritative, unlike the mount</h2>
 * A pet has no rider, so there is no client-authoritative vehicle packet to respect: the SERVER drives the follow in
 * {@link #tick()} and every client just lerps toward the synced position, the ordinary tracked-entity shape. The
 * pet walks toward the owner when it drifts past {@link #FOLLOW_START}, settles when inside {@link #FOLLOW_STOP},
 * and TELEPORTS to the owner when it falls too far behind ({@link #TELEPORT_DISTANCE}) or gets stuck. A pet whose
 * owner has left this level removes itself; {@link CosmeticPetManager} re-spawns one in the new level.
 *
 * <h2>It is never written to disk</h2>
 * The {@link EntityType} is built {@code .noSave()}, so a chunk unload discards it rather than persisting it, which
 * is what stops a summoned pet ever becoming a stray creature after a restart. The in-memory
 * {@code CosmeticPetManager} map plus this non-persistence is the whole story.
 */
public class CosmeticPetEntity extends Entity implements GeoEntity
{
    private static final EntityDataAccessor<String> DATA_PET_ID =
            SynchedEntityData.defineId(CosmeticPetEntity.class, EntityDataSerializers.STRING);
    /** The owner, synced so a CLIENT can tell whose pet this is (the Magic aura is drawn on the owner's own pet). */
    private static final EntityDataAccessor<java.util.Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(CosmeticPetEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Beyond this it stops trailing and just walks toward the owner. Inside FOLLOW_STOP it settles and idles. */
    // Tightened 2026-09-22 so the pet stays close at speed: it starts walking as soon as the gap opens past two
    // blocks and only settles once it has closed to within one, so its resting distance is a block or so at heel
    // rather than drifting out toward the tether.
    private static final double FOLLOW_START = 2.0D;
    private static final double FOLLOW_STOP = 1.0D;

    /** Too far to chase on foot (or stuck this long): blink to the owner rather than trail forever. */
    // Lowered from 20 so a pet that does fall behind rejoins nearer the owner instead of after a long trail.
    private static final double TELEPORT_DISTANCE = 16.0D;
    private static final int STUCK_TELEPORT_TICKS = 60;

    private static final float MAX_YAW_STEP = 20.0F;
    private static final double WALK_GRAVITY = 0.08D;

    /**
     * Below this squared horizontal travel per tick the pet keeps its current heading rather than re-aiming, so tiny
     * residual motion when it settles does not make it jitter between directions. About 0.05 blocks/tick.
     */
    private static final double FACE_DEADZONE_SQR = 2.5E-3D;

    // Station-keeping offsets used when the owner is flying: the pet flies a little to the owner's side, slightly
    // behind, and a touch above their feet, so it reads as flying BESIDE them rather than trailing on the ground.
    private static final double FLIGHT_SIDE = 0.9D;
    private static final double FLIGHT_BACK = 0.4D;
    private static final double FLIGHT_RISE = 0.3D;

    // Speed tracking. The pet targets the owner's own per-tick speed plus a margin so it can close a gap, capped so
    // a teleporting or glitching owner cannot fling it, and eased so it accelerates and stops smoothly.
    // Retuned 2026-09-22 to keep the pet close at speed: a wider margin closes the gap faster, a higher cap lets it
    // keep up with fast (DMZ ki) flight instead of teleporting, and a larger smoothing step means the follow speed
    // reacts within a tick or two of a sprint or a launch rather than easing in over ~ten ticks.
    private static final double SPEED_MARGIN = 1.7D;
    private static final double SPEED_CAP = 2.4D;
    private static final double SPEED_SMOOTH = 0.5D;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Client-side interpolation state.
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    // Server bookkeeping.
    private boolean moving;
    private int stuckTicks;
    private double lastDistSqr = Double.MAX_VALUE;

    // Owner-speed tracking, so the pet moves as fast as the owner is actually moving rather than at a fixed trot.
    // The owner's per-tick displacement is sampled between consecutive pet ticks (independent of vanilla field
    // timing), then a smoothed follow speed is eased toward it so the pet does not jitter when the owner stops.
    private double lastOwnerX = Double.NaN;
    private double lastOwnerZ = Double.NaN;
    private double followSpeedSmoothed;

    @Nullable
    private UUID ownerUUID;

    // Resolved once from the synced id, so a bad id can never reach the render or the follow step.
    private CosmeticPetType spec;

    public CosmeticPetEntity(EntityType<? extends CosmeticPetEntity> type, Level level)
    {
        super(type, level);
        this.blocksBuilding = false;
        this.setMaxUpStep(1.0F);
    }

    @Override
    protected void defineSynchedData()
    {
        this.entityData.define(DATA_PET_ID, CosmeticPetType.defaultPetId());
        this.entityData.define(DATA_OWNER, java.util.Optional.empty());
    }

    public void setPetId(String id)
    {
        String safe = CosmeticPetType.known(id) ? id : CosmeticPetType.defaultPetId();
        this.entityData.set(DATA_PET_ID, safe);
        this.spec = CosmeticPetType.of(safe);
        this.setNoGravity(spec != null && spec.flying());
        this.refreshDimensions();
    }

    public String getPetId()
    {
        return this.entityData.get(DATA_PET_ID);
    }

    /** The resolved spec, always non-null: a bad synced id degrades to the default rather than throwing. */
    public CosmeticPetType spec()
    {
        if (this.spec == null)
        {
            this.spec = CosmeticPetType.of(getPetId());
            if (this.spec == null)
                this.spec = CosmeticPetType.of(CosmeticPetType.defaultPetId());
        }
        return this.spec;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key)
    {
        super.onSyncedDataUpdated(key);
        if (DATA_PET_ID.equals(key))
        {
            this.spec = CosmeticPetType.of(getPetId());
            this.setNoGravity(this.spec != null && this.spec.flying());
            this.refreshDimensions();
        }
    }

    // Per-pet collision box read from the resolved spec; refreshDimensions() is called whenever the id changes.
    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose)
    {
        CosmeticPetType s = spec();
        return net.minecraft.world.entity.EntityDimensions.scalable(s.boxWidth, s.boxHeight);
    }

    @Nullable
    public UUID getOwnerUUID()
    {
        // The server holds the field; a client only ever has the synced copy.
        return this.ownerUUID != null ? this.ownerUUID : this.entityData.get(DATA_OWNER).orElse(null);
    }

    public void setOwnerUUID(@Nullable UUID ownerUUID)
    {
        this.ownerUUID = ownerUUID;
        this.entityData.set(DATA_OWNER, java.util.Optional.ofNullable(ownerUUID));
    }

    // noSave() means these never run for a save, but a dedicated server still round-trips them when it spawns the
    // entity for a client, so the id is written here rather than only synced, keeping a just-spawned pet correct on
    // the very first tracking packet.
    @Override
    protected void addAdditionalSaveData(CompoundTag tag)
    {
        tag.putString("PetId", getPetId());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag)
    {
        if (tag.contains("PetId"))
            setPetId(tag.getString("PetId"));
    }

    /* -------------------------------------------------------------- interaction / collision / damage */

    @Override
    public InteractionResult interact(Player player, InteractionHand hand)
    {
        // A cosmetic pet cannot be leashed, mounted or otherwise interacted with: it just follows. PASS lets the
        // player's normal use (placing a block, using an item) fall through as if the pet were not there.
        return InteractionResult.PASS;
    }

    @Override
    public boolean isPickable()
    {
        // Not pickable, so a right-click or an attack aimed through it hits what is behind it, never the pet.
        return false;
    }

    @Override
    public boolean isPushable()
    {
        return false;
    }

    @Override
    public void push(Entity entity)
    {
        // No collision push in either direction: the pet never shoves the owner and is never shoved.
    }

    @Override
    public boolean canBeCollidedWith()
    {
        return false;
    }

    @Override
    public boolean isPushedByFluid()
    {
        return false;
    }

    // Takes no damage at all: a cosmetic pet is not a combat object. It is removed only by recall, an owner leaving
    // this level, logout, death, a hop, or a chunk unload.
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

    @Override
    public boolean isAttackable()
    {
        return false;
    }

    // A plain Entity has no leash slot at all (leashing lives on Mob), so nothing here can be leashed; the interact
    // override above also PASSes, so a lead is used on whatever is behind the pet rather than on the pet.

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source)
    {
        return false;
    }

    // A cosmetic pet must survive even the paths that bypass hurt(). Entity.kill() removes directly without going
    // through hurt() or isInvulnerableTo(), so /kill and every /kill @e sweep would otherwise delete it; make it a
    // no-op. The only thing that ever removes a pet is CosmeticPetManager, through discard().
    @Override
    public void kill()
    {
    }

    // Falling out of the world calls discard() directly, again bypassing invulnerability. A pet that follows into
    // the void must not vanish: ignore it. The follow step keeps it at the owner, and the owner leaving the level
    // retires it through the normal path instead.
    @Override
    public void onBelowWorld()
    {
    }

    /* -------------------------------------------------------------- silence / culling / render distance */

    // A cosmetic pet is silent. Every sound a plain Entity emits from its own movement (step sounds off the block it
    // walks on, the amethyst chime, swim and splash sounds, the fire-extinguish hiss) goes through Entity.playSound,
    // which is gated on isSilent(), so returning true here suppresses all of them at once. No deliberate pet sound
    // was ever added, so nothing is lost. This is why the owner heard footsteps trailing them: the pet was walking
    // and playing the ground's step sound every stride.
    @Override
    public boolean isSilent()
    {
        return true;
    }

    // The collision box (about 0.5 x 0.7) is deliberately tiny so the pet never blocks a doorway or a hit, but the
    // drawn chibi model is visually larger, and both the frustum check and the EntityCulling mod occlude from THIS
    // box. Left at the collision size, a pet reads as "blinking out" the moment its little box slips off the screen
    // edge or behind a block the taller model still pokes over. Inflate the culling box so it matches what is drawn.
    @Override
    public AABB getBoundingBoxForCulling()
    {
        return this.getBoundingBox().inflate(0.75D);
    }

    // shouldRenderAtSqrDistance scales the render cutoff by the collision box size, so this small box would stop the
    // pet drawing at roughly a third of a normal entity's range, well inside the owner's own view: it would vanish
    // as soon as the owner sprinted or flew a short way ahead. Use a fixed, generous range (a size-one entity's) so
    // a following pet is drawn as long as its chunk is loaded, scaled only by the client's entity-distance setting.
    @Override
    public boolean shouldRenderAtSqrDistance(double distSq)
    {
        double range = 64.0D * getViewScale();
        return distSq < range * range;
    }

    /* -------------------------------------------------------------- tick / follow */

    @Override
    public void tick()
    {
        // A pet whose position stopped being a number cannot be ticked sanely; remove it.
        if (!this.level().isClientSide
                && !(Double.isFinite(this.getX()) && Double.isFinite(this.getY()) && Double.isFinite(this.getZ())))
        {
            this.discard();
            return;
        }

        super.tick();

        // Keep last-tick rotation so the renderer can interpolate a smooth turn. A plain Entity (unlike LivingEntity)
        // never copies these itself, and GeoEntityRenderer draws a non-living entity from its interpolated yaw, so
        // without this the model would face a single fixed direction and never turn.
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();

        if (this.level().isClientSide)
        {
            tickLerp();
            return;
        }

        Player owner = this.ownerUUID == null ? null : this.level().getPlayerByUUID(this.ownerUUID);
        // Owner gone, removed, or now in another level: retire this pet. The manager re-spawns one in the owner's
        // new level on the dimension-change and login paths, so a stranded pet never lingers.
        if (owner == null || owner.isRemoved() || owner.isSpectator() || owner.level() != this.level())
        {
            this.discard();
            return;
        }

        followOwner(owner);
    }

    private void followOwner(Player owner)
    {
        CosmeticPetType s = spec();
        double dx = owner.getX() - this.getX();
        double dz = owner.getZ() - this.getZ();
        double horizSq = dx * dx + dz * dz;
        double distSq = horizSq + (owner.getY() - this.getY()) * (owner.getY() - this.getY());

        // The pet moves at least as fast as the owner is actually moving, plus a margin to close a gap, so a
        // sprinting, flying or mounted owner does not simply outrun it into a teleport. Sampled and eased here so
        // every path below shares the same speed.
        double speed = trackedSpeed(owner, s.followSpeed);

        // Flying comes from the owner's live state (DMZ ki flight, creative flight, elytra, a flying vehicle) or from
        // a pet that is a flier by type. Teleport stays the last resort for a genuine out-of-range gap.
        boolean flightMode = s.flying() || ownerIsFlying(owner);
        this.setNoGravity(flightMode);

        if (distSq > TELEPORT_DISTANCE * TELEPORT_DISTANCE)
        {
            teleportToOwner(owner);
            return;
        }

        if (flightMode)
        {
            flyBeside(owner, distSq, speed);
            return;
        }

        // Hysteresis: start walking once the gap opens past FOLLOW_START, stop once it closes inside FOLLOW_STOP, so
        // the pet does not stutter on the boundary.
        if (this.moving)
        {
            if (horizSq < FOLLOW_STOP * FOLLOW_STOP)
                this.moving = false;
        }
        else if (horizSq > FOLLOW_START * FOLLOW_START)
        {
            this.moving = true;
        }

        Vec3 motion = this.getDeltaMovement();
        double vx = motion.x;
        double vz = motion.z;

        if (this.moving && horizSq > 1.0E-4)
        {
            double dist = Math.sqrt(horizSq);
            double nx = dx / dist;
            double nz = dz / dist;
            vx = nx * speed;
            vz = nz * speed;
            // Face the way it is actually travelling, not the owner. Same smoothing as before, so it curves round.
            faceTravel(vx, vz);

            // Stuck detection: if it is trying to move but the gap is not closing (a wall, a fence), blink over.
            if (distSq >= this.lastDistSqr - 1.0E-3)
            {
                if (++this.stuckTicks > STUCK_TELEPORT_TICKS)
                {
                    teleportToOwner(owner);
                    return;
                }
            }
            else
            {
                this.stuckTicks = 0;
            }
        }
        else
        {
            vx *= 0.5D;
            vz *= 0.5D;
            this.stuckTicks = 0;
            // Idle: keep the last heading. A settled pet holds whichever way it was travelling rather than snapping
            // to the owner's look, which the owner found odd. The face deadzone below also stops residual drift
            // from re-aiming it.
        }
        this.lastDistSqr = distSq;

        double vy = this.onGround() ? 0.0D : motion.y - WALK_GRAVITY;

        this.setDeltaMovement(finiteOrZero(vx), finiteOrZero(vy), finiteOrZero(vz));
        this.move(MoverType.SELF, this.getDeltaMovement());
    }

    /**
     * Hold station a little to the owner's side, slightly behind, at roughly the owner's height, easing toward that
     * anchor at the shared tracked speed so the pet keeps up with a flying owner instead of trailing on the ground.
     */
    private void flyBeside(Player owner, double distSq, double speed)
    {
        float yaw = owner.getYRot();
        double rad = Math.toRadians(yaw);
        // Owner facing unit (Minecraft yaw 0 faces +Z) and the owner's right-hand unit.
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double rx = -Math.cos(rad);
        double rz = -Math.sin(rad);

        double tx = owner.getX() + rx * FLIGHT_SIDE - fx * FLIGHT_BACK;
        double tz = owner.getZ() + rz * FLIGHT_SIDE - fz * FLIGHT_BACK;
        double ty = owner.getY() + FLIGHT_RISE;

        double vx = Mth.clamp((tx - this.getX()) * 0.4D, -speed, speed);
        double vz = Mth.clamp((tz - this.getZ()) * 0.4D, -speed, speed);
        double vy = Mth.clamp((ty - this.getY()) * 0.3D, -speed, speed);

        this.setDeltaMovement(finiteOrZero(vx), finiteOrZero(vy), finiteOrZero(vz));
        this.move(MoverType.SELF, this.getDeltaMovement());

        // Face the direction of travel, smoothed like the ground turn. When it is holding station beside a
        // hovering owner its travel is below the deadzone, so it keeps its last heading rather than jittering.
        Vec3 flown = this.getDeltaMovement();
        faceTravel(flown.x, flown.z);
        this.moving = this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
        this.stuckTicks = 0;
        this.lastDistSqr = distSq;
    }

    /**
     * The speed to move this tick: the owner's own per-tick horizontal speed with a margin, floored at the pet's
     * idle trot, capped, and eased so it accelerates and settles without jitter when the owner stops.
     */
    private double trackedSpeed(Player owner, double base)
    {
        double ownerSpeed = 0.0D;
        if (!Double.isNaN(this.lastOwnerX))
        {
            double ox = owner.getX() - this.lastOwnerX;
            double oz = owner.getZ() - this.lastOwnerZ;
            ownerSpeed = Math.sqrt(ox * ox + oz * oz);
        }
        this.lastOwnerX = owner.getX();
        this.lastOwnerZ = owner.getZ();

        double target = Mth.clamp(ownerSpeed * SPEED_MARGIN, base, SPEED_CAP);
        this.followSpeedSmoothed += (target - this.followSpeedSmoothed) * SPEED_SMOOTH;
        if (this.followSpeedSmoothed < base)
            this.followSpeedSmoothed = base;
        return this.followSpeedSmoothed;
    }

    /**
     * Whether the owner is airborne right now, by any route the suite recognises: DMZ ki flight (asked through the
     * compat guard, never a raw DMZ import here), creative/ability flight, elytra gliding, or riding a vehicle or
     * mount that is off the ground.
     */
    private boolean ownerIsFlying(Player owner)
    {
        if (owner.getAbilities().flying || owner.isFallFlying())
            return true;
        Entity vehicle = owner.getVehicle();
        if (vehicle != null && !vehicle.onGround())
            return true;
        return owner instanceof ServerPlayer sp && DmzFlightState.isFlying(sp);
    }

    private void teleportToOwner(Player owner)
    {
        // Land just behind the owner, at their feet, so it appears at heel rather than in their face.
        float yaw = owner.getYRot();
        double rad = Math.toRadians(yaw);
        double bx = owner.getX() + Math.sin(rad) * 0.8D;
        double bz = owner.getZ() - Math.cos(rad) * 0.8D;
        this.moveTo(bx, owner.getY(), bz, yaw, 0.0F);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
        this.setDeltaMovement(Vec3.ZERO);
        this.moving = false;
        this.stuckTicks = 0;
        this.lastDistSqr = Double.MAX_VALUE;
    }

    /**
     * Turn toward the direction the pet is actually travelling this tick, one clamped yaw step. Below the deadzone
     * the motion is treated as noise and the heading is kept, so a settling or station-keeping pet does not jitter.
     */
    private void faceTravel(double vx, double vz)
    {
        if (vx * vx + vz * vz < FACE_DEADZONE_SQR)
            return;
        float want = (float) (Mth.atan2(vz, vx) * (180D / Math.PI)) - 90.0F;
        faceLike(want);
    }

    private void faceLike(float want)
    {
        float delta = Mth.clamp(Mth.wrapDegrees(want - this.getYRot()), -MAX_YAW_STEP, MAX_YAW_STEP);
        this.setYRot(this.getYRot() + delta);
        this.setXRot(0.0F);
    }

    private static double finiteOrZero(double v)
    {
        return Double.isFinite(v) ? v : 0.0D;
    }

    private void tickLerp()
    {
        if (this.lerpSteps <= 0)
            return;
        double x = this.getX() + (this.lerpX - this.getX()) / this.lerpSteps;
        double y = this.getY() + (this.lerpY - this.getY()) / this.lerpSteps;
        double z = this.getZ() + (this.lerpZ - this.getZ()) / this.lerpSteps;
        double dyaw = Mth.wrapDegrees(this.lerpYRot - this.getYRot());
        this.setYRot(this.getYRot() + (float) (dyaw / this.lerpSteps));
        this.setPos(x, y, z);
        this.setRot(this.getYRot(), this.getXRot());
        this.setYBodyRot(this.getYRot());
        this.setYHeadRot(this.getYRot());
        --this.lerpSteps;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport)
    {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yaw;
        this.lerpSteps = steps;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket()
    {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /* -------------------------------------------------------------- geckolib */

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "controller", 4, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state)
    {
        double sq = this.getDeltaMovement().horizontalDistanceSqr();
        AnimationController<T> controller = state.getController();
        // "walk" and "idle" are the two looping clips every converted chibi carries; the third, "interact", is a
        // one-shot not driven here. Referencing a clip a rig lacks would throw in GeckoLib, so both are known-present.
        if (sq > 0.0016D)
            controller.setAnimation(RawAnimation.begin().then("walk", Animation.LoopType.LOOP));
        else
            controller.setAnimation(RawAnimation.begin().then("idle", Animation.LoopType.LOOP));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return this.cache;
    }
}
