package net.shurui.shuruisutilities.cosmetics.wardrobe.mount;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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

/**
 * A cosmetic mount: a rideable GeckoLib entity summoned from the wardrobe, controlled by its rider, that takes and
 * deals no damage and never touches the save file.
 *
 * <h2>Movement is the Boat pattern, like the hoverbike</h2>
 * For a vehicle whose controlling passenger is the local player, vanilla makes the CLIENT authoritative and
 * auto-emits {@code ServerboundMoveVehiclePacket}, which the server applies. Anything the server simulates itself
 * is overwritten by that packet. So input flags are fed each client tick from the rider's keys
 * ({@code CosmeticMountClientEvents}), the controlling client reads them in {@link #tick()} and does the move, and
 * everyone else lerps toward the synced position. This is the same shape {@code HoverbikeEntity} documents; the
 * mount trims it to the essentials and swaps hover for either gravity (walking rigs) or altitude-hold (flying rigs)
 * chosen by {@link CosmeticMountType}.
 *
 * <h2>It is never written to disk</h2>
 * The {@link EntityType} is built {@code .noSave()}, so a chunk unload discards it rather than persisting it, which
 * is what stops a summoned mount ever becoming a free second vehicle after a restart. There is therefore no recall
 * SavedData to keep in step (unlike the persisted hoverbike): the in-memory {@code CosmeticMountManager} map plus
 * this non-persistence is the whole story. A riderless mount also despawns after {@link #RIDERLESS_DESPAWN_TICKS}.
 */
public class CosmeticMountEntity extends Entity implements GeoEntity
{
    private static final EntityDataAccessor<String> DATA_MOUNT_ID =
            SynchedEntityData.defineId(CosmeticMountEntity.class, EntityDataSerializers.STRING);

