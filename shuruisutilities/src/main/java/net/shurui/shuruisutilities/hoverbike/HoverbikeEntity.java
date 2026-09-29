package net.shurui.shuruisutilities.hoverbike;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.RegistryObject;


/**
 * Rideable hoverbike. One entity class, cosmetic model/sound picked by a synched Variant byte (1-4), no AI.
 *
 * Movement is Boat-pattern: for a vehicle whose controlling passenger is the local player, vanilla makes the
 * CLIENT authoritative (isControlledByLocalInstance() true on the rider), the client simulates move() and
 * vanilla auto-emits ServerboundMoveVehiclePacket, which the server applies. Anything the server computes
 * itself gets overwritten by that packet, which is why the old server-authoritative version never moved. So:
 * - input flags fed each client tick from the rider's keys (HoverbikeClientEvents.ForgeBus -> setInput);
 *   we can't patch LocalPlayer.rideTick like Boat does, so a tick handler feeds them. No custom packet.
 * - in tick(), controlling client reads the flags, does yaw-relative velocity + hover gravity + move(SELF).
 *   Everyone else just lerps toward the synced pos.
 * - server still simulates gravity/drift when riderless so dropped bikes settle.
 * maxUpStep 1.0 = one-block ledge step. Step/land sounds suppressed (engine loop only); never takes fall damage.
 */
