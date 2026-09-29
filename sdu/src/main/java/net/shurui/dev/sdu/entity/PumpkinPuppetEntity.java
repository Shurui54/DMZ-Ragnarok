package net.shurui.dev.sdu.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A Duke Snipperjack minion: the pumpkin puppet, converted from the pack's {@code ds_pumpkin_puppet} ModelEngine
 * rig to a GeckoLib entity. It walks at the nearest player, does a light melee bite, and can be "primed" by the
 * boss to detonate (or detonates on death), dealing an area burst.
 *
 * <p>Adaptation note: in the MythicMobs pack a puppet is a ZOMBIE template driven by MythicMobs skill lines. Here
 * it is a plain {@link Monster} with vanilla goals so it works with DragonMineZ combat without the plugin. Its
 * melee damage and explosion damage scale with {@link #powerScale}, which the boss copies from its own scaling so
 * the whole encounter tracks the arena's power level. Damage to players is dealt with a mob-attack source so it
 * flows through DMZ's damage pipeline exactly like any other mob hit.
 */
public class PumpkinPuppetEntity extends Monster implements GeoEntity {

    private static final EntityDataAccessor<Float> POWER =
            SynchedEntityData.defineId(PumpkinPuppetEntity.class, EntityDataSerializers.FLOAT);
    /** 0 idle/walk, 1 exploding (client plays the explode clip). */
    private static final EntityDataAccessor<Integer> ACTION =
            SynchedEntityData.defineId(PumpkinPuppetEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation MELEE = RawAnimation.begin().thenPlay("melee");
    private static final RawAnimation EXPLODE = RawAnimation.begin().thenPlayAndHold("explode");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** >0 while a detonation is in progress; counts down to the actual burst then removal. */
    private int fuse = -1;
    private int meleeAnimTicks = 0;

    public PumpkinPuppetEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        // Scaled by the boss's one SCALE constant so the entourage's hitbox matches its (scaled) render.
        return super.getDimensions(pose).scale(DukeSnipperjackEntity.SCALE);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.4);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(POWER, 1.0f);
        this.entityData.define(ACTION, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.25, true));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    public void setPowerScale(float scale) {
        this.entityData.set(POWER, scale <= 0 ? 1.0f : scale);
    }

    public float getPowerScale() {
        return this.entityData.get(POWER);
    }

    /** Start the detonation sequence (used by the boss's "prime the puppets" skill). */
    public void prime() {
        if (fuse < 0 && isAlive()) {
            fuse = 30;
            this.entityData.set(ACTION, 1);
            this.setDeltaMovement(Vec3.ZERO);
            playSound(SoundEvents.CREEPER_PRIMED, 1.0f, 0.7f);
        }
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        if (level().isClientSide() || fuse >= 0) {
            return false;
        }
        meleeAnimTicks = 8;
        playSound(SoundEvents.BLAZE_AMBIENT, 1.0f, 1.3f);
        if (target instanceof LivingEntity living) {
            float dmg = (float) (4.0 * getPowerScale());
            return living.hurt(damageSources().mobAttack(this), dmg);
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        if (meleeAnimTicks > 0) {
            meleeAnimTicks--;
        }
        if (fuse >= 0) {
            this.setDeltaMovement(Vec3.ZERO);
            if (fuse == 0) {
                detonate();
            }
            fuse--;
        }
    }

    private void detonate() {
        float scale = getPowerScale();
        // Blast radius grows with the boss's size constant so the hit zone matches the bigger puppet and its burst.
        double r = 4.0 * DukeSnipperjackEntity.SCALE;
        for (Player p : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(r))) {
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) {
                continue;
            }
            double d = distanceTo(p);
            if (d > r) {
                continue;
            }
            float dmg = (float) (7.0 * scale * (1.0 - d / (r + 1.0)));
            p.hurt(damageSources().mobAttack(this), Math.max(1.0f, dmg));
        }
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SCULK_SOUL,
                    getX(), getY() + 0.6, getZ(), 24, 0.4, 0.4, 0.4, 0.05);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL,
                    getX(), getY() + 0.5, getZ(), 12, 0.6, 0.2, 0.6, 0.02);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.4f, 1.0f);
        discard();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // A puppet that is already detonating cannot be interrupted, so its burst always lands.
        if (fuse >= 0 && !source.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    public void die(DamageSource source) {
        // A puppet killed by players still bursts, mirroring the pack's on-death explosion, but only if it was
        // not already primed (that path removes it in detonate()).
        if (!level().isClientSide() && fuse < 0) {
            detonate();
        }
        super.die(source);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("ds_power", getPowerScale());
        tag.putInt("ds_fuse", fuse);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("ds_power")) {
            setPowerScale(tag.getFloat("ds_power"));
        }
        fuse = tag.contains("ds_fuse") ? tag.getInt("ds_fuse") : -1;
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 3, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        if (this.entityData.get(ACTION) == 1) {
            state.getController().setAnimation(EXPLODE);
            return PlayState.CONTINUE;
        }
        if (meleeAnimTicks > 0) {
            state.getController().setAnimation(MELEE);
            return PlayState.CONTINUE;
        }
        if (state.isMoving()) {
            state.getController().setAnimation(WALK);
        } else {
            state.getController().setAnimation(IDLE);
        }
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