    // The drive speed and sprint multiplier of the vehicle this mount was summoned in place of, so a replacing
    // mount moves at the same speed as the hoverbike (or other vehicle) it stood in for rather than its own per-rig
    // number. Synced because the drive step runs on the RIDER's client and the vehicle configs (ConfigHoverbikes)
    // are COMMON, server-side only: without the sync a dedicated-server client would drive on stale local defaults.
    // 0 speed means the mount was summoned on its own (via /cosmetic), so it keeps its own configured speed.
    private static final EntityDataAccessor<Float> DATA_VEHICLE_SPEED =
            SynchedEntityData.defineId(CosmeticMountEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPRINT_MULT =
            SynchedEntityData.defineId(CosmeticMountEntity.class, EntityDataSerializers.FLOAT);

    /** Default sprint multiplier when no vehicle speed was inherited (a plain /cosmetic summon). */
    private static final float DEFAULT_SPRINT_MULT = 1.5F;

    /** A mount nobody has ridden for this long removes itself, so a dismounted mount does not litter the world. */
    private static final int RIDERLESS_DESPAWN_TICKS = 200;

    private static final float MAX_YAW_STEP = 12.0F;
    private static final double ACCEL_LERP = 0.30D;
    private static final double COAST_DECAY = 0.55D;
    private static final float HEAD_YAW_CLAMP = 120.0F;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Local-rider input flags, fed from the client tick handler while this is the controlled vehicle.
    private boolean inputUp;
    private boolean inputDown;
    private boolean inputLeft;
    private boolean inputRight;
    private boolean inputSprint;
    private boolean inputJump;
    private boolean inputDescend;
    private boolean jumpLatched;

    // Client-side interpolation state.
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    // Server bookkeeping.
    private int riderlessTicks;

    // Server-side sound throttles. Ambient plays on a randomised interval; the move sound honours a cooldown close
    // to the source pack's 1.5 s so a step or flap does not machine-gun while driving.
    private int ambientDelay = 60 + this.random.nextInt(80);
    private int moveSoundCooldown;

    @Nullable
    private UUID ownerUUID;

    // Resolved once from the synced id, so a bad id can never reach the render or the drive step.
    private CosmeticMountType spec;

    public CosmeticMountEntity(EntityType<? extends CosmeticMountEntity> type, Level level)
    {
        super(type, level);
        this.blocksBuilding = true;
        this.setMaxUpStep(1.0F);
    }

    @Override
    protected void defineSynchedData()
    {
        this.entityData.define(DATA_MOUNT_ID, CosmeticMountType.defaultMountId());
        this.entityData.define(DATA_VEHICLE_SPEED, 0.0F);
        this.entityData.define(DATA_SPRINT_MULT, DEFAULT_SPRINT_MULT);
    }

    /**
     * The drive speed (blocks/tick) and sprint multiplier of the vehicle this mount replaced, set on the server at
     * summon and synced to the rider. A speed of 0 (the default, and the value a plain {@code /cosmetic} summon
     * leaves) means "no vehicle to match": {@link #speedResolved()} then uses the mount's own configured speed.
     */
    public void setVehicleMovement(float speed, float sprintMultiplier)
    {
        this.entityData.set(DATA_VEHICLE_SPEED, Math.max(0.0F, speed));
        this.entityData.set(DATA_SPRINT_MULT, sprintMultiplier > 1.0F ? sprintMultiplier : DEFAULT_SPRINT_MULT);
    }

    public void setMountId(String id)
    {
        String safe = CosmeticMountType.known(id) ? id : CosmeticMountType.defaultMountId();
        this.entityData.set(DATA_MOUNT_ID, safe);
        this.spec = CosmeticMountType.of(safe);
        this.refreshDimensions();
    }

    public String getMountId()
    {
        return this.entityData.get(DATA_MOUNT_ID);
    }

    /** The resolved spec, always non-null: a bad synced id degrades to the default rather than throwing. */
    public CosmeticMountType spec()
    {
        if (this.spec == null)
        {
            this.spec = CosmeticMountType.of(getMountId());
            if (this.spec == null)
                this.spec = CosmeticMountType.of(CosmeticMountType.defaultMountId());
        }
        return this.spec;
    }

    /**
     * The editable movement sub-record for this mount, read off the SYNCED catalogue by the mount id, or null when
     * the def carries none. The catalogue is present on both the server and every client (the catalogue packet), so
     * both ends resolve the same movement without any new entity data. Null means fall back to the code table.
     */
    @Nullable
    private net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticMount mountDef()
    {
        try
        {
            net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef def =
                    net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog.get(getMountId());
            return def == null ? null : def.mount;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** Whether this mount flies: the operator-edited sub-record wins, falling back to the seeded code table. */
    public boolean flyingResolved()
    {
        net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticMount m = mountDef();
        return m != null ? m.flying : spec().flying();
    }

    /**
     * Drive speed in blocks per tick, resolved by a three-step precedence:
     * <ol>
     * <li>An operator who DELIBERATELY edited this mount's speed in the wardrobe editor wins: a considered choice
     * must not be silently overwritten by the vehicle's speed. "Deliberate" is the stored speed differing from the
     * value {@link CosmeticMountType} seeds a fresh catalogue with (see {@link #operatorSpeedOverride()}).</li>
     * <li>Otherwise the speed of the VEHICLE this mount was summoned in place of (the equipped hoverbike's config
     * speed), synced at summon. This is what makes a replacing mount move at the same speed as its hoverbike.</li>
     * <li>Otherwise the mount's own seeded speed, for a plain {@code /cosmetic} summon that replaced nothing.</li>
     * </ol>
     */
    public double speedResolved()
    {
        double override = operatorSpeedOverride();
        if (override > 0.0D)
            return override;
        float vehicle = this.entityData.get(DATA_VEHICLE_SPEED);
        if (vehicle > 0.0F)
            return vehicle;
        return spec().speed;
    }

    /**
     * The mount's editor speed when an operator has deliberately changed it, else a negative sentinel. Deliberate is
     * detected by the stored speed differing from the value {@link CosmeticMountType} seeds a fresh catalogue with,
     * so an untouched (seeded or absent) record does not count as an override and lets the replaced vehicle's speed
     * win. The one blind spot, an operator setting the editor speed to exactly the seeded value, is harmless: it asks
     * for the same number the seed already holds.
     */
    private double operatorSpeedOverride()
    {
        net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticMount m = mountDef();
        if (m == null || !(m.speed > 0.0D))
            return -1.0D;
        CosmeticMountType t = spec();
        if (t != null && Math.abs(m.speed - t.speed) < 1.0E-4)
            return -1.0D;
        return m.speed;
    }

    /** Sprint multiplier: the replaced vehicle's, when one was inherited, else the mount's own default. */
    private double sprintMultiplierResolved()
    {
        float m = this.entityData.get(DATA_SPRINT_MULT);
        return m > 1.0F ? m : DEFAULT_SPRINT_MULT;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key)
    {
        super.onSyncedDataUpdated(key);
        if (DATA_MOUNT_ID.equals(key))
        {
            this.spec = CosmeticMountType.of(getMountId());
            this.refreshDimensions();
        }
    }

    // Per-mount collision box, so a broomstick and a ghost ship do not share one size. Read from the resolved
    // spec; refreshDimensions() is called whenever the id changes on either side.
    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose)
    {
        CosmeticMountType s = spec();
        return net.minecraft.world.entity.EntityDimensions.scalable(s.boxWidth, s.boxHeight);
    }

    @Nullable
    public UUID getOwnerUUID()
    {
        return this.ownerUUID;
    }

    public void setOwnerUUID(@Nullable UUID ownerUUID)
    {
        this.ownerUUID = ownerUUID;
    }

    // noSave() means these never run for a save, but a dedicated server still round-trips them when it spawns the
    // entity for a client, so the id is written here rather than only synced, keeping a just-spawned mount correct
    // on the very first tracking packet.
    @Override
    protected void addAdditionalSaveData(CompoundTag tag)
    {
        tag.putString("MountId", getMountId());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag)
    {
        if (tag.contains("MountId"))
            setMountId(tag.getString("MountId"));
    }

    /* -------------------------------------------------------------- riding */

    @Override
    @Nullable
    public LivingEntity getControllingPassenger()
    {
        return getFirstPassenger() instanceof Player p ? p : null;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger)
    {
        return getPassengers().isEmpty();
    }

    public void setInput(boolean up, boolean down, boolean left, boolean right, boolean sprint, boolean jump,
            boolean descend)
    {
        this.inputUp = up;
        this.inputDown = down;
        this.inputLeft = left;
        this.inputRight = right;
        this.inputSprint = sprint;
        this.inputJump = jump;
        this.inputDescend = descend;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand)
    {
        if (player.isSecondaryUseActive())
            return InteractionResult.PASS;
        // Only the owner may ride a summoned mount, so a mount cannot be hijacked while its owner is briefly off it.
        if (this.ownerUUID != null && !this.ownerUUID.equals(player.getUUID()))
            return InteractionResult.PASS;
        if (!this.level().isClientSide)
            return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move)
    {
        if (!hasPassenger(passenger))
            return;
        double y = this.getY() + spec().seatHeight + passenger.getMyRidingOffset();
        move.accept(passenger, this.getX(), y, this.getZ());
        if (passenger instanceof LivingEntity living)
        {
            living.setYBodyRot(this.getYRot());
            float delta = Mth.wrapDegrees(living.getYHeadRot() - this.getYRot());
            float clamped = Mth.clamp(delta, -HEAD_YAW_CLAMP, HEAD_YAW_CLAMP);
            living.yRotO += clamped - delta;
            living.setYHeadRot(living.getYHeadRot() + clamped - delta);
        }
    }

    @Override
    public double getPassengersRidingOffset()
    {
        return spec().seatHeight;
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity rider)
    {
        Vec3 side = getCollisionHorizontalEscapeVector(this.getBbWidth() * Mth.SQRT_OF_TWO, rider.getBbWidth(),
                this.getYRot());
        return new Vec3(this.getX() + side.x, this.getBoundingBox().maxY, this.getZ() + side.z);
    }

    /* -------------------------------------------------------------- collision / damage */

    @Override
    public boolean isPickable()
    {
        return !this.isRemoved();
    }

    @Override
    public boolean isPushable()
    {
        return false;
    }

    @Override
    public boolean canBeCollidedWith()
    {
        return true;
    }

    @Override
    public boolean isNoGravity()
    {
        return false;
    }

    @Override
    public boolean isPushedByFluid()
    {
        return false;
    }

    // Takes no damage at all: a cosmetic mount is not a combat object, so a stray hit or an area effect must never
    // dismount or destroy it. It is removed only by recall, dismount-timeout, logout, a hop or /kill (which calls
    // discard() directly, bypassing hurt()).
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

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source)
    {
        return false;
    }

    /* -------------------------------------------------------------- tick / movement */

    @Override
    public void tick()
    {
        // A mount whose position stopped being a number cannot be ticked sanely; put any rider off and remove it.
        if (!this.level().isClientSide
                && !(Double.isFinite(this.getX()) && Double.isFinite(this.getY()) && Double.isFinite(this.getZ())))
        {
            ejectPassengers();
            this.discard();
            return;
        }

        super.tick();

        if (!this.level().isClientSide)
        {
            // Despawn a mount nobody is riding, so a dismount without a recall does not leave it standing forever.
            if (this.getPassengers().isEmpty())
            {
                if (++this.riderlessTicks > RIDERLESS_DESPAWN_TICKS)
                {
                    this.discard();
                    return;
                }
            }
            else
            {
                this.riderlessTicks = 0;
            }
            tickSounds();
        }

        tickLerp();

        if (this.isControlledByLocalInstance())
        {
            driveMovement();
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.fallDistance = 0.0F;
            LivingEntity controller = getControllingPassenger();
            if (controller != null)
                controller.fallDistance = 0.0F;
        }
        else if (!this.level().isClientSide)
        {
            // Server, remote client authoritative while ridden: do not self-move. A riderless mount on the server
            // just settles with gravity so it does not hang in the air after a dismount.
            if (this.getPassengers().isEmpty())
                serverIdleSettle();
            else
                this.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Server-side mount SFX: a randomised idle vocal, and a step or flap while it is actually moving. Played with a
     * null player so {@code level.playSound} broadcasts to every tracking client exactly once, which is why this is
     * gated to the server. The vehicle it replaced is never spawned, so no vehicle sound competes with these.
     */
    private void tickSounds()
    {
        String id = getMountId();
        if (--this.ambientDelay <= 0)
        {
            this.ambientDelay = 120 + this.random.nextInt(120);
            SoundEvent idle = CosmeticMountSounds.idle(id);
            if (idle != null)
                playMountSound(idle, 0.7F, 0.95F + this.random.nextFloat() * 0.1F);
        }
        if (this.moveSoundCooldown > 0)
            this.moveSoundCooldown--;
        boolean moving = !this.getPassengers().isEmpty()
                && this.getDeltaMovement().horizontalDistanceSqr() > 0.006D;
        if (moving && this.moveSoundCooldown <= 0)
        {
            // Flap/step faster at a sprint, matching the animation the rig plays; a walk keeps the 1.5 s pack cadence.
            this.moveSoundCooldown = this.inputSprint ? 16 : 28;
            SoundEvent move = CosmeticMountSounds.move(id);
            if (move != null)
                playMountSound(move, 0.5F, 1.0F);
        }
    }

    private void playMountSound(SoundEvent event, float volume, float pitch)
    {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), event, SoundSource.NEUTRAL, volume, pitch);
    }

    private void serverIdleSettle()
    {
        Vec3 m = this.getDeltaMovement();
        double vy = flyingResolved() ? 0.0D : (this.onGround() ? 0.0D : m.y - 0.04D);
        this.setDeltaMovement(m.x * 0.6D, vy, m.z * 0.6D);
        this.move(MoverType.SELF, this.getDeltaMovement());
    }

    private void driveMovement()
    {
        Vec3 motion = this.getDeltaMovement();
        LivingEntity controller = getControllingPassenger();
        boolean flying = flyingResolved();

        if (controller instanceof Player player)
        {
            float yawDelta = Mth.clamp(Mth.wrapDegrees(player.getYRot() - this.getYRot()), -MAX_YAW_STEP, MAX_YAW_STEP);
            this.setYRot(this.getYRot() + yawDelta);
            this.setXRot(0.0F);

            double base = speedResolved() * (this.inputSprint ? sprintMultiplierResolved() : 1.0D);
            float forward = (this.inputUp ? 1.0F : 0.0F) - (this.inputDown ? 1.0F : 0.0F);
            float strafe = (this.inputLeft ? 1.0F : 0.0F) - (this.inputRight ? 1.0F : 0.0F);

            Vec3 horiz = new Vec3(motion.x, 0.0D, motion.z);
            Vec3 input = new Vec3(strafe * 0.5F, 0.0D, forward);
            if (input.lengthSqr() > 1.0E-4)
            {
                Vec3 target = input.normalize().scale(base).yRot(-this.getYRot() * ((float) Math.PI / 180F));
                horiz = horiz.lerp(target, ACCEL_LERP);
            }
            else
            {
                horiz = horiz.scale(COAST_DECAY);
            }
            motion = new Vec3(horiz.x, motion.y, horiz.z);
        }
        else
        {
            motion = new Vec3(motion.x * 0.8D, motion.y, motion.z * 0.8D);
        }

        double vy = motion.y;
        if (flying)
        {
            // Altitude hold: jump climbs, sneak descends, otherwise the mount holds its height with light damping.
            if (this.inputJump)
                vy = 0.35D;
            else if (this.inputDescend)
                vy = -0.35D;
            else
                vy *= 0.6D;
        }
        else
        {
            // Walking: gravity plus a grounded jump, latched so holding space does not auto-bounce.
            if (!this.onGround())
                vy -= 0.08D;
            vy *= 0.92D;
            if (this.onGround() && vy < 0.0D)
                vy = 0.0D;
            if (this.inputJump && !this.jumpLatched && this.onGround())
            {
                vy = 0.5D;
                this.jumpLatched = true;
            }
            else if (!this.inputJump)
            {
                this.jumpLatched = false;
            }
        }

        this.setDeltaMovement(finiteOrZero(motion.x), finiteOrZero(vy), finiteOrZero(motion.z));
    }

    private static double finiteOrZero(double v)
    {
        return Double.isFinite(v) ? v : 0.0D;
    }

    private void tickLerp()
    {
        if (this.isControlledByLocalInstance())
        {
            this.lerpSteps = 0;
            this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
        }
        if (this.lerpSteps <= 0)
            return;
        double x = this.getX() + (this.lerpX - this.getX()) / this.lerpSteps;
        double y = this.getY() + (this.lerpY - this.getY()) / this.lerpSteps;
        double z = this.getZ() + (this.lerpZ - this.getZ()) / this.lerpSteps;
        double dyaw = Mth.wrapDegrees(this.lerpYRot - this.getYRot());
        this.setYRot(this.getYRot() + (float) (dyaw / this.lerpSteps));
        this.setPos(x, y, z);
        this.setRot(this.getYRot(), this.getXRot());
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
        if (sq > 0.006D)
        {
            String anim = this.inputSprint ? spec().fastAnimation : "walk";
            controller.setAnimation(RawAnimation.begin().then(anim, Animation.LoopType.LOOP));
        }
        else
        {
            controller.setAnimation(RawAnimation.begin().then("idle", Animation.LoopType.LOOP));
        }
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return this.cache;
    }
}
