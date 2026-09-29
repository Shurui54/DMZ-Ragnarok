package net.shurui.dev.sdu.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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
 * Duke Snipperjack: a rift boss converted from the PixelBarrel MythicMobs + ModelEngine pack to a native
 * DragonMineZ-compatible GeckoLib entity for 1.20.1 Forge. This reimplements the MythicMobs FIGHT (Mobs +
 * DS_BossSkills/DS_PhaseControlSkills/DS_VFXSkills/DS_MinionSkills) as close to 1:1 as a native entity can get:
 * the same stance ladder, thresholds, per-skill gcd and cooldown timings, damage numbers, cones/ranges,
 * telegraph animations, sounds and particle beats, and the interlude's "clear the adds to advance" shape.
 *
 * <h2>Stance ladder (matches the pack's setstance/model swaps)</h2>
 * <ol>
 *   <li>{@link #ST_PREFIGHT}: seated at his tea on the phase-0 rig, fully immobile and immune. He only rises when
 *       a PLAYER attacks or interacts with him (never on a timer), taking zero damage from the provoking hit.</li>
 *   <li>{@link #ST_RISING}: the pack's DS_Phase_1 stand-up, played as the phase-0 rig's {@code phase1} clip.</li>
 *   <li>{@link #ST_PHASE1}: 100-65%. Melee/Melee2, and a random roll of KnifeThrow / SummonPuppet / CloneSwap,
 *       plus a single SummonClone below 90%.</li>
 *   <li>{@link #ST_INTERLUDE}: below 65%. He sits back at his tea (phase-0 rig, immune) and three interlude clones
 *       (melee / puppet / knife) spawn; the party must clear them to advance.</li>
 *   <li>{@link #ST_TO_PHASE2}: the pack's DS_Phase_2 re-arm, played as the phase-0 rig's {@code phase2} clip.</li>
 *   <li>{@link #ST_PHASE2}: 65-35%. Melee, and a random roll of ScissorTeleport / RaisePuppet / SpookyAssault.</li>
 *   <li>{@link #ST_TO_PHASE3}: the pack's DS_Phase_3 monocle-shatter, the phase-2 rig's {@code phase3} clip.</li>
 *   <li>{@link #ST_PHASE3}: below 35%, faster. Melee, a random roll of PrimePuppet / FallingScissor /
 *       ScissorTeleport, and the occasional Mirage after-image.</li>
 *   <li>{@link #ST_DEAD}: the death sequence and the signature blade drop.</li>
 * </ol>
 *
 * <h2>What is faithful and what is adapted</h2>
 * Every attack plays the ModelEngine animation of the same name (converted 1:1) with the pack's telegraph delay,
 * damage window, cone/range, sounds and particle beats. The ModelEngine VFX helper armour-stands (knife, scissor,
 * cyclone, soul ring, thrust wave) do not exist without the plugins, so they are reproduced as boss-driven
 * particle-and-area effects. The pumpkin puppet minion IS a real entity ({@link PumpkinPuppetEntity}). The pack's
 * clone doubles and mirages (full AI copies of the boss) are adapted to blink/after-image bursts; the interlude
 * keeps its "clear the adds to advance" shape with a puppet-and-clone wave. Each adaptation is noted at its skill.
 *
 * <h2>Scaling</h2>
 * Health is whatever the rift/raid editor set (applied to MAX_HEALTH by the raid system). The pack's small
 * MythicMobs damage numbers are multiplied by a DMZ unit and a {@link #getPowerScale power scale} derived from
 * that health, so a beefier configured boss hits proportionally harder. The unit is overridable per spawn through
 * the persistent-data key {@code ds_damage_unit}. All player damage is dealt with a mob-attack source so it passes
 * through DMZ's damage pipeline like any other mob hit.
 */
public class DukeSnipperjackEntity extends PathfinderMob implements GeoEntity {

    // Stance values; also drive the geo/animation rig swap. Public so the model/renderer can read them.
    public static final int ST_PREFIGHT = 0;
    public static final int ST_RISING = 1;
    public static final int ST_PHASE1 = 2;
    public static final int ST_INTERLUDE = 3;
    public static final int ST_TO_PHASE2 = 4;
    public static final int ST_PHASE2 = 5;
    public static final int ST_TO_PHASE3 = 6;
    public static final int ST_PHASE3 = 7;
    public static final int ST_DEAD = 8;

    /** Rig ids the model resolver maps a stance to: 0 = phase-0 tea rig, 1 = phase-1 rig, 2 = phase-2/3 rig. */
    public static final int RIG_PHASE0 = 0;
    public static final int RIG_PHASE1 = 1;
    public static final int RIG_PHASE2 = 2;

    /**
     * The ONE place the whole fight's size is defined. The renderer scales the GeckoLib model by this, the
     * entity scales its bounding box / eye height by this (so he is clickable and hittable where he appears),
     * {@link PumpkinPuppetEntity} and the snipperjack renderers read it so the entourage stays in proportion,
     * and the attack reach/area is multiplied by it so the hit zones still agree with the (bigger) visuals.
     * Retune the whole boss here. 1.0 is the pack's original size; 1.5 makes him read as a boss, not a miniboss.
     */
    public static final float SCALE = 1.5f;

    private static final EntityDataAccessor<Integer> STANCE =
            SynchedEntityData.defineId(DukeSnipperjackEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> ACTION =
            SynchedEntityData.defineId(DukeSnipperjackEntity.class, EntityDataSerializers.STRING);
    /** Bumped whenever a new action starts so the client re-triggers even when the name repeats. */
    private static final EntityDataAccessor<Integer> ACTION_ID =
            SynchedEntityData.defineId(DukeSnipperjackEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> POWER =
            SynchedEntityData.defineId(DukeSnipperjackEntity.class, EntityDataSerializers.FLOAT);

    /** MythicMobs damage numbers assume ~20 HP players; this converts them to DMZ-meaningful damage. */
    private static final double DMZ_DAMAGE_UNIT = 6.0;
    /** Boss health that maps to powerScale 1.0 (the pack ships 1500). */
    private static final double REFERENCE_HEALTH = 1500.0;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossBar = new ServerBossEvent(
            Component.literal("Duke Snipperjack"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);

    /** Global cooldown / "busy": while >0 the boss is committed to an attack and does not move or start another. */
    private int gcd = 0;
    /** Ticks until the next melee attempt (~onTimer:20). */
    private int meleeTimer = 20;
    /** Ticks until the next special-skill attempt (~onTimer:15). */
    private int specialTimer = 30;
    /** Per-roll cooldown on the random special (the pack's cd=7 on the randomskill line). */
    private int specialCd = 0;
    /** Melee cooldown (the pack's Cooldown:3 on DS_Melee). */
    private int meleeCd = 0;
    /** SummonClone cooldown (the pack's gcd + the score{clone<1} gate; one clone at a time). */
    private int cloneCd = 0;
    /** Mirage cooldown (phase 3, the pack's cd=3 on DS_Mirage). */
    private int mirageCd = 0;
    /** Ticks left in a stance transition (rising / re-arm / phase-3 shatter) before the next stance begins. */
    private int transitionTimer = 0;
    /** puppet score cap, mirroring the pack's score{o=puppet}. */
    private int puppetScore = 0;
    private int cloneScore = 0;

    private boolean tauntedOnce = false;
    private double damageUnit = DMZ_DAMAGE_UNIT;

    /** Interlude bookkeeping: the adds that must die before phase 2 may begin. */
    private final List<java.util.UUID> interludeAdds = new ArrayList<>();
    private int interludeGrace = 0;

    /** Simple delayed-task scheduler so an attack can lay out its damage/sound/particle beats over time. */
    private static final class Task {
        int delay;
        final Runnable run;
        Task(int delay, Runnable run) { this.delay = delay; this.run = run; }
    }
    private final List<Task> tasks = new ArrayList<>();
    private int invulnWindow = 0;
    /** Ticks during which the cast-root does NOT zero his horizontal motion, so a leap (Melee2) can carry. */
    private int leapGrace = 0;

    public DukeSnipperjackEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 0;
        this.bossBar.setDarkenScreen(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1500.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        // Scale the hitbox and eye height with the render scale so he is clickable/hittable where he is drawn.
        return super.getDimensions(pose).scale(SCALE);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STANCE, ST_PREFIGHT);
        this.entityData.define(ACTION, "");
        this.entityData.define(ACTION_ID, 0);
        this.entityData.define(POWER, 1.0f);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // A real melee-attack goal so he closes on and paths to his target between casts, the way the pack's
        // runaigoalselector{goal=meleeattack} turns the vanilla AI back on once he is up from his tea.
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, true));
        this.goalSelector.addGoal(4, new MoveTowardsTargetGoal(this, 1.0, 40.0F));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 24.0F));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ------------------------------------------------------------------ synced accessors

    public int getStance() {
        return this.entityData.get(STANCE);
    }

    /** Which of the three rigs draws this stance: pre-fight/interlude/transitions use the phase-0 tea rig. */
    public int getRigPhase() {
        return switch (getStance()) {
            case ST_PHASE1 -> RIG_PHASE1;
            case ST_PHASE2, ST_TO_PHASE3, ST_PHASE3 -> RIG_PHASE2;
            default -> RIG_PHASE0; // PREFIGHT, RISING, INTERLUDE, TO_PHASE2, DEAD
        };
    }

    private void setStance(int stance) {
        this.entityData.set(STANCE, stance);
    }

    /** True while he is seated at his tea and must not move, be pushed, or take damage. */
    public boolean isSeated() {
        int s = getStance();
        return s == ST_PREFIGHT || s == ST_INTERLUDE;
    }

    public String getAction() {
        return this.entityData.get(ACTION);
    }

    public int getActionId() {
        return this.entityData.get(ACTION_ID);
    }

    public float getPowerScale() {
        return this.entityData.get(POWER);
    }

    /** Start a transient animation on every viewer. */
    private void play(String action) {
        this.entityData.set(ACTION, action == null ? "" : action);
        this.entityData.set(ACTION_ID, this.entityData.get(ACTION_ID) + 1);
    }

    // ------------------------------------------------------------------ scaling

    private void ensureScaled() {
        double maxHp = getMaxHealth();
        float want = (float) Math.max(0.5, Math.min(8.0, maxHp / REFERENCE_HEALTH));
        if (Math.abs(want - getPowerScale()) > 0.01f) {
            this.entityData.set(POWER, want);
        }
        if (getPersistentData().contains("ds_damage_unit")) {
            damageUnit = getPersistentData().getDouble("ds_damage_unit");
        }
    }

    private float dmg(double mythicBase) {
        return (float) (mythicBase * damageUnit * getPowerScale());
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        bossBar.setName(Component.literal("Duke Snipperjack " + stanceLabel()));
        bossBar.setProgress(Math.max(0f, getHealth() / getMaxHealth()));
        ensureScaled();
        runTasks();
        tickCooldowns();

        if (isSeated()) {
            // Completely immobile at his tea: no navigation, no drift, and (via setNoAi below) no goals. Gravity
            // still applies so he rests on the block he was seated on.
            this.setDeltaMovement(this.getDeltaMovement().multiply(0.0, 1.0, 0.0));
            this.getNavigation().stop();
            if (!isNoAi()) {
                setNoAi(true);
            }
            if (getStance() == ST_INTERLUDE) {
                tickInterlude();
            }
            return;
        }
        if (isNoAi()) {
            setNoAi(false);
        }

        if (gcd > 0) {
            gcd--;
            // Rooted while committed to an attack, mirroring the pack's SLOW-during-cast, EXCEPT during a leap
            // window so Melee2's lunge can carry him. Keep facing the target so the directional clips (thrust and
            // melee lunges, all authored toward the model's -Z front) read forward instead of firing at wherever
            // his body last happened to point.
            if (leapGrace > 0) {
                leapGrace--;
            } else {
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.0, 1.0, 0.0));
                this.getNavigation().stop();
            }
            LivingEntity t = getTarget();
            if (t != null) {
                faceTarget(t);
            }
        }

        switch (getStance()) {
            case ST_RISING, ST_TO_PHASE2, ST_TO_PHASE3 -> tickTransition();
            case ST_PHASE1 -> tickPhaseOne();
            case ST_PHASE2 -> tickPhaseTwo();
            case ST_PHASE3 -> tickPhaseThree();
            default -> { }
        }
    }

    private void tickCooldowns() {
        if (specialCd > 0) specialCd--;
        if (meleeCd > 0) meleeCd--;
        if (cloneCd > 0) cloneCd--;
        if (mirageCd > 0) mirageCd--;
    }

    private String stanceLabel() {
        return switch (getStance()) {
            case ST_PHASE1 -> "(Phase I)";
            case ST_INTERLUDE -> "(Interlude)";
            case ST_TO_PHASE2 -> "(Phase II)";
            case ST_PHASE2 -> "(Phase II)";
            case ST_TO_PHASE3, ST_PHASE3 -> "(Phase III)";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ pre-fight (seated at tea)

    /**
     * A player provoked the Duke: taunt for the first two pokes (the pack's tea_convo score gate), then rise.
     * Returns true if the provoking hit/interaction was consumed by the pre-fight (so it deals no damage).
     */
    private boolean provoke(Player by) {
        if (getStance() != ST_PREFIGHT) {
            return false;
        }
        if (!tauntedOnce) {
            tauntedOnce = true;
            broadcast("Do not interrupt a gentleman at his tea, would you?");
            level().playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1f, 1f);
            return true;
        }
        beginRising();
        return true;
    }

    private void beginRising() {
        setNoAi(false);
        setStance(ST_RISING);
        // DS_Phase_1 plays the 'phase1' stand-up on the phase-0 tea rig, then swaps to the phase-1 rig.
        play("phase1");
        broadcast("Tsk, tsk. Such a pity you have come all this way just to make a fool of yourself.");
        level().playSound(null, blockPosition(), SoundEvents.ENDER_EYE_DEATH, SoundSource.HOSTILE, 2f, 1f);
        transitionTimer = 130; // matches DS_Phase_1's delay 130 before the model swap
        gcd = 130;
    }

    private void tickTransition() {
        if (transitionTimer > 0) {
            transitionTimer--;
            return;
        }
        switch (getStance()) {
            case ST_RISING -> enterPhaseOne();
            case ST_TO_PHASE2 -> enterPhaseTwo();
            case ST_TO_PHASE3 -> enterPhaseThree();
            default -> { }
        }
    }

    private void enterPhaseOne() {
        setStance(ST_PHASE1);
        vfxTeleport();
        gcd = 20;
        specialTimer = 40;
        meleeTimer = 20;
    }

    // ------------------------------------------------------------------ phase 1 (100-65%)

    private void tickPhaseOne() {
        if (healthFraction() <= 0.65f) {
            beginInterlude();
            return;
        }
        LivingEntity target = nearestTarget();
        if (target == null || gcd > 0) {
            return;
        }
        // SummonClone: one clone once he is below 90% (DS_SummonClone, score{clone<1}).
        if (cloneScore < 1 && cloneCd <= 0 && healthFraction() < 0.90f) {
            skillSummonClone();
            return;
        }
        if (--meleeTimer <= 0) {
            meleeTimer = 20;
            if (meleeCd <= 0 && distanceTo(target) <= reach(4.5)) {
                meleeCd = 60;
                // 30% chance the pack casts Melee2 (the thrust/leap) instead of the basic swing.
                if (random.nextFloat() < 0.30f) {
                    skillMelee2(target);
                } else {
                    skillMelee(target, 5.0, reach(4.5), 90);
                }
                return;
            }
        }
        if (--specialTimer <= 0) {
            specialTimer = 15;
            if (specialCd <= 0 && random.nextFloat() < 0.70f) {
                specialCd = 140; // cd=7 on the randomskill line
                switch (random.nextInt(3)) {
                    case 0 -> skillKnifeThrow(target);
                    case 1 -> skillSummonPuppet(target);
                    default -> skillCloneSwap(target);
                }
            }
        }
    }

    // ------------------------------------------------------------------ interlude (< 65%)

    private void beginInterlude() {
        setStance(ST_INTERLUDE);
        play("pocket_watch");
        broadcast("Ah, it seems the moment has arrived for a nice cup of tea. Do keep my guests entertained.");
        vfxFade();
        // The pack removes his summons and zeroes the clone/puppet scores at the interlude.
        cloneScore = 0;
        puppetScore = 0;
        despawnMinions();
        interludeAdds.clear();
        interludeGrace = 40;
        gcd = 60;
        if (level() instanceof ServerLevel sl) {
            // Three interlude clones (melee / puppet / knife), the "clear the adds to advance" wave.
            for (int i = 0; i < 3; i++) {
                PumpkinPuppetEntity add = summonPuppetAt(sl, position().add(
                        (random.nextDouble() - 0.5) * 6, 0, (random.nextDouble() - 0.5) * 6));
                if (add != null) {
                    interludeAdds.add(add.getUUID());
                }
            }
        }
        // Sit back down at the tea (phase-0 rig, idle) once the pocket-watch beat has played.
        schedule(20, () -> play("idle"));
    }

    private void tickInterlude() {
        if (interludeGrace > 0) {
            interludeGrace--;
            return;
        }
        interludeAdds.removeIf(id -> {
            net.minecraft.world.entity.Entity e = ((ServerLevel) level()).getEntity(id);
            return !(e instanceof LivingEntity le) || !le.isAlive();
        });
        if (interludeAdds.isEmpty()) {
            beginToPhaseTwo();
        }
    }

    private void beginToPhaseTwo() {
        setNoAi(false);
        setStance(ST_TO_PHASE2);
        // DS_Phase_2 plays the 'phase2' re-arm on the phase-0 rig, then swaps to the phase-2 rig.
        play("phase2");
        broadcast("If only I could savour this cup of tea in peace. What a joy it would be.");
        level().playSound(null, blockPosition(), SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 2f, 1.5f);
        transitionTimer = 150; // DS_Phase_2 delay 150
        gcd = 150;
    }

    private void enterPhaseTwo() {
        setStance(ST_PHASE2);
        vfxTeleport();
        gcd = 40;
        specialTimer = 30;
        meleeTimer = 20;
    }

    // ------------------------------------------------------------------ phase 2 (65-35%)

    private void tickPhaseTwo() {
        if (healthFraction() <= 0.35f) {
            beginToPhaseThree();
            return;
        }
        LivingEntity target = nearestTarget();
        if (target == null || gcd > 0) {
            return;
        }
        if (--meleeTimer <= 0) {
            meleeTimer = 20;
            if (meleeCd <= 0 && distanceTo(target) <= reach(5.0)) {
                meleeCd = 60;
                skillMelee(target, 10.0, reach(5.0), 120);
                return;
            }
        }
        if (--specialTimer <= 0) {
            specialTimer = 15;
            if (specialCd <= 0 && random.nextFloat() < 0.70f) {
                specialCd = 140;
                switch (random.nextInt(3)) {
                    case 0 -> skillScissorTeleport(target);
                    case 1 -> skillRaisePuppets();
                    default -> skillSpookyAssault(target);
                }
            }
        }
    }

    // ------------------------------------------------------------------ phase 3 (< 35%)

    private void beginToPhaseThree() {
        setStance(ST_TO_PHASE3);
        // DS_Phase_3 stuns himself, shatters the monocle and plays 'phase3', then speeds up.
        play("phase3");
        broadcast("Ahh... a duel to the death, you say? How utterly thrilling.");
        level().playSound(null, blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 3f, 1f);
        particles(ParticleTypes.CRIT, 1.6, 12, 0.25);
        transitionTimer = 90; // DS_Phase_3 delay 70 + a beat
        gcd = 90;
    }

    private void enterPhaseThree() {
        setStance(ST_PHASE3);
        var spd = getAttribute(Attributes.MOVEMENT_SPEED);
        if (spd != null) {
            spd.setBaseValue(0.42); // the pack's setspeed 1.6 (of walking) faster gait
        }
        level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 3f, 1f);
        gcd = 20;
        specialTimer = 25;
        meleeTimer = 16;
    }

    private void tickPhaseThree() {
        LivingEntity target = nearestTarget();
        if (target == null || gcd > 0) {
            return;
        }
        // DS_Mirage: cd=3, ~onTimer:20 0.3.
        if (mirageCd <= 0 && random.nextFloat() < 0.30f) {
            mirageCd = 60;
            skillMirage(target);
            return;
        }
        if (--meleeTimer <= 0) {
            meleeTimer = 16;
            if (meleeCd <= 0 && distanceTo(target) <= reach(5.0)) {
                meleeCd = 60;
                skillMelee(target, 10.0, reach(5.0), 120);
                return;
            }
        }
        if (--specialTimer <= 0) {
            specialTimer = 15;
            if (specialCd <= 0 && random.nextFloat() < 0.70f) {
                specialCd = 140;
                switch (random.nextInt(3)) {
                    case 0 -> skillPrimePuppets();
                    case 1 -> skillFallingScissors(target);
                    default -> skillScissorTeleport(target);
                }
            }
        }
    }

    // ------------------------------------------------------------------ attacks (each maps to a DS_ skill)

    /** DS_Melee / DS_Melee_Phase2: gcd 35, damage at delay 9-15 in a cone. */
    private void skillMelee(LivingEntity target, double base, double range, int coneAngle) {
        gcd = 35;
        faceTarget(target);
        play("melee");
        level().playSound(null, blockPosition(), SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 1f, 1.3f);
        schedule(9, () -> {
            for (LivingEntity e : livingInCone(range, coneAngle)) {
                e.hurt(damageSources().mobAttack(this), dmg(base));
            }
            particles(ParticleTypes.SWEEP_ATTACK, 1.5, 1, 0.1);
            level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1f, 0.8f);
        });
    }

    /** DS_Melee2: gcd 35, thrust telegraph, a leap onto the target, then a wide 120-cone hit for 8. */
    private void skillMelee2(LivingEntity target) {
        gcd = 35;
        faceTarget(target);
        play("thrust");
        level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1f, 0.8f);
        schedule(15, () -> {
            level().playSound(null, blockPosition(), SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 1f, 1.3f);
            if (target != null && target.isAlive()) {
                leapToward(target, 1.0);
            }
        });
        schedule(17, () -> {
            for (LivingEntity e : livingInCone(reach(9.0), 120)) {
                e.hurt(damageSources().mobAttack(this), dmg(8.0));
            }
            particles(ParticleTypes.SWEEP_ATTACK, 1.5, 3, 0.2);
        });
    }

    /** DS_KnifeThrow: gcd 70, throw telegraph, then two thrown blades (repeat 2, interval 10) for 4 each. */
    private void skillKnifeThrow(LivingEntity target) {
        gcd = 70;
        faceTarget(target);
        play("throw");
        level().playSound(null, blockPosition(), SoundEvents.ENDER_EYE_DEATH, SoundSource.HOSTILE, 3f, 1f);
        for (int i = 0; i < 2; i++) {
            int t = 35 + i * 10;
            schedule(t, () -> {
                level().playSound(null, blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 3f, 1.5f);
                lineProjectile(target, 4.0, ParticleTypes.PORTAL);
            });
        }
    }

    /**
     * DS_SummonPuppet: gcd 115, a long puppet-conjure telegraph, then a projectile to the target that lands a
     * pumpkin puppet on it (score{puppet<3}). Adapted: the ModelEngine ds_vfx_puppet bullet is a particle streak.
     */
    private void skillSummonPuppet(LivingEntity target) {
        if (puppetScore >= 3) {
            return;
        }
        gcd = 115;
        faceTarget(target);
        play("puppet");
        level().playSound(null, blockPosition(), SoundEvents.ENDER_EYE_DEATH, SoundSource.HOSTILE, 3f, 1f);
        schedule(21, () -> level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2f, 1f));
        schedule(100, () -> {
            if (level() instanceof ServerLevel sl && target != null) {
                lineProjectile(target, 0.0, ParticleTypes.REVERSE_PORTAL);
                if (summonPuppetAt(sl, target.position()) != null) {
                    puppetScore++;
                }
                level().playSound(null, blockPosition(), SoundEvents.WOOL_STEP, SoundSource.HOSTILE, 2f, 1f);
            }
        });
    }

    /** DS_SummonClone: gcd 45, clone telegraph, then a single clone double. Adapted: an after-image + a puppet. */
    private void skillSummonClone() {
        cloneCd = 200;
        gcd = 45;
        play("clone");
        level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2f, 1f);
        schedule(32, () -> {
            afterImage();
            if (level() instanceof ServerLevel sl) {
                // The clone double in the pack fights alongside him; adapted to a summoned puppet ally so the
                // "there are two of him" pressure is a real extra body the party must deal with.
                if (summonPuppetAt(sl, position().add((random.nextDouble() - 0.5) * 4, 0, (random.nextDouble() - 0.5) * 4)) != null) {
                    cloneScore++;
                }
            }
        });
    }

    /** DS_CloneSwap: gcd 50, cyclone telegraph, a blink toward the target, then a 6-radius burst for 8. */
    private void skillCloneSwap(LivingEntity target) {
        gcd = 50;
        play("cyclone");
        level().playSound(null, blockPosition(), SoundEvents.BAT_TAKEOFF, SoundSource.HOSTILE, 2f, 0.7f);
        schedule(25, () -> {
            if (target != null) {
                blinkNear(target, reach(2.5));
            }
            vfxTeleport();
            for (LivingEntity e : livingInRadius(reach(6.0))) {
                e.hurt(damageSources().mobAttack(this), dmg(8.0));
            }
            particles(ParticleTypes.SWEEP_ATTACK, 1.0, 8, 0.6);
        });
    }

    /** DS_ScissorTeleport: gcd 40, a dashing scissor rush that damages 7 along the path, ending in a blink. */
    private void skillScissorTeleport(LivingEntity target) {
        gcd = 40;
        faceTarget(target);
        play("scissor_teleport");
        level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2f, 1f);
        // onTick path damage: three beats of a 3-radius 7-damage sweep as he dashes in.
        for (int i = 0; i < 3; i++) {
            schedule(6 + i * 5, () -> {
                for (LivingEntity e : livingInRadius(reach(3.0))) {
                    e.hurt(damageSources().mobAttack(this), dmg(7.0));
                }
                particles(ParticleTypes.SWEEP_ATTACK, 0.6, 2, 0.3);
                level().playSound(null, blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.7f, 1f);
            });
        }
        schedule(22, () -> {
            if (target != null) {
                blinkNear(target, reach(2.0));
            }
            vfxTeleport();
            play("scissor_teleport_end");
            level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2f, 0.7f);
        });
    }

    /** DS_RaisePuppet: gcd 20, raise telegraph, then three pumpkin puppets around him (score{puppet<1} gate). */
    private void skillRaisePuppets() {
        if (puppetScore >= 1) {
            return;
        }
        gcd = 20;
        play("raise");
        level().playSound(null, blockPosition(), SoundEvents.GHAST_HURT, SoundSource.HOSTILE, 1f, 0.7f);
        schedule(3, () -> {
            if (level() instanceof ServerLevel sl) {
                for (int i = 0; i < 3; i++) {
                    if (summonPuppetAt(sl, position().add(
                            (random.nextDouble() - 0.5) * 5, 0, (random.nextDouble() - 0.5) * 5)) != null) {
                        puppetScore++;
                    }
                }
            }
        });
    }

    /** DS_SpookyAssault: gcd 75, vanish (immune + invisible), blink onto the target, blind it and strike for 10. */
    private void skillSpookyAssault(LivingEntity target) {
        gcd = 75;
        play("hat");
        setInvulnerableWindow(46);
        addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false));
        level().playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 2f, 1f);
        vfxFade();
        schedule(45, () -> {
            if (target != null) {
                blinkNear(target, reach(1.5));
                target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
            }
            vfxTeleport();
            play("melee2");
            level().playSound(null, blockPosition(), SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 2f, 1.5f);
            schedule(20, () -> {
                for (LivingEntity e : livingInCone(reach(6.0), 120)) {
                    e.hurt(damageSources().mobAttack(this), dmg(10.0));
                }
            });
        });
    }

    /** DS_PrimePuppet: gcd 40, prime telegraph, then signal every pumpkin puppet to detonate. */
    private void skillPrimePuppets() {
        if (puppetScore <= 0) {
            return;
        }
        gcd = 40;
        play("prime");
        level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2f, 1f);
        schedule(15, () -> {
            if (level() instanceof ServerLevel sl) {
                for (net.minecraft.world.entity.Entity e : sl.getEntities(this, getBoundingBox().inflate(50))) {
                    if (e instanceof PumpkinPuppetEntity puppet) {
                        puppet.prime();
                    }
                }
            }
            level().playSound(null, blockPosition(), SoundEvents.WITHER_BREAK_BLOCK, SoundSource.HOSTILE, 2f, 1f);
        });
    }

    /** DS_FallingScissor: gcd 60, a rain of guillotine scissors around the target; each drop hits for 15 (r 2.5). */
    private void skillFallingScissors(LivingEntity target) {
        gcd = 60;
        play("falling_scissor");
        level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2f, 1f);
        if (target == null) {
            return;
        }
        final Vec3 focus = target.position();
        for (int i = 0; i < 4; i++) {
            final Vec3 spot = focus.add((random.nextDouble() - 0.5) * 6, 0, (random.nextDouble() - 0.5) * 6);
            int drop = 20 + i * 6;
            schedule(drop, () -> {
                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.PORTAL, spot.x, spot.y + 0.2, spot.z, 8, 0.3, 0.4, 0.3, 0.05);
                }
                level().playSound(null, BlockPos.containing(spot), SoundEvents.ENDER_EYE_DEATH, SoundSource.HOSTILE, 1.5f, 1f);
            });
            schedule(drop + 8, () -> {
                for (Player p : level().getEntitiesOfClass(Player.class, new AABB(spot, spot).inflate(reach(2.5)))) {
                    if (p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                        p.hurt(damageSources().mobAttack(this), dmg(15.0));
                    }
                }
                level().playSound(null, BlockPos.containing(spot), SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 2f, 0.6f);
            });
        }
    }

    /**
     * DS_Mirage: a phase-3 after-image that does one skill and fades. The pack summons a full mirage copy
     * (ds_mirage_knife / ds_mirage_puppet); adapted to a boss-driven after-image that either throws a knife line
     * or drops a puppet, then a fade burst, without a second AI body.
     */
    private void skillMirage(LivingEntity target) {
        afterImage();
        if (random.nextBoolean()) {
            level().playSound(null, blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 2f, 1.5f);
            lineProjectile(target, 4.0, ParticleTypes.PORTAL);
        } else if (level() instanceof ServerLevel sl && target != null && puppetScore < 3) {
            if (summonPuppetAt(sl, target.position().add((random.nextDouble() - 0.5) * 4, 0, (random.nextDouble() - 0.5) * 4)) != null) {
                puppetScore++;
            }
        }
        vfxFade();
    }

    // ------------------------------------------------------------------ shared helpers

    private float healthFraction() {
        return getHealth() / getMaxHealth();
    }

    /** A spatial reach/area, grown with {@link #SCALE} so hit zones agree with the bigger body and visuals. */
    private static double reach(double base) {
        return base * SCALE;
    }

    /** Point yaw, body yaw and head yaw straight at the target so the model's -Z front reads toward it. */
    private void faceTarget(LivingEntity target) {
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        if (dx * dx + dz * dz < 1.0e-6) {
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.setYHeadRot(yaw);
        this.yHeadRotO = yaw;
    }

    private LivingEntity nearestTarget() {
        LivingEntity t = getTarget();
        if (t != null && t.isAlive()) {
            return t;
        }
        return level().getNearestPlayer(this, 48.0);
    }

    private List<LivingEntity> livingInRadius(double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Player p : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(r))) {
            if (p.isAlive() && !p.isSpectator() && !p.isCreative() && distanceTo(p) <= r) {
                out.add(p);
            }
        }
        return out;
    }

    private List<LivingEntity> livingInCone(double r, double angleDeg) {
        List<LivingEntity> out = new ArrayList<>();
        Vec3 facing = getViewVector(1.0f).multiply(1, 0, 1).normalize();
        double cos = Math.cos(Math.toRadians(angleDeg / 2.0));
        for (LivingEntity e : livingInRadius(r)) {
            Vec3 to = e.position().subtract(position()).multiply(1, 0, 1);
            if (to.lengthSqr() < 1.0e-4) {
                out.add(e);
                continue;
            }
            if (facing.dot(to.normalize()) >= cos) {
                out.add(e);
            }
        }
        return out;
    }

    private void lineProjectile(LivingEntity target, double base, ParticleOptions particle) {
        if (target == null || !(level() instanceof ServerLevel sl)) {
            return;
        }
        Vec3 from = position().add(0, getBbHeight() * 0.6, 0);
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        if (len < 0.01) {
            return;
        }
        dir = dir.scale(1.0 / len);
        for (double d = 0; d < len; d += 0.5) {
            Vec3 p = from.add(dir.scale(d));
            sl.sendParticles(particle, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        if (base > 0 && target.isAlive() && target.position().distanceTo(to) < 3.0) {
            target.hurt(damageSources().mobAttack(this), dmg(base));
            level().playSound(null, target.blockPosition(), SoundEvents.ARROW_HIT_PLAYER, SoundSource.HOSTILE, 1f, 0.95f);
        }
    }

    private void blinkNear(LivingEntity target, double dist) {
        if (!(level() instanceof ServerLevel)) {
            return;
        }
        Vec3 back = target.position().subtract(dirTo(target).scale(dist));
        this.teleportTo(back.x, target.getY(), back.z);
        this.getNavigation().stop();
        faceTarget(target);
        this.lookAt(EntityAnchorArgument.Anchor.EYES, target.position());
    }

    private void leapToward(LivingEntity target, double strength) {
        Vec3 d = dirTo(target).scale(strength * SCALE);
        this.setDeltaMovement(d.x, 0.25, d.z);
        this.hasImpulse = true;
        this.leapGrace = 6; // let the cast-root pause zeroing his motion so the lunge actually moves him
    }

    private Vec3 dirTo(LivingEntity target) {
        Vec3 d = target.position().subtract(position()).multiply(1, 0, 1);
        return d.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : d.normalize();
    }

    private PumpkinPuppetEntity summonPuppetAt(ServerLevel sl, Vec3 pos) {
        PumpkinPuppetEntity puppet = net.shurui.dev.sdu.registry.ModEntities.PUMPKIN_PUPPET.get().create(sl);
        if (puppet == null) {
            return null;
        }
        puppet.moveTo(pos.x, pos.y, pos.z, random.nextFloat() * 360f, 0f);
        puppet.setPowerScale(getPowerScale());
        puppet.getPersistentData().putBoolean("dmz_stats_configured", true);
        sl.addFreshEntity(puppet);
        return puppet;
    }

    private void despawnMinions() {
        if (!(level() instanceof ServerLevel sl)) {
            return;
        }
        for (net.minecraft.world.entity.Entity e : sl.getEntities(this, getBoundingBox().inflate(60))) {
            if (e instanceof PumpkinPuppetEntity puppet && puppet.isAlive()) {
                // Only the standing puppets summoned during phase 1, not the interlude wave which we track by UUID.
                if (!interludeAdds.contains(puppet.getUUID())) {
                    puppet.discard();
                }
            }
        }
    }

    private void setInvulnerableWindow(int ticks) {
        this.invulnWindow = ticks;
    }

    private void schedule(int delay, Runnable r) {
        tasks.add(new Task(delay, r));
    }

    private void runTasks() {
        if (invulnWindow > 0) {
            invulnWindow--;
        }
        if (tasks.isEmpty()) {
            return;
        }
        List<Runnable> due = new ArrayList<>();
        for (java.util.Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (--t.delay <= 0) {
                due.add(t.run);
                it.remove();
            }
        }
        for (Runnable r : due) {
            r.run();
        }
    }

    private void particles(ParticleOptions particle, double y, int count, double spread) {
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(particle, getX(), getY() + y, getZ(), count, spread, spread, spread, 0.02);
        }
    }

    private void afterImage() {
        particles(ParticleTypes.CRIT, 1.2, 20, 0.4);
        particles(ParticleTypes.SMOKE, 1.2, 12, 0.3);
        level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_HURT, SoundSource.HOSTILE, 1f, 0.7f);
    }

    private void vfxTeleport() {
        level().playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 1f);
        particles(ParticleTypes.PORTAL, 1.2, 15, 0.4);
        particles(ParticleTypes.REVERSE_PORTAL, 1.2, 15, 0.3);
    }

    private void vfxFade() {
        level().playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_HURT, SoundSource.HOSTILE, 1f, 0.7f);
        particles(ParticleTypes.CRIT, 1.2, 15, 0.4);
        particles(ParticleTypes.SMOKE, 1.2, 15, 0.3);
    }

    private void broadcast(String message) {
        Component c = Component.literal("<Duke Snipperjack> ").withStyle(net.minecraft.ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(net.minecraft.ChatFormatting.WHITE));
        for (Player p : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(50))) {
            p.sendSystemMessage(c);
        }
    }

    // ------------------------------------------------------------------ combat integration

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.GENERIC_KILL)) {
            return super.hurt(source, amount);
        }
        // Seated at his tea (pre-fight or interlude): completely immune. A PLAYER's attack still provokes the
        // pre-fight rise, but deals no damage; nothing can kill, knock about or drag him while seated.
        if (isSeated()) {
            if (!level().isClientSide() && getStance() == ST_PREFIGHT) {
                Player by = attacker(source);
                if (by != null) {
                    provoke(by);
                }
            }
            return false;
        }
        if (getStance() == ST_RISING || getStance() == ST_DEAD || invulnWindow > 0) {
            return false;
        }
        return super.hurt(source, amount);
    }

    private static Player attacker(DamageSource source) {
        if (source.getEntity() instanceof Player p) {
            return p;
        }
        if (source.getDirectEntity() instanceof Player p) {
            return p;
        }
        return null;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide() && getStance() == ST_PREFIGHT) {
            provoke(player);
            return InteractionResult.sidedSuccess(level().isClientSide());
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean isPushable() {
        // Not shoved off his obsidian seat while seated at tea; otherwise the normal PathfinderMob behaviour.
        return !isSeated() && super.isPushable();
    }

    @Override
    protected void pushEntities() {
        if (isSeated()) {
            return;
        }
        super.pushEntities();
    }

    @Override
    public void knockback(double strength, double x, double z) {
        if (isSeated()) {
            return;
        }
        super.knockback(strength, x, z);
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            setStance(ST_DEAD);
            play("death");
            despawnMinions();
            broadcast("Impossible... a gentleman... undone...");
            spawnAtLocation(net.shurui.dev.sdu.registry.ModItems.DUKES_BLADE.get());
        }
        super.die(source);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        this.bossBar.setProgress(Math.max(0f, getHealth() / getMaxHealth()));
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossBar.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossBar.removePlayer(player);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("ds_stance", getStance());
        tag.putFloat("ds_power", getPowerScale());
        tag.putDouble("ds_damage_unit", damageUnit);
        tag.putBoolean("ds_taunted", tauntedOnce);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("ds_stance")) {
            setStance(tag.getInt("ds_stance"));
        } else if (tag.contains("ds_phase")) {
            // Legacy save (phase-indexed): map the old phase ints onto the new stance ladder.
            setStance(switch (tag.getInt("ds_phase")) {
                case 1 -> ST_PHASE1;
                case 2 -> ST_PHASE2;
                case 3 -> ST_PHASE3;
                case 4 -> ST_INTERLUDE;
                case 5 -> ST_DEAD;
                default -> ST_PREFIGHT;
            });
        }
        if (tag.contains("ds_power")) {
            this.entityData.set(POWER, tag.getFloat("ds_power"));
        }
        if (tag.contains("ds_damage_unit")) {
            damageUnit = tag.getDouble("ds_damage_unit");
        }
        tauntedOnce = tag.getBoolean("ds_taunted") || tag.getBoolean("ds_intro");
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 2, this::predicate));
    }

    // Client-side tracking to turn a bumped ACTION_ID into a one-shot play that reverts to the base clip.
    private transient int clientActionId = -1;
    private transient boolean clientPlaying = false;

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        AnimationController<?> controller = state.getController();
        if (getStance() == ST_DEAD) {
            controller.setAnimation(RawAnimation.begin().then("death", Animation.LoopType.HOLD_ON_LAST_FRAME));
            return PlayState.CONTINUE;
        }
        int id = getActionId();
        if (id != clientActionId) {
            clientActionId = id;
            clientPlaying = true;
            controller.forceAnimationReset();
        }
        if (clientPlaying) {
            String action = getAction();
            if (action != null && !action.isEmpty()) {
                controller.setAnimation(RawAnimation.begin().then(action, Animation.LoopType.PLAY_ONCE));
                if (controller.hasAnimationFinished()) {
                    clientPlaying = false;
                } else {
                    return PlayState.CONTINUE;
                }
            } else {
                clientPlaying = false;
            }
        }
        // Base loop: seated/transition rigs only have idle; phase 3 runs; phases 1/2 walk when moving.
        int rig = getRigPhase();
        if (rig == RIG_PHASE0) {
            controller.setAnimation(RawAnimation.begin().thenLoop("idle"));
        } else if (state.isMoving()) {
            String base = getStance() == ST_PHASE3 ? "run" : "walk";
            controller.setAnimation(RawAnimation.begin().thenLoop(base));
        } else {
            controller.setAnimation(RawAnimation.begin().thenLoop("idle"));
        }
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
