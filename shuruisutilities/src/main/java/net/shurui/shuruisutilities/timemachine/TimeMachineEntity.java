package net.shurui.shuruisutilities.timemachine;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Trunks' time machine as a rideable, FLYING SU vehicle, drawn through the GeckoLib geo converted from the
 * Blockbench model (geometry only, no animations). It shares the one "hoverbike" curios slot with the hoverbike,
 * the space pod chip and the nimbus chip, so exactly one of them can ever be out at once, and it is deployed and
 * recalled from the same toggle keybind (see {@link net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle}).
 *
 * <p>It enables space travel the SAME way the space pod does: a rider who flies it up past the space-travel entry
 * altitude in an eligible dimension is carried into space aboard it, and every landing/return rebuilds it under the
 * pilot, all handled in {@link net.shurui.shuruisutilities.space.SpaceTravelModule} through {@link TimeMachineDeploy}.
 * Unlike DMZ's persistence-required space pod, this is a plain SU {@link Entity}: a lost entity costs nothing because
 * the chip never leaves the curios slot (the item-loss fix, commit 98c02141), so the carry paths simply discard and
 * re-deploy it instead of dissolving anything back into an item.
 *
 * <h3>Movement (Boat pattern, matching the hoverbike)</h3>
 * For a vehicle whose controlling passenger is the local player, vanilla makes the CLIENT authoritative and emits
 * {@code ServerboundMoveVehiclePacket} for the server to apply. So input flags are fed each client tick from the
 * rider's keys ({@code TimeMachineClientEvents}), and the controlling instance integrates them in {@link #tick()}
 * with a plain {@link #move(MoverType, Vec3)}; everyone else lerps toward the synced position. This is full 3D
 * flight: no gravity while ridden, jump ascends, sneak descends, WASD is yaw-relative. A riderless machine settles
 * to the ground under a gentle gravity so a stranded one does not hang in the air.
 */
public class TimeMachineEntity extends Entity implements GeoEntity
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // flight tuning (blocks/tick). sprint scales both the horizontal and the climb rate so a launch to the
    // space-entry altitude (1000 by default) is not a marathon. Kept modest so the big box is controllable.
    private static final double FLY_SPEED = 0.55D;
    private static final double CLIMB_SPEED = 0.55D;
    private static final double SPRINT_MULT = 2.2D;
    private static final double ACCEL_LERP = 0.30D;   // velocity ease toward the input target per tick
    private static final double COAST_DECAY = 0.80D;  // per-tick horizontal decay with no input
    private static final float MAX_YAW_STEP = 8.0F;   // max yaw ease toward the rider's view per tick
    private static final float HEAD_YAW_CLAMP = 120.0F;

    // riderless settle
    private static final double IDLE_GRAVITY = 0.04D;
    private static final double IDLE_HORIZONTAL_DECAY = 0.8D;

    // boat-style accumulate-then-break threshold
    private static final float BREAK_DAMAGE = 60.0F;

    // client interpolation state
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    // local-rider input flags, fed each client tick while this is the controlled vehicle
    private boolean inputUp;
    private boolean inputDown;
    private boolean inputLeft;
    private boolean inputRight;
    private boolean inputSprint;
    private boolean inputJump;
    private boolean inputSneak;

    private float damage;

    // owner of a curios-deployed machine (set on spawn). server-only, for recall bookkeeping. never null in
    // practice: deploy always stamps it. Persisted so a destroy long after deploy still clears the right record.
    @Nullable
    private UUID ownerUUID;

    public TimeMachineEntity(EntityType<? extends TimeMachineEntity> type, Level level)
    {
        super(type, level);
        this.blocksBuilding = true;
    }

    public TimeMachineEntity(Level level, double x, double y, double z)
    {
        this(TimeMachineEntities.TIME_MACHINE.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData()
    {
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

    @Override
    protected void addAdditionalSaveData(CompoundTag tag)
    {
        if (this.ownerUUID != null)
            tag.putUUID("Owner", this.ownerUUID);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag)
    {
        this.ownerUUID = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    public void setInput(boolean up, boolean down, boolean left, boolean right, boolean sprint, boolean jump,
            boolean sneak)
    {
        this.inputUp = up;
        this.inputDown = down;
        this.inputLeft = left;
        this.inputRight = right;
        this.inputSprint = sprint;
        this.inputJump = jump;
        this.inputSneak = sneak;
    }

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

    @Override
    public InteractionResult interact(Player player, InteractionHand hand)
    {
        if (player.isSecondaryUseActive())
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
        // seat offset is entity-local: x/z rotated by yaw into world space, y straight up. See TimeMachineSeat.
        Vec3 local = new Vec3(TimeMachineSeat.X, 0.0D, TimeMachineSeat.Z)
                .yRot(-this.getYRot() * ((float) Math.PI / 180F));
        double y = this.getY() + TimeMachineSeat.Y + passenger.getMyRidingOffset();
        move.accept(passenger, this.getX() + local.x, y, this.getZ() + local.z);
        if (passenger instanceof LivingEntity living)
        {
            living.setYBodyRot(this.getYRot());
            clampRiderHeadYaw(living);
        }
    }

    private void clampRiderHeadYaw(LivingEntity rider)
    {
        float delta = Mth.wrapDegrees(rider.getYHeadRot() - this.getYRot());
        float clamped = Mth.clamp(delta, -HEAD_YAW_CLAMP, HEAD_YAW_CLAMP);
        rider.yRotO += clamped - delta;
        rider.setYHeadRot(rider.getYHeadRot() + clamped - delta);
    }

    @Override
    public double getPassengersRidingOffset()
    {
        return TimeMachineSeat.Y;
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity rider)
    {
        Vec3 side = getCollisionHorizontalEscapeVector(this.getBbWidth() * Mth.SQRT_OF_TWO, rider.getBbWidth(),
                this.getYRot());
        return new Vec3(this.getX() + side.x, this.getBoundingBox().maxY, this.getZ() + side.z);
    }

    /* ----------------------------------------------------------- collision / interaction */

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
    public boolean canCollideWith(Entity other)
    {
        return (other.canBeCollidedWith() || other.isPushable()) && !this.isPassengerOfSameVehicle(other);
    }

    @Override
    public boolean isNoGravity()
    {
        return true; // flight vehicle: gravity is handled by hand in tick(), never by the base entity
    }

    @Override
    public boolean isPushedByFluid()
    {
        return false;
    }

    /* ----------------------------------------------------------- damage / drops */

    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        if (this.isInvulnerableTo(source))
            return false;
        if (this.level().isClientSide || this.isRemoved())
            return true;

        this.damage += amount * 10.0F;
        this.gameEvent(net.minecraft.world.level.gameevent.GameEvent.ENTITY_DAMAGE, source.getEntity());

        boolean creative = source.getEntity() instanceof Player p && p.getAbilities().instabuild;
        if (creative || this.damage > BREAK_DAMAGE)
        {
            // curios-deployed machines never ground-drop: the chip stays in the owner's slot the whole time it is
            // out, so a destroy must produce NO item (dropping one would duplicate the chip). An owner-less machine
            // (which deploy never makes, kept only as a safety) falls back to vanilla drop behaviour.
            if (this.ownerUUID == null && !creative
                    && this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS))
                this.spawnAtLocation(TimeMachineDeploy.chipStack());
            this.discard();
        }
        return true;
    }

    // A curios-deployed machine's ITEM never leaves the slot, so a destroy must NOT hand any item back (that would
    // duplicate the chip still sitting in the owner's slot). On a genuine destroy we only clear the owner's stale
    // deploy record, and only when they are online. Chunk-unload / dim-change removals are ignored so a deployed
    // machine persists. Mirrors HoverbikeEntity.remove exactly.
    @Override
    public void remove(Entity.RemovalReason reason)
    {
        if (!this.level().isClientSide && this.ownerUUID != null && reason.shouldDestroy())
        {
            ServerPlayer online = ((ServerLevel) this.level()).getServer().getPlayerList().getPlayer(this.ownerUUID);
            if (online != null)
                TimeMachineDeployData.clear(online);
        }
        super.remove(reason);
    }

    @Override
    public boolean isAttackable()
    {
        return true;
    }

    @Override
    public net.minecraft.world.item.ItemStack getPickResult()
    {
        return TimeMachineDeploy.chipStack();
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source)
    {
        return false;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state)
    {
    }

    /* ----------------------------------------------------------- tick / movement */

    @Override
    public void tick()
    {
        // guard a machine whose position went non-finite (defensive, matching the hoverbike): put any rider down
        // and remove it rather than throw in the entity tracker every tick.
        if (!this.level().isClientSide
                && !(Double.isFinite(this.getX()) && Double.isFinite(this.getY()) && Double.isFinite(this.getZ())))
        {
            for (Entity rider : new java.util.ArrayList<>(this.getPassengers()))
            {
                rider.stopRiding();
                rider.setDeltaMovement(Vec3.ZERO);
            }
            this.discard();
            return;
        }

        if (this.damage > 0.0F)
            this.damage -= 1.0F;

        super.tick();
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
        else if (this.level().isClientSide)
        {
            // remote view: vehicle-move packet + lerp drive it, nothing to simulate
        }
        else
        {
            // server, remote client authoritative: don't self-move (packet applies its pos). zero residual motion.
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    private void driveMovement()
    {
        Vec3 motion = this.getDeltaMovement();
        LivingEntity controller = getControllingPassenger();
        if (controller instanceof Player player)
        {
            // ease yaw toward the rider's view (do NOT touch yRotO: baseTick copied yRot->yRotO before this, so the
            // renderer keeps a real prev-frame yaw to interpolate from), pitch follows at half.
            float yawDelta = Mth.wrapDegrees(player.getYRot() - this.getYRot());
            yawDelta = Mth.clamp(yawDelta, -MAX_YAW_STEP, MAX_YAW_STEP);
            this.setYRot(this.getYRot() + yawDelta);
            this.setXRot(player.getXRot() * 0.5F);

            double base = FLY_SPEED;
            double climb = CLIMB_SPEED;
            if (this.inputSprint)
            {
                base *= SPRINT_MULT;
                climb *= SPRINT_MULT;
            }

            float forward = (this.inputUp ? 1.0F : 0.0F) - (this.inputDown ? 1.0F : 0.0F);
            float strafe = (this.inputLeft ? 1.0F : 0.0F) - (this.inputRight ? 1.0F : 0.0F);

            Vec3 horiz = new Vec3(motion.x, 0.0D, motion.z);
            Vec3 input = new Vec3(strafe * 0.6F, 0.0D, forward);
            if (input.lengthSqr() > 1.0E-4)
            {
                Vec3 target = input.normalize().scale(base).yRot(-this.getYRot() * ((float) Math.PI / 180F));
                horiz = horiz.lerp(target, ACCEL_LERP);
            }
            else
            {
                horiz = horiz.scale(COAST_DECAY);
            }

            // vertical: jump ascends, sneak descends, else hold altitude (no gravity while ridden).
            double vy;
            if (this.inputJump && !this.inputSneak)
                vy = climb;
            else if (this.inputSneak && !this.inputJump)
                vy = -climb;
            else
                vy = motion.y * 0.6D; // bleed residual so it settles to a hover

            motion = new Vec3(horiz.x, vy, horiz.z);
        }
        else
        {
            // riderless: bleed horizontal speed and settle to the ground under a gentle gravity
            double vy = this.onGround() ? 0.0D : motion.y - IDLE_GRAVITY;
            motion = new Vec3(motion.x * IDLE_HORIZONTAL_DECAY, vy, motion.z * IDLE_HORIZONTAL_DECAY);
        }

        this.setDeltaMovement(finiteOrZero(motion.x), finiteOrZero(motion.y), finiteOrZero(motion.z));
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

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        // one idle controller; the geo has no keyframe animations, so it never plays anything (PlayState.STOP).
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state)
    {
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }
}