public class HoverbikeEntity extends Entity
{
    private static final EntityDataAccessor<Byte> DATA_VARIANT =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_HURT_TIME =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_DAMAGE =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.FLOAT);
    // server-derived speed synced to the rider. ConfigHoverbikes is COMMON (not synced), so on a dedicated
    // server the admin's edits live server-side only; without this the client would drive on its local defaults.
    // driveMovement() reads these, not the config. Defaults = variant-1 config defaults so it's sane pre-bake.
    private static final EntityDataAccessor<Float> DATA_SPEED =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPRINT_MULT =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.FLOAT);
    // --- racing (PRIVATE feature). Appended AT THE END of defineSynchedData so the wire order of the existing
    // accessors is unchanged and old-world bikes load byte-identically. All three are transient race state: a race
    // bike is never saved (shouldBeSaved false below), so these are never written to disk.
    // DATA_RACE_FLAGS: race state bits (RACE_FLAG_ACTIVE marks a race-managed bike). DATA_RACE_FX: cosmetic FX bits
    // (see net.shurui.shuruisutilities.racing.physics.RaceFx). DATA_RACER_SLOT: the grid/place slot for labels.
    // DATA_RACE_AURA: the SERVER-authored aura FX bits (Destroyer, Kaioken flash / x20, Nimbus, Afterimage) for a
    // HUMAN racer, so OTHER clients (and bots) can draw those auras and hide an afterimaged rider. DATA_RACE_FX for a
    // human rider is set CLIENT-side from the local physics and never leaves that client; this parallel accessor is
    // the server's copy of just the aura windows, so remote visibility never fights the client-authored physics FX.
    private static final EntityDataAccessor<Integer> DATA_RACE_FLAGS =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RACE_FX =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> DATA_RACER_SLOT =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_RACE_AURA =
            SynchedEntityData.defineId(HoverbikeEntity.class, EntityDataSerializers.INT);

    /** {@link #DATA_RACE_FLAGS} bit marking a bike the race engine owns (a "race bike"), never a personal deploy. */
    public static final int RACE_FLAG_ACTIVE = 1;

    // boat-style accumulate-then-break threshold
    private static final float BREAK_DAMAGE = 40.0F;

    // buoyancy spring toward the water surface (fraction of height error/tick) + vertical damping so it settles
    private static final double WATER_BUOYANCY_ACCEL = 0.35D;
    private static final double WATER_VERTICAL_DAMPING = 0.6D;

    // shore-exit climb impulse (blocks/tick up) when a floating bike hits a shore block it can't step over.
    // vanilla step-up only fires when onGround(), and a floating bike isn't grounded (same reason boats can't
    // drive out of water). with the 0.90 air damping / 0.035 gravity below, the arc rises ~4 blocks before
    // decaying, so it easily crests a one-block ledge while forward momentum carries it onto the shore.
    private static final double CLIMB_IMPULSE = 0.42D;
    private static final double SHORE_PROBE_DISTANCE = 0.45D;
    private static final double SHORE_RISE_LOW = 0.6D;  // try this first (single-block ledge)
    private static final double SHORE_RISE_HIGH = 1.1D; // taller lip, up to maxUpStep
    private static final double CLIMB_REAPPLY_VY = 0.1D; // only re-apply below this vy so it can't stack into a rocket

    // max yaw ease toward the rider's view per tick. snapping straight to the player yaw (and collapsing yRotO)
    // killed render interp and caused the turn jitter; easing curves the heading instead of hard-flipping.
    private static final float MAX_YAW_STEP = 10.0F;
    private static final double ACCEL_LERP = 0.25D;  // velocity ease toward input target/tick
    private static final double COAST_DECAY = 0.5D;  // horizontal decay/tick with no input
    private static final float HEAD_YAW_CLAMP = 105.0F; // boat-style free-look limit, degrees
    // horizontal move longer than this fraction of box width gets sub-stepped so a fast bike sweeps walls
    // instead of tunnelling
    private static final double SUBSTEP_WIDTH_FRACTION = 0.5D;

    // Client-side interpolation state (populated by lerpTo from server position updates).
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    // Local-rider input flags, fed from the client tick handler while this bike is the controlled vehicle.
    private boolean inputUp;
    private boolean inputDown;
    private boolean inputLeft;
    private boolean inputRight;
    private boolean inputSprint;
    private boolean inputJump;

    // edge-latch: consume the jump flag once per press so holding space doesn't re-jump on re-touch
    private boolean jumpLatched;

    // glide memory (corner re-press guard). when sweepMove zeroes an axis because a wall/corner blocked it, it
    // records the blocked direction here (sign of intended component, 0 = free). next tick driveMovement damps the
    // input target on any axis still pressing that way so the accel-ease doesn't slam back into the seam.
    // self-clearing when the axis records no collision or the input sign flips.
    private double glideBlockedX;
    private double glideBlockedZ;

    private static final double GLIDE_REPRESS_SCALE = 0.5D;

    // keep a little push (vs hard zero) on an axis the final sub-move ended blocked on, so it can re-slide the seam.
    // hard zero only after CONSECUTIVE_BLOCK_HARD_ZERO+ blocked ticks.
    private static final double BLOCKED_AXIS_VELOCITY_SCALE = 0.25D;
    private static final int CONSECUTIVE_BLOCK_HARD_ZERO = 2;

    private int consecutiveBlockedX;
    private int consecutiveBlockedZ;

    // set by driveMovement when the water shore-exit assist fired. sweepMove must NOT zero the blocked axis or latch
    // glide on such a tick: the horizontal collision is the shore lip the climb impulse is lifting us over, and
    // killing forward drive would strand the bike at the waterline. reset each tick at top of driveMovement.
    private boolean waterExitAssistThisTick;

    // one-shot: re-derive synced speed from config once after the bike enters the world (covers disk-loaded bikes
    // whose synced values would otherwise be stale definition defaults). done on first tick, not readAdditionalSaveData,
    // since level/side is reliable by then.
    private boolean serverSpeedInitialized;

    // owner of a curios-deployed bike (set on spawn). not synced, server-only, for recall bookkeeping.
    // null for boat-style bikes spawned from a held item.
    @Nullable
    private UUID ownerUUID;

    // Racing: the ONLY player allowed to ride a race bike (its assigned racer). Server-side, transient race state,
    // never saved (a race bike is never saved). Null on a normal personal bike.
    @Nullable
    private UUID raceOwner;

    // Racing physics (transient, never saved). A race bike drives on these, NOT on DATA_SPEED / variant, so every
    // racing bike handles identically (owner decision). Set by the key on the server (bots) and by the rider client
    // (RaceInput, from packet 109) for a human racer. kartState carries the physics between ticks; raceSurface is the
    // set of blocks that count as on-track (off it, the off-road penalty applies). Null on a normal personal bike,
    // which is exactly why a bike OUTSIDE a race is untouched by the race branch in driveMovement.
    @Nullable
    private net.shurui.shuruisutilities.racing.physics.RaceDriveParams raceParams;
    @Nullable
    private java.util.Set<net.minecraft.world.level.block.Block> raceSurface;
    @Nullable
    private net.shurui.shuruisutilities.racing.physics.KartState kartState;
    // race jump edge detection (separate from jumpLatched so the normal path is untouched)
    private boolean raceJumpWasHeld;

    public HoverbikeEntity(net.minecraft.world.entity.EntityType<? extends HoverbikeEntity> type, Level level)
    {
        super(type, level);
        this.blocksBuilding = true;
        this.setMaxUpStep(1.0F);
    }

    public HoverbikeEntity(Level level, double x, double y, double z)
    {
        this(HoverbikeEntities.HOVERBIKE.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData()
    {
        this.entityData.define(DATA_VARIANT, (byte) 1);
        this.entityData.define(DATA_HURT_TIME, 0);
        this.entityData.define(DATA_DAMAGE, 0.0F);
        this.entityData.define(DATA_SPEED, 0.35F);
        this.entityData.define(DATA_SPRINT_MULT, 1.6F);
        // Racing accessors, APPENDED at the end so the existing wire order is untouched.
        this.entityData.define(DATA_RACE_FLAGS, 0);
        this.entityData.define(DATA_RACE_FX, 0);
        this.entityData.define(DATA_RACER_SLOT, (byte) 0);
        this.entityData.define(DATA_RACE_AURA, 0);
    }

    // --- racing accessors + guards (PRIVATE feature; a normal personal bike is unaffected) ---

    /** Whether this bike is owned by the race engine (a "race bike"), not a personal deploy. */
    public boolean isRaceBike()
    {
        return (this.entityData.get(DATA_RACE_FLAGS) & RACE_FLAG_ACTIVE) != 0;
    }

    public int getRaceFlags()
    {
        return this.entityData.get(DATA_RACE_FLAGS);
    }

    public void setRaceFlags(int flags)
    {
        this.entityData.set(DATA_RACE_FLAGS, flags);
    }

    public int getRaceFx()
    {
        return this.entityData.get(DATA_RACE_FX);
    }

    public void setRaceFx(int fx)
    {
        this.entityData.set(DATA_RACE_FX, fx);
    }

    /** The SERVER-authored aura FX bits (a subset of {@link net.shurui.shuruisutilities.racing.physics.RaceFx}:
     *  the Destroyer / Kaioken / Nimbus / Afterimage auras) for a human racer, synced to every tracking client so
     *  others can draw the aura and hide an afterimaged rider. 0 on a bot bike (whose full FX rides DATA_RACE_FX). */
    public int getRaceAura()
    {
        return this.entityData.get(DATA_RACE_AURA);
    }

    public void setRaceAura(int aura)
    {
        this.entityData.set(DATA_RACE_AURA, aura);
    }

    public int getRacerSlot()
    {
        return this.entityData.get(DATA_RACER_SLOT) & 0xFF;
    }

    public void setRacerSlot(int slot)
    {
        this.entityData.set(DATA_RACER_SLOT, (byte) slot);
    }

    @Nullable
    public UUID getRaceOwner()
    {
        return this.raceOwner;
    }

    /** Assign the racer allowed to ride this race bike (server-side; part of race setup). */
    public void setRaceOwner(@Nullable UUID uuid)
    {
        this.raceOwner = uuid;
    }

    /** Set the shared kart-physics parameters this race bike drives on (server bot setup, or the rider client). */
    public void setRaceParams(@Nullable net.shurui.shuruisutilities.racing.physics.RaceDriveParams params)
    {
        this.raceParams = params;
    }

    /** Set the on-track surface block set (off it, the off-road penalty applies). */
    public void setRaceSurface(@Nullable java.util.Set<net.minecraft.world.level.block.Block> surface)
    {
        this.raceSurface = surface;
    }

    /** This bike's kart-physics state, created lazily; transient, never saved. */
    public net.shurui.shuruisutilities.racing.physics.KartState raceKartState()
    {
        if (this.kartState == null)
            this.kartState = new net.shurui.shuruisutilities.racing.physics.KartState();
        return this.kartState;
    }

    private boolean isAssignedRacer(Entity entity)
    {
        return entity != null && this.raceOwner != null && this.raceOwner.equals(entity.getUUID());
    }

    // clamped 1-4. server-side also re-derives synced speed so both spawn paths carry the right per-variant speed.
    public void setVariant(int variant)
    {
        this.entityData.set(DATA_VARIANT, (byte) Mth.clamp(variant, 1, 4));
        if (!this.level().isClientSide)
            refreshSpeedFromConfig();
    }

    public int getVariant()
    {
        return this.entityData.get(DATA_VARIANT);
    }

    // copy per-variant speed + sprint mult from config into synced data. server-only (client just reads).
    private void refreshSpeedFromConfig()
    {
        int variant = Mth.clamp(getVariant(), 1, 4);
        this.entityData.set(DATA_SPEED, (float) ConfigHoverbikes.speed[variant]);
        this.entityData.set(DATA_SPRINT_MULT, (float) ConfigHoverbikes.sprintMultiplier);
    }

    private float getSyncedSpeed()
    {
        return this.entityData.get(DATA_SPEED);
    }

    private float getSyncedSprintMultiplier()
    {
        return this.entityData.get(DATA_SPRINT_MULT);
    }

    // re-derive synced speed for every loaded bike across all levels after a live config change (editor / /su reload).
    // full scan is fine since config edits are rare. no-op if the server isn't running.
    public static void refreshAllLoadedFromConfig()
    {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        for (ServerLevel level : server.getAllLevels())
        {
            for (Entity e : level.getAllEntities())
            {
                if (e instanceof HoverbikeEntity bike)
                    bike.refreshSpeedFromConfig();
            }
        }
    }

    private void setHurtTime(int time)
    {
        this.entityData.set(DATA_HURT_TIME, time);
    }

    private int getHurtTime()
    {
        return this.entityData.get(DATA_HURT_TIME);
    }

    private void setDamage(float damage)
    {
        this.entityData.set(DATA_DAMAGE, damage);
    }

    private float getDamage()
    {
        return this.entityData.get(DATA_DAMAGE);
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
        tag.putByte("Variant", (byte) getVariant());
        if (this.ownerUUID != null)
            tag.putUUID("Owner", this.ownerUUID);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag)
    {
        if (tag.contains("Variant"))
            setVariant(tag.getByte("Variant"));
        this.ownerUUID = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    @Override
    public boolean shouldBeSaved()
    {
        // A race bike is transient race state: never write it to disk, so a server stop mid-race leaves no orphan
        // bike behind. A normal personal bike saves as before.
        return !isRaceBike() && super.shouldBeSaved();
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
        // A race bike only ever carries its assigned racer; a personal bike is unchanged (first-come single seat).
        if (isRaceBike())
            return getPassengers().isEmpty() && isAssignedRacer(passenger);
        return getPassengers().isEmpty();
    }

    // fed every client tick while this bike is the controlled vehicle (mirrors Boat.setInput). the resulting
    // motion, not the keys, is what syncs via the vanilla vehicle-move packet.
    public void setInput(boolean up, boolean down, boolean left, boolean right, boolean sprint, boolean jump)
    {
        this.inputUp = up;
        this.inputDown = down;
        this.inputLeft = left;
        this.inputRight = right;
        this.inputSprint = sprint;
        this.inputJump = jump;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand)
    {
        if (player.isSecondaryUseActive())
            return InteractionResult.PASS;
        // A race bike may only be mounted by its assigned racer; anyone else is passed through (no mount).
        if (isRaceBike() && !isAssignedRacer(player))
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

        int variant = Mth.clamp(getVariant(), 1, 4);
        double[] seat = HoverbikeTunables.SEAT_OFFSET[variant];
        // Seat offset is entity-local: x/z rotated by yaw into world space, y is straight up.
        Vec3 local = new Vec3(seat[0], 0.0D, seat[2]).yRot(-this.getYRot() * ((float) Math.PI / 180F));
        double y = this.getY() + seat[1] + passenger.getMyRidingOffset();
        move.accept(passenger, this.getX() + local.x, y, this.getZ() + local.z);
        if (passenger instanceof LivingEntity living)
        {
            // body follows the bike; head is free-look, soft-clamped within HEAD_YAW_CLAMP (Boat.clampRotation
            // style). a per-tick yaw delta stuttered while turning, so don't drive the head that way.
            living.setYBodyRot(this.getYRot());
            clampRiderHeadYaw(living);
        }
    }

    // nudge head yaw back inside HEAD_YAW_CLAMP of the bike yaw without snapping (Boat.clampRotation)
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
        // Seat height above the bike origin; per-variant tunable (y component of SEAT_OFFSET).
        return HoverbikeTunables.SEAT_OFFSET[Mth.clamp(getVariant(), 1, 4)][1];
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity rider)
    {
        Vec3 side = getCollisionHorizontalEscapeVector(this.getBbWidth() * Mth.SQRT_OF_TWO, rider.getBbWidth(),
                this.getYRot());
        double x = this.getX() + side.x;
        double z = this.getZ() + side.z;
        return new Vec3(x, this.getBoundingBox().maxY, z);
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
        return false;
    }

    // no fluid push/drag (Boat.isPushedByFluid). our buoyancy owns vertical motion on water; the vanilla
    // fluid-push would fight the float and slow the bike on the surface.
    @Override
    public boolean isPushedByFluid()
    {
        return false;
    }

    /* ----------------------------------------------------------- damage / drops */

    // boat-style accumulate-then-break: punches add to a decaying damage counter; past BREAK_DAMAGE it breaks
    // and drops its variant item (honoring doEntityDrops). creative attacker breaks instantly with no drop.
    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        // A race bike never takes damage and never drops: race hits are not damage, and it must not break mid-race.
        if (isRaceBike())
            return false;
        if (this.isInvulnerableTo(source))
            return false;
        if (this.level().isClientSide || this.isRemoved())
            return true;

        this.setHurtTime(10);
        this.setDamage(this.getDamage() + amount * 10.0F);
        this.markHurt();
        this.gameEvent(net.minecraft.world.level.gameevent.GameEvent.ENTITY_DAMAGE, source.getEntity());

        boolean creative = source.getEntity() instanceof Player p && p.getAbilities().instabuild;
        if (creative || this.getDamage() > BREAK_DAMAGE)
        {
            // curios-deployed bikes never ground-drop: their item stays in the owner's curios slot the whole time, so
            // a destroy must produce NO item (dropping one would duplicate the chip still in the slot). owner-less
            // bikes (which deploy never makes, kept only as a safety) fall back to vanilla drop behavior.
            if (this.ownerUUID == null && !creative
                    && this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS))
                this.spawnAtLocation(getDropItem());
            this.discard();
        }
        return true;
    }

    // A curios-deployed bike's ITEM never leaves the slot (see PacketHoverbikeToggle.deploy), so a destroy must NOT
    // hand any item back: doing so would duplicate the chip still sitting in the owner's slot. On a genuine destroy
    // (damage break, /kill) we only clear the owner's stale deploy record, and only when they are online, so their
    // next toggle deploys fresh straight from the slot. An offline owner keeps the record; recall's not-found path
    // clears it on their next toggle. Chunk-unload / dim-change removals are ignored so the deployed bike persists.
    @Override
    public void remove(Entity.RemovalReason reason)
    {
        if (!this.level().isClientSide && this.ownerUUID != null && reason.shouldDestroy())
        {
            ServerPlayer online = ((ServerLevel) this.level()).getServer().getPlayerList().getPlayer(this.ownerUUID);
            if (online != null)
                HoverbikeDeployData.clear(online);
        }

        super.remove(reason);
    }

    @Override
    public boolean isAttackable()
    {
        return true;
    }

    private ItemStack getDropItem()
    {
        RegistryObject<net.minecraft.world.item.Item> item = HoverbikeItems.BIKES[Mth.clamp(getVariant(), 1, 4)];
        return new ItemStack(item.get());
    }

    @Override
    public ItemStack getPickResult()
    {
        return getDropItem();
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source)
    {
        // Hover vehicle: never takes fall damage, and swallows it for the rider (handled in tick()).
        return false;
    }

    // engine loop only: no-op the step and swim/land sound paths
    @Override
    protected void playStepSound(net.minecraft.core.BlockPos pos, BlockState state)
    {
        // no-op: engine loop only.
    }

    @Override
    protected void playSwimSound(float volume)
    {
        // no-op: engine loop only.
    }

    /* ----------------------------------------------------------- tick / movement */

    @Override
    public void tick()
    {
        // A bike whose own position stopped being a number cannot be ticked, tracked or saved sanely, and one of
        // them sitting in a loaded chunk throws in the entity tracker every tick for as long as it exists. Put any
        // rider down somewhere real and remove it. Should be unreachable now the buoyancy spring cannot produce a
        // non-finite velocity, but a bike saved to disk BEFORE that fix is still out there in somebody's world.
        if (!this.level().isClientSide
                && !(Double.isFinite(this.getX()) && Double.isFinite(this.getY()) && Double.isFinite(this.getZ())))
        {
            for (net.minecraft.world.entity.Entity rider : new java.util.ArrayList<>(this.getPassengers()))
            {
                rider.stopRiding();
                rider.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                if (rider instanceof net.minecraft.server.level.ServerPlayer p
                        && this.level() instanceof net.minecraft.server.level.ServerLevel sl)
                {
                    net.minecraft.core.BlockPos spawn = sl.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            sl.getSharedSpawnPos());
                    p.teleportTo(sl, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, p.getYRot(), p.getXRot());
                }
            }
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[Hoverbike] discarded a bike with a non-finite position");
            this.discard();
            return;
        }

        // re-derive config speed once on the server so disk-loaded bikes reflect live config, even if it changed
        // while they were unloaded
        if (!this.level().isClientSide && !this.serverSpeedInitialized)
        {
            refreshSpeedFromConfig();
            this.serverSpeedInitialized = true;
        }

        // decay hurt/damage counters (server drives, synced for the renderer)
        if (this.getHurtTime() > 0)
            this.setHurtTime(this.getHurtTime() - 1);
        if (this.getDamage() > 0.0F)
            this.setDamage(this.getDamage() - 1.0F);

        super.tick();

        tickLerp();

        // A riderless race bike is driven by the key's autopilot (RaceBots via the hook), server-side. Feed its input
        // before the authoritative simulate below, so the race branch in driveMovement steers it.
        if (!this.level().isClientSide && isRaceBike() && getControllingPassenger() == null)
        {
            net.shurui.shuruisutilities.api.key.RaceHooks.BotInput bi =
                    net.shurui.shuruisutilities.api.key.RaceHooks.get().botInput(this);
            if (bi != null)
                setInput(bi.up(), bi.down(), bi.left(), bi.right(), bi.sprint(), bi.jump());
            else
                setInput(false, false, false, false, false, false);
        }

        if (this.isControlledByLocalInstance())
        {
            // authoritative simulator (riding client, or server when riderless)
            driveMovement();
            sweepMove(this.getDeltaMovement());

            // no fall damage for bike or rider
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

    // this tick's velocity from input (ridden) or drift/gravity (riderless)
    private void driveMovement()
    {
        // reset shore-exit latch; re-armed below only if the water-exit probe fires
        this.waterExitAssistThisTick = false;

        // RACE BRANCH: a race-managed bike drives on the shared kart physics (RaceDriveParams), never on DATA_SPEED
        // or the variant, so every racing bike handles identically. A normal personal bike NEVER has the race flag
        // set, so it never enters here and behaves exactly as before.
        if (isRaceBike())
        {
            driveRaceMovement();
            return;
        }

        Vec3 motion = this.getDeltaMovement();

        LivingEntity controller = getControllingPassenger();
        if (controller instanceof Player player)
        {
            // ease bike yaw toward the rider's view (clamped MAX_YAW_STEP deg/tick), don't snap. do NOT touch
            // yRotO: baseTick copies yRot -> yRotO before this, so leaving it gives the renderer a real prev-frame
            // yaw to interpolate from (the turn-jitter fix).
            float yawDelta = Mth.wrapDegrees(player.getYRot() - this.getYRot());
            yawDelta = Mth.clamp(yawDelta, -MAX_YAW_STEP, MAX_YAW_STEP);
            this.setYRot(this.getYRot() + yawDelta);
            this.setXRot(player.getXRot() * 0.5F);

            // read the synced speed, not the COMMON config, so dedicated-server admin edits reach the driving client
            double base = getSyncedSpeed();
            if (this.inputSprint)
                base *= getSyncedSprintMultiplier();

            float forward = (this.inputUp ? 1.0F : 0.0F) - (this.inputDown ? 1.0F : 0.0F);
            float strafe = (this.inputLeft ? 1.0F : 0.0F) - (this.inputRight ? 1.0F : 0.0F);

            Vec3 horiz = new Vec3(motion.x, 0.0D, motion.z);
            Vec3 input = new Vec3(strafe * 0.5F, 0.0D, forward);
            if (input.lengthSqr() > 1.0E-4)
            {
                // target from the bike's eased yaw (not the player's raw yaw) so turns curve smoothly. ease
                // current -> target so accel ramps rather than snaps.
                Vec3 target = input.normalize().scale(base).yRot(-this.getYRot() * ((float) Math.PI / 180F));
                target = applyGlideMemory(target);
                horiz = horiz.lerp(target, ACCEL_LERP);
            }
            else
            {
                horiz = horiz.scale(COAST_DECAY); // no input, coast down
            }
            motion = new Vec3(horiz.x, motion.y, horiz.z);
        }
        else
        {
            // riderless: bleed horizontal speed
            motion = new Vec3(motion.x * 0.8D, motion.y, motion.z * 0.8D);
        }

        // vertical: buoyancy on water, hover-gravity in air, plus jump. onWater also gates the jump below.
        //
        // ONE probe, shared by the gate and the spring below. They used to be two independent calls and could
        // disagree, which is what let a rider be thrown to an infinite position: isFloatingOnWater() returns true
        // from isInWater() without ever consulting waterSurfaceY(), and isInWater() is a BOUNDING BOX test while
        // waterSurfaceY() probes only the origin block and the one under it. Straddling flowing water satisfies
        // the first and misses the second, so the spring ran with surface = NEGATIVE_INFINITY and drove vy, the
        // bike and its passenger to infinity. See surfaceOrNaN().
        double surface = waterSurfaceY();
        boolean onWater = isFloatingOnWater(surface);
        double vy = motion.y;

        if (onWater && Double.isFinite(surface))
        {
            // buoyancy: settle the base at/just above the surface, damp vy so it rides like ground
            double error = surface - this.getY(); // >0 = below the target surface
            vy += error * WATER_BUOYANCY_ACCEL;   // spring toward surface
            vy *= WATER_VERTICAL_DAMPING;         // damp so it doesn't bounce

            // shore-exit assist: a floating bike isn't onGround() so maxUpStep never fires on a shore block's side
            // face and it stalls at the waterline. when driving into a low ledge, kick an upward impulse
            // (rate-limited) and let forward momentum carry it onto the shore.
            double dirX = motion.x;
            double dirZ = motion.z;
            double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (dirLen > 1.0E-4 && vy < CLIMB_REAPPLY_VY)
            {
                dirX /= dirLen;
                dirZ /= dirLen;
                double rise = shoreExitRise(dirX, dirZ);
                if (rise > 0.0D && hasHeadroom(CLIMB_IMPULSE))
                {
                    vy = CLIMB_IMPULSE;
                    // tell sweepMove the shore-lip collision this tick isn't a wall (keep forward vel, no glide latch)
                    this.waterExitAssistThisTick = true;
                }
            }
        }
        else
        {
            // hover gravity: gentle pull, damped so it floats rather than plummets
            if (!this.onGround())
                vy -= 0.035D;
            vy *= 0.90D;
            if (this.onGround() && vy < 0)
                vy = 0.0D;
        }

        // jump: only when grounded or on water, no mid-air re-jump. latch so holding space doesn't auto-bounce.
        // headroom-gated so we don't wedge into a ceiling.
        boolean grounded = this.onGround() || onWater;
        if (this.inputJump && !this.jumpLatched && grounded)
        {
            if (hasHeadroom(HoverbikeTunables.JUMP_POWER))
                vy = HoverbikeTunables.JUMP_POWER;
            this.jumpLatched = true;
        }
        else if (!this.inputJump)
        {
            this.jumpLatched = false;
        }

        // clip upward vel (jump or water spring) that would drive into a solid block above
        if (vy > 0.0D && !hasHeadroom(vy))
            vy = 0.0D;

        // Last line of defence: never hand the entity a velocity that is not a real number. Anything non-finite
        // here propagates into the position, and from the position into the RIDER's position, and from there into
        // playerdata on the next save, which locks that player out of the server on every subsequent login until
        // somebody moves them. A dropped velocity for one tick is not worth comparing to that.
        this.setDeltaMovement(finiteOrZero(motion.x), finiteOrZero(vy), finiteOrZero(motion.z));
    }

    // Kart-physics drive for a race bike. Runs on the rider's client (a human racer) and on the server (a bot bike);
    // both feed input flags the same way. Reads only the shared RaceDriveParams. sweepMove (in tick) still moves the
    // bike, so wall sweeping is unchanged; the wall bump reads this.horizontalCollision from that move.
    private void driveRaceMovement()
    {
        net.shurui.shuruisutilities.racing.physics.RaceDriveParams p = this.raceParams;
        net.shurui.shuruisutilities.racing.physics.KartState st = raceKartState();
        double vy = this.getDeltaMovement().y;

        if (p == null)
        {
            // Race bike whose params have not arrived yet (a client one tick before RaceInput pushes them): hold still.
            vy = raceVertical(vy, 0.0);
            this.setDeltaMovement(0.0, finiteOrZero(vy), 0.0);
            return;
        }

        // environment: the block the bike drives over (surface set membership + boost pad).
        double footY = this.getBoundingBox().minY;
        BlockState below = this.level().getBlockState(
                net.minecraft.core.BlockPos.containing(this.getX(), footY - 0.05D, this.getZ()));
        BlockState here = this.level().getBlockState(
                net.minecraft.core.BlockPos.containing(this.getX(), footY + 0.1D, this.getZ()));
        boolean onBoostPad = below.is(net.shurui.shuruisutilities.racing.RaceRegistries.RACE_BOOST_PAD.get())
                || here.is(net.shurui.shuruisutilities.racing.RaceRegistries.RACE_BOOST_PAD.get());
        boolean onSurface = onBoostPad || raceSurfaceContains(below) || raceSurfaceContains(here);

        boolean jumpPressed = this.inputJump && !this.raceJumpWasHeld;
        this.raceJumpWasHeld = this.inputJump;

        net.shurui.shuruisutilities.racing.physics.KartPhysics.Input in =
                new net.shurui.shuruisutilities.racing.physics.KartPhysics.Input(
                        this.inputUp, this.inputDown, this.inputLeft, this.inputRight, this.inputJump, jumpPressed,
                        this.onGround());
        net.shurui.shuruisutilities.racing.physics.KartPhysics.Env env =
                new net.shurui.shuruisutilities.racing.physics.KartPhysics.Env(onSurface, onBoostPad,
                        this.horizontalCollision);

        net.shurui.shuruisutilities.racing.physics.KartPhysics.Output out =
                net.shurui.shuruisutilities.racing.physics.KartPhysics.step(st, in, env, p, this.getYRot());

        // heading from steering (not the rider's view); flat pitch.
        this.setYRot(Mth.wrapDegrees(this.getYRot() + (float) out.yawDelta()));
        this.setXRot(0.0F);

        vy = raceVertical(vy, out.hopImpulse());

        this.setDeltaMovement(finiteOrZero(out.horizontal().x), finiteOrZero(vy), finiteOrZero(out.horizontal().z));
        setRaceFx(out.fxFlags());

        // The rider's client tells the server about drift start / release (server validates the mini-turbo, R5).
        if (this.level().isClientSide)
        {
            if (out.startDrift())
                net.shurui.shuruisutilities.commons.network.NetworkUtils.sendToServer(
                        new net.shurui.shuruisutilities.racing.net.PacketRaceDrift(
                                net.shurui.shuruisutilities.racing.net.PacketRaceDrift.START, 0));
            if (out.releaseDrift())
                net.shurui.shuruisutilities.commons.network.NetworkUtils.sendToServer(
                        new net.shurui.shuruisutilities.racing.net.PacketRaceDrift(
                                net.shurui.shuruisutilities.racing.net.PacketRaceDrift.RELEASE, out.releaseTier()));
        }
    }

    // hover gravity for a race bike (kart hop owns the up-impulse). Kept simple: gentle pull, damped, no water spring.
    private double raceVertical(double vy, double hopImpulse)
    {
        if (!this.onGround())
            vy -= 0.035D;
        vy *= 0.90D;
        if (this.onGround() && vy < 0)
            vy = 0.0D;
        boolean grounded = this.onGround();
        if (hopImpulse > 0.0D && grounded && hasHeadroom(hopImpulse))
            vy = hopImpulse;
        if (vy > 0.0D && !hasHeadroom(vy))
            vy = 0.0D;
        return vy;
    }

    private boolean raceSurfaceContains(BlockState state)
    {
        // No surface set delivered yet: treat everything as on-track (no spurious off-road at spawn).
        if (this.raceSurface == null)
            return true;
        return this.raceSurface.contains(state.getBlock());
    }

    // corner re-press guard: damp any horizontal axis sweepMove zeroed last tick while the rider is still pushing
    // that way. otherwise the accel-ease rebuilds the full target each tick and slams back into the seam; scaling
    // it to GLIDE_REPRESS_SCALE lets it glide along. self-clearing when the input sign flips or the axis frees up.
    private Vec3 applyGlideMemory(Vec3 target)
    {
        double tx = target.x;
        double tz = target.z;

        // only damp while the target still points the blocked way; else release the latch
        if (this.glideBlockedX != 0.0D)
        {
            if (Math.signum(tx) == this.glideBlockedX)
                tx *= GLIDE_REPRESS_SCALE;
            else
                this.glideBlockedX = 0.0D;
        }
        // Z axis: same, latched independently
        if (this.glideBlockedZ != 0.0D)
        {
            if (Math.signum(tz) == this.glideBlockedZ)
                tz *= GLIDE_REPRESS_SCALE;
            else
                this.glideBlockedZ = 0.0D;
        }
        return new Vec3(tx, target.y, tz);
    }

    // max total step-up rise/tick across sub-steps. maxUpStep is 1.0 so a real single ledge never tops ~1.0;
    // past this the sub-steps are stair-climbing multiple blocks in one tick, so revert to one move().
    private static final double MAX_TICK_STEP_RISE = 1.1D;

    // authoritative move that sub-steps a fast delta so the bike sweeps walls instead of tunnelling. each move()
    // resolves per-axis sliding itself, so DON'T bail on the first horizontalCollision (that ground it to a halt
    // on surfaces it should slide along); only abort the rest on near-zero displacement (genuinely wedged).
    // after the sweep, if we collided, zero deltaMovement on any axis with significant intent but ~0 actual so
    // the eased velocity stops pressing into the wall and fresh input redirects cleanly.
    // step-up guard: each sub-step can fire maxUpStep, so a run could climb several blocks; if total rise tops
    // MAX_TICK_STEP_RISE, revert and re-run as one move() (caps step-up at one ledge).
    private void sweepMove(Vec3 delta)
    {
        double horizLen = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double threshold = SUBSTEP_WIDTH_FRACTION * this.getBbWidth();

        if (horizLen <= threshold)
        {
            this.move(MoverType.SELF, delta);
            return;
        }

        int steps = Mth.ceil(horizLen / threshold);
        Vec3 sub = delta.scale(1.0D / steps);

        // sweep start, for measuring actual vs intended and for a full revert
        double startX = this.getX();
        double startY = this.getY();
        double startZ = this.getZ();

        boolean wedged = false;

        // judge blocking on the FINAL executed movement (last sub-move + any corner-escape moves), not the sweep
        // aggregate: if early steps were blocked but it slid/escaped free by tick end, the aggregate still reads ~0
        // and would wrongly zero + latch glide (the rubber-band-at-a-cleared-corner bug).
        boolean finalCollision = false;
        double finalIntendX = 0.0D;
        double finalIntendZ = 0.0D;
        double finalActualX = 0.0D;
        double finalActualZ = 0.0D;

        for (int i = 0; i < steps; i++)
        {
            double beforeX = this.getX();
            double beforeY = this.getY();
            double beforeZ = this.getZ();

            this.move(MoverType.SELF, sub);

            // capture this move's collision flag + intent/displacement as current end-of-sweep state; a later
            // sub-step or corner-escape overwrites it, so what survives the loop is the final movement's
            finalCollision = this.horizontalCollision;
            finalIntendX = sub.x;
            finalIntendZ = sub.z;
            finalActualX = this.getX() - beforeX;
            finalActualZ = this.getZ() - beforeZ;

            double dx = finalActualX;
            double dy = this.getY() - beforeY;
            double dz = finalActualZ;

            // near-zero displacement: genuine wedge, OR a corner false-wedge where vanilla's axis-ordered resolve
            // zeroed both axes because the box clipped the lip on the first axis while the other was free. before
            // aborting, retry axis-isolated (larger |component| first); if either frees up, keep sweeping. only
            // abort if both isolated axes are truly blocked.
            if ((dx * dx + dy * dy + dz * dz) < 1.0E-8)
            {
                double escBeforeX = this.getX();
                double escBeforeZ = this.getZ();
                if (!tryCornerEscape(sub))
                {
                    // both isolated axes blocked: sweep ends here, wedged. final move's outcome stands.
                    finalCollision = this.horizontalCollision;
                    finalActualX = this.getX() - escBeforeX;
                    finalActualZ = this.getZ() - escBeforeZ;
                    wedged = true;
                    break;
                }
                // an axis freed via corner escape: those moves are now the final movement, so read their flag +
                // displacement (reflects the freed corner, not the stalled sub-step before it)
                finalCollision = this.horizontalCollision;
                finalActualX = this.getX() - escBeforeX;
                finalActualZ = this.getZ() - escBeforeZ;
                // keep running the remaining sub-steps
            }
        }

        // step-up guard: rise over one ledge means the sub-steps climbed several blocks; revert + one move()
        if ((this.getY() - startY) > MAX_TICK_STEP_RISE)
        {
            this.setPos(startX, startY, startZ);
            this.move(MoverType.SELF, delta);
            return;
        }

        // blocked-axis handling, judged on the final movement not the aggregate: an axis is still blocked only if
        // the final move (or its escape) collided AND got ~0 displacement despite real intent. if the tick ended
        // moving freely, keep all momentum and clear both latches (the rubber-band-after-a-cleared-corner fix).
        boolean blockedX = finalCollision
                && Math.abs(finalIntendX) > 1.0E-4 && Math.abs(finalActualX) < 1.0E-4;
        boolean blockedZ = finalCollision
                && Math.abs(finalIntendZ) > 1.0E-4 && Math.abs(finalActualZ) < 1.0E-4;

        if (this.waterExitAssistThisTick)
        {
            // shore-exit tick: the collision is the lip the climb impulse is lifting over. bypass all zeroing/
            // latching, keep forward velocity, clear latches + counters so next tick resumes at full.
            this.glideBlockedX = 0.0D;
            this.glideBlockedZ = 0.0D;
            this.consecutiveBlockedX = 0;
            this.consecutiveBlockedZ = 0;
        }
        else if (blockedX || blockedZ)
        {
            Vec3 dm = this.getDeltaMovement();
            double vx = dm.x;
            double vz = dm.z;

            // soften the still-blocked case: keep a little push for a re-slide, hard-zero only after
            // CONSECUTIVE_BLOCK_HARD_ZERO+ blocked ticks. counter bumps on a blocked tick, resets on a free one.
            if (blockedX)
            {
                this.consecutiveBlockedX++;
                vx *= (this.consecutiveBlockedX >= CONSECUTIVE_BLOCK_HARD_ZERO) ? 0.0D : BLOCKED_AXIS_VELOCITY_SCALE;
            }
            else
            {
                this.consecutiveBlockedX = 0;
            }
            if (blockedZ)
            {
                this.consecutiveBlockedZ++;
                vz *= (this.consecutiveBlockedZ >= CONSECUTIVE_BLOCK_HARD_ZERO) ? 0.0D : BLOCKED_AXIS_VELOCITY_SCALE;
            }
            else
            {
                this.consecutiveBlockedZ = 0;
            }
            this.setDeltaMovement(vx, dm.y, vz);

            // latch glide only for the axis that ended blocked; a freed axis records 0 and self-clears
            this.glideBlockedX = blockedX ? Math.signum(finalIntendX) : 0.0D;
            this.glideBlockedZ = blockedZ ? Math.signum(finalIntendZ) : 0.0D;
        }
        else
        {
            // ended free on both axes: keep all momentum, release latches, reset counters. no zeroing.
            this.glideBlockedX = 0.0D;
            this.glideBlockedZ = 0.0D;
            this.consecutiveBlockedX = 0;
            this.consecutiveBlockedZ = 0;
        }

        // 'wedged' is informational; the loop already broke early on a genuine wedge
    }

    // corner false-wedge escape: retry the sub-step one axis at a time (larger |component| first) so a corner
    // vanilla's axis-ordered collide zeroed on the first axis (while the other was free) isn't read as a wedge.
    // true if either isolated axis moved (>1e-4) so the sweep continues; false only if both are truly blocked.
    private boolean tryCornerEscape(Vec3 sub)
    {
        boolean moved = false;

        if (Math.abs(sub.x) >= Math.abs(sub.z))
        {
            moved |= tryIsolatedAxisMove(sub.x, 0.0D);
            moved |= tryIsolatedAxisMove(0.0D, sub.z);
        }
        else
        {
            moved |= tryIsolatedAxisMove(0.0D, sub.z);
            moved |= tryIsolatedAxisMove(sub.x, 0.0D);
        }
        return moved;
    }

    // single-axis move; true if it moved (>1e-4, axis was free)
    private boolean tryIsolatedAxisMove(double dx, double dz)
    {
        if (dx == 0.0D && dz == 0.0D)
            return false;
        double bx = this.getX();
        double bz = this.getZ();
        this.move(MoverType.SELF, new Vec3(dx, 0.0D, dz));
        double mx = this.getX() - bx;
        double mz = this.getZ() - bz;
        return (mx * mx + mz * mz) > 1.0E-8;
    }

    // true when submerged enough to ride the surface. isInWater() alone flips off the instant buoyancy lifts the
    // base clear (then it drops back in), so also accept a water column whose surface is at/above the origin.
    //
    // Takes the already-probed surface rather than probing again, so the caller's spring is guaranteed to be
    // working from the same reading this answered with. The two used to be separate calls and disagreeing was a
    // player-breaking bug, not a cosmetic one.
    /** A velocity component, or zero if it is NaN or infinite. See the note at the end of driveMovement. */
    private static double finiteOrZero(double v)
    {
        return Double.isFinite(v) ? v : 0.0D;
    }

    private boolean isFloatingOnWater(double surface)
    {
        if (this.isInWater())
            return true;
        return surface >= this.getY() - 0.05D;
    }

    // true if the box raised by rise blocks is collision-free (room to move up). gates jump + water spring.
    private boolean hasHeadroom(double rise)
    {
        if (rise <= 0.0D)
            return true;
        return this.level().noCollision(this, this.getBoundingBox().move(0.0D, rise, 0.0D));
    }

    // shore-exit probe: given the normalized move dir, return the smallest rise that clears a low ledge ahead
    // (SHORE_RISE_LOW before HIGH), or 0 if nothing to climb. "blocked flat but free when raised" is a steppable
    // lip; a full wall is blocked at every height so no rise passes.
    private double shoreExitRise(double dirX, double dirZ)
    {
        double offX = dirX * SHORE_PROBE_DISTANCE;
        double offZ = dirZ * SHORE_PROBE_DISTANCE;

        // something ahead must actually block us (last move collided, or the forward-offset box isn't clear);
        // if the flat path is already open there's nothing to climb
        boolean blockedAhead = this.horizontalCollision
                || !this.level().noCollision(this, this.getBoundingBox().move(offX, 0.0D, offZ));
        if (!blockedAhead)
            return 0.0D;

        // smallest rise that opens the path wins (low ledge first, then taller lip)
        if (this.level().noCollision(this, this.getBoundingBox().move(offX, SHORE_RISE_LOW, offZ)))
            return SHORE_RISE_LOW;
        if (this.level().noCollision(this, this.getBoundingBox().move(offX, SHORE_RISE_HIGH, offZ)))
            return SHORE_RISE_HIGH;
        return 0.0D;
    }

    // world Y of the water surface at the bike's x/z, or NEGATIVE_INFINITY if no water there
    private double waterSurfaceY()
    {
        net.minecraft.core.BlockPos.MutableBlockPos pos =
                new net.minecraft.core.BlockPos.MutableBlockPos(Mth.floor(this.getX()), Mth.floor(this.getY()), Mth.floor(this.getZ()));
        // probe origin block + the one below so a bike resting just above the surface still sees water
        for (int i = 0; i <= 1; i++)
        {
            net.minecraft.world.level.material.FluidState fluid = this.level().getFluidState(pos);
            if (!fluid.isEmpty() && fluid.is(net.minecraft.tags.FluidTags.WATER))
                return pos.getY() + fluid.getHeight(this.level(), pos);
            pos.move(0, -1, 0);
        }
        return Double.NEGATIVE_INFINITY;
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
}
