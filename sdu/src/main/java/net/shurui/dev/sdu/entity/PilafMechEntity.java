package net.shurui.dev.sdu.entity;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
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
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.sdu.registry.ModSounds;
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
 * Pilaf Mech: the Halloween 2026 RAID boss. Given the same treatment as {@link DukeSnipperjackEntity} (a native
 * DragonMineZ-compatible GeckoLib boss: a phase ladder driven off its health fraction, a health-derived power scale
 * that makes a beefier configured boss hit proportionally harder, a boss bar, telegraphed attacks with damage windows
 * and particle/sound beats, and all damage dealt as a mob-attack source so it runs through DMZ's damage pipeline) but
 * delivered as a RAID boss instead of a rift boss.
 *
 * <h2>Differences from Duke, because it is a raid boss</h2>
 * <ul>
 *   <li>Its HEALTH is set by the raid system (Raids applies the participant-scaled MAX_HEALTH), exactly as Duke's is
 *       when Duke is used as a raid boss.</li>
 *   <li>Its per-hit ceiling and DROPS are the RAID's job, not the entity's: the raid def's reward table (editable in
 *       game) is the raid-appropriate analogue of Duke's signature item drop, so this entity ships no bespoke item.</li>
 *   <li>It is gated to the Halloween EVENT: the raid def that names this entity is {@code eventOnly}, so it only runs
 *       while the key's event engine features it (see the {@code pilaf_mech} raid in the Halloween bundle).</li>
 * </ul>
 *
 * <h2>Model</h2>
 * It uses the owner's dedicated {@code pilaf_mech} GeckoLib rig (geo, Pilaf-skinned texture and its OWN animation
 * library, all under {@code assets/dmz_ragnarok/.../entity/pilaf_mech.*}). Its clips are the rig's own animations, so
 * every name this entity plays (idle, walk, spawn, slam_front, slam_front_2, swing_1, fire_missile,
 * spin_attack_short, death) MUST exist in {@code pilaf_mech.animation.json}: a missing clip crashes GeckoLib on the
 * render thread. See {@code PilafMechModel} for where the geo/texture/animation ids live.
 *
 * <h2>Stances</h2>
 * <ol>
 *   <li>{@link #ST_ACTIVATING}: a short power-up on spawn (immune), the mech booting its systems.</li>
 *   <li>{@link #ST_PHASE1}: 100-50%. Stomp, rocket punch and a missile barrage.</li>
 *   <li>{@link #ST_ENRAGE}: the 50% overdrive re-arm (immune), then it speeds up.</li>
 *   <li>{@link #ST_PHASE2}: below 50%, faster. Stomp, a wide ki beam, ground pound and the barrage.</li>
 *   <li>{@link #ST_DEAD}: the shutdown sequence.</li>
 * </ol>
 */
public class PilafMechEntity extends PathfinderMob implements GeoEntity {

    public static final int ST_ACTIVATING = 0;
    public static final int ST_PHASE1 = 1;
    public static final int ST_ENRAGE = 2;
    public static final int ST_PHASE2 = 3;
    public static final int ST_DEAD = 4;

    /**
     * The one place the whole fight's size is defined. The renderer scales the GeckoLib model by this and the
     * entity scales its bounding box / eye height by it, so the mech is clickable and hittable where it is drawn,
     * and the attack reach is multiplied by it so the hit zones agree with the (bigger) visuals. The owner's Pilaf
     * Mech rig is ~12.4 blocks tall at 1.0, so it is scaled to 0.6 for a ~7.4-block raid boss; retune the whole
     * boss here. The registered base bounding box (ModEntities) is the model's native footprint, so this one number
     * moves the render and the hitbox together.
     */
    public static final float SCALE = 0.6f;

    private static final EntityDataAccessor<Integer> STANCE =
            SynchedEntityData.defineId(PilafMechEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> ACTION =
            SynchedEntityData.defineId(PilafMechEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> ACTION_ID =
            SynchedEntityData.defineId(PilafMechEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> POWER =
            SynchedEntityData.defineId(PilafMechEntity.class, EntityDataSerializers.FLOAT);

    /** MythicMobs-style small damage numbers converted to DMZ-meaningful damage. */
    private static final double DMZ_DAMAGE_UNIT = 6.0;
    /** Boss health that maps to powerScale 1.0. */
    private static final double REFERENCE_HEALTH = 2000.0;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossBar = new ServerBossEvent(
            Component.translatable("entity.dmz_ragnarok.pilaf_mech"),
            BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);

    /** While >0 the boss is committed to an attack and does not move or start another. */
    private int gcd = 0;
    private int meleeTimer = 20;
    private int specialTimer = 40;
    private int meleeCd = 0;
    private int specialCd = 0;
    private int transitionTimer = 0;
    private double damageUnit = DMZ_DAMAGE_UNIT;

    // ------------------------------------------------------------------ voice lines
    //
    // Pilaf pilots the mech, so it taunts in his voice (pompous, cowardly-arrogant, world-domination obsessed).
    // A triggered line shows to nearby players as a boss speech line (name-prefixed chat) and plays the matching
    // ModSounds voice event (silent until the owner adds .ogg clips). Attack and hurt lines roll a chance and
    // respect a shared cooldown so they never spam; phase-start, enrage, low-health, death and kill lines are
    // forced (they ignore the cooldown and always play). Both the chance and the cooldown are configurable per
    // spawn through the persistent-data keys pm_voice_chance (float 0..1) and pm_voice_cooldown (int ticks),
    // like pm_damage_unit.
    /** Ticks until a non-forced line may play again. */
    private int voiceCd = 0;
    /** Roll for an attack/hurt line (default 35%). Overridable via persistent data pm_voice_chance. */
    private float voiceChance = 0.35f;
    /** Minimum ticks between lines (default 6s). Overridable via persistent data pm_voice_cooldown. */
    private int voiceCooldownTicks = 120;
    /** The low-health panic line fires once per fight. */
    private boolean lowHealthSaid = false;

    /** Simple delayed-task scheduler so an attack can lay out its damage/sound/particle beats over time. */
    private static final class Task {
        int delay;
        final Runnable run;
        Task(int delay, Runnable run) { this.delay = delay; this.run = run; }
    }
    private final List<Task> tasks = new ArrayList<>();

    public PilafMechEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 0;
        this.bossBar.setDarkenScreen(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 2000.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(SCALE);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STANCE, ST_ACTIVATING);
        this.entityData.define(ACTION, "");
        this.entityData.define(ACTION_ID, 0);
        this.entityData.define(POWER, 1.0f);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, true));
        this.goalSelector.addGoal(4, new MoveTowardsTargetGoal(this, 1.0, 40.0F));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 24.0F));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ------------------------------------------------------------------ synced accessors

    public int getStance() {
        return this.entityData.get(STANCE);
    }

    private void setStance(int stance) {
        this.entityData.set(STANCE, stance);
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
        if (getPersistentData().contains("pm_damage_unit")) {
            damageUnit = getPersistentData().getDouble("pm_damage_unit");
        }
        if (getPersistentData().contains("pm_voice_chance")) {
            voiceChance = Mth.clamp(getPersistentData().getFloat("pm_voice_chance"), 0f, 1f);
        }
        if (getPersistentData().contains("pm_voice_cooldown")) {
            voiceCooldownTicks = Math.max(0, getPersistentData().getInt("pm_voice_cooldown"));
        }
    }

    private float dmg(double base) {
        return (float) (base * damageUnit * getPowerScale());
    }

    private static double reach(double base) {
        return base * SCALE;
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        bossBar.setProgress(Math.max(0f, getHealth() / getMaxHealth()));
        ensureScaled();
        runTasks();
        if (specialCd > 0) specialCd--;
        if (meleeCd > 0) meleeCd--;
        if (voiceCd > 0) voiceCd--;

        if (gcd > 0) {
            gcd--;
            this.setDeltaMovement(this.getDeltaMovement().multiply(0.0, 1.0, 0.0));
            this.getNavigation().stop();
            LivingEntity t = getTarget();
            if (t != null) {
                faceTarget(t);
            }
        }

        switch (getStance()) {
            case ST_ACTIVATING, ST_ENRAGE -> tickTransition();
            case ST_PHASE1 -> tickPhaseOne();
            case ST_PHASE2 -> tickPhaseTwo();
            default -> { }
        }
    }

    private void tickTransition() {
        if (transitionTimer > 0) {
            transitionTimer--;
            return;
        }
        if (getStance() == ST_ACTIVATING) {
            enterPhaseOne();
        } else if (getStance() == ST_ENRAGE) {
            enterPhaseTwo();
        }
    }

    private void enterPhaseOne() {
        setStance(ST_PHASE1);
        vfxBurst();
        say("spawn", LINES_SPAWN, ModSounds.PILAF_VOICE_SPAWN.get(), 1f, true);
        gcd = 20;
        specialTimer = 40;
        meleeTimer = 20;
    }

    // ------------------------------------------------------------------ phase 1 (100-50%)

    private void tickPhaseOne() {
        if (healthFraction() <= 0.50f) {
            beginEnrage();
            return;
        }
        LivingEntity target = nearestTarget();
        if (target == null || gcd > 0) {
            return;
        }
        if (--meleeTimer <= 0) {
            meleeTimer = 20;
            if (meleeCd <= 0 && distanceTo(target) <= reach(5.0)) {
                meleeCd = 55;
                skillStomp(target, 6.0);
                return;
            }
        }
        if (--specialTimer <= 0) {
            specialTimer = 15;
            if (specialCd <= 0 && random.nextFloat() < 0.70f) {
                specialCd = 120;
                if (random.nextBoolean()) {
                    skillRocketPunch(target);
                } else {
                    skillMissileBarrage(target);
                }
            }
        }
    }

    private void beginEnrage() {
        setStance(ST_ENRAGE);
        play("spawn");
        say("enrage", LINES_ENRAGE, ModSounds.PILAF_VOICE_ENRAGE.get(), 1f, true);
        level().playSound(null, blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 3f, 0.8f);
        particles(ParticleTypes.LARGE_SMOKE, 2.0, 20, 0.6);
        transitionTimer = 80;
        gcd = 80;
    }

    private void enterPhaseTwo() {
        setStance(ST_PHASE2);
        var spd = getAttribute(Attributes.MOVEMENT_SPEED);
        if (spd != null) {
            spd.setBaseValue(0.36);
        }
        vfxBurst();
        say("phase2", LINES_PHASE2, ModSounds.PILAF_VOICE_PHASE2.get(), 1f, true);
        level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3f, 0.9f);
        gcd = 20;
        specialTimer = 25;
        meleeTimer = 16;
    }

    // ------------------------------------------------------------------ phase 2 (< 50%)

    private void tickPhaseTwo() {
        if (!lowHealthSaid && healthFraction() <= 0.15f) {
            lowHealthSaid = true;
            say("low_health", LINES_LOW_HEALTH, ModSounds.PILAF_VOICE_LOW_HEALTH.get(), 1f, true);
        }
        LivingEntity target = nearestTarget();
        if (target == null || gcd > 0) {
            return;
        }
        if (--meleeTimer <= 0) {
            meleeTimer = 16;
            if (meleeCd <= 0 && distanceTo(target) <= reach(5.5)) {
                meleeCd = 55;
                skillStomp(target, 9.0);
                return;
            }
        }
        if (--specialTimer <= 0) {
            specialTimer = 15;
            if (specialCd <= 0 && random.nextFloat() < 0.75f) {
                specialCd = 120;
                switch (random.nextInt(3)) {
                    case 0 -> skillKiBeam(target);
                    case 1 -> skillGroundPound();
                    default -> skillMissileBarrage(target);
                }
            }
        }
    }

    // ------------------------------------------------------------------ attacks

    /** A heavy piston stomp: a short telegraph, then a cone hit in front. */
    private void skillStomp(LivingEntity target, double base) {
        gcd = 30;
        faceTarget(target);
        play("slam_front");
        sayAttack("attack_stomp", LINES_STOMP);
        level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.2f, 0.7f);
        schedule(10, () -> {
            for (LivingEntity e : livingInCone(reach(5.0), 100)) {
                dealDamage(e, dmg(base));
            }
            particles(ParticleTypes.SWEEP_ATTACK, 0.4, 3, 0.3);
            level().playSound(null, blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.0f, 0.6f);
        });
    }

    /** A launched fist that streaks to the target and detonates for a single-target hit. */
    private void skillRocketPunch(LivingEntity target) {
        gcd = 45;
        faceTarget(target);
        play("swing_1");
        sayAttack("attack_rocket_punch", LINES_ROCKET);
        level().playSound(null, blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.5f, 0.8f);
        schedule(16, () -> {
            lineProjectile(target, 10.0, ParticleTypes.CRIT);
            level().playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.0f, 1.2f);
        });
    }

    /** A rain of small missiles around the target: three drops, each a small radius hit. */
    private void skillMissileBarrage(LivingEntity target) {
        gcd = 55;
        play("fire_missile");
        sayAttack("attack_missile", LINES_MISSILE);
        level().playSound(null, blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 1.5f, 1f);
        if (target == null) {
            return;
        }
        final Vec3 focus = target.position();
        for (int i = 0; i < 3; i++) {
            final Vec3 spot = focus.add((random.nextDouble() - 0.5) * 7, 0, (random.nextDouble() - 0.5) * 7);
            int drop = 14 + i * 8;
            schedule(drop, () -> {
                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.FLAME, spot.x, spot.y + 0.3, spot.z, 12, 0.4, 0.3, 0.4, 0.05);
                }
            });
            schedule(drop + 6, () -> {
                for (Player p : level().getEntitiesOfClass(Player.class,
                        new net.minecraft.world.phys.AABB(spot, spot).inflate(reach(2.5)))) {
                    if (p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                        dealDamage(p, dmg(7.0));
                    }
                }
                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.EXPLOSION, spot.x, spot.y + 0.3, spot.z, 1, 0, 0, 0, 0);
                }
                level().playSound(null, net.minecraft.core.BlockPos.containing(spot),
                        SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2f, 1.3f);
            });
        }
    }

    /** A wide chest-cannon ki beam: a longer telegraph, then a cone burst for heavy damage. */
    private void skillKiBeam(LivingEntity target) {
        gcd = 60;
        faceTarget(target);
        play("slam_front_2");
        sayAttack("attack_ki_beam", LINES_KI_BEAM);
        level().playSound(null, blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2f, 0.8f);
        schedule(30, () -> {
            for (LivingEntity e : livingInCone(reach(10.0), 40)) {
                dealDamage(e, dmg(12.0));
            }
            beamParticles(target);
            level().playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.5f, 0.7f);
        });
    }

    /** A slamming ground pound: a radial burst around the boss. */
    private void skillGroundPound() {
        gcd = 45;
        play("spin_attack_short");
        sayAttack("attack_ground_pound", LINES_GROUND_POUND);
        level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.5f, 0.6f);
        schedule(20, () -> {
            for (LivingEntity e : livingInRadius(reach(6.0))) {
                dealDamage(e, dmg(10.0));
            }
            particles(ParticleTypes.EXPLOSION, 0.2, 8, 0.8);
            level().playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.5f, 0.8f);
        });
    }

    // ------------------------------------------------------------------ shared helpers

    private float healthFraction() {
        return getHealth() / getMaxHealth();
    }

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
            sl.sendParticles(particle, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
        }
        if (base > 0 && target.isAlive()) {
            dealDamage(target, dmg(base));
        }
    }

    private void beamParticles(LivingEntity target) {
        if (target == null || !(level() instanceof ServerLevel sl)) {
            return;
        }
        Vec3 from = position().add(0, getBbHeight() * 0.6, 0);
        Vec3 dir = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(from);
        double len = Math.min(dir.length(), reach(10.0));
        if (len < 0.01) {
            return;
        }
        dir = dir.normalize();
        for (double d = 0; d < len; d += 0.4) {
            Vec3 p = from.add(dir.scale(d));
            sl.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.0);
        }
    }

    private void schedule(int delay, Runnable r) {
        tasks.add(new Task(delay, r));
    }

    private void runTasks() {
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

    private void vfxBurst() {
        particles(ParticleTypes.CLOUD, 1.2, 15, 0.5);
        particles(ParticleTypes.CRIT, 1.2, 15, 0.4);
    }

    // ------------------------------------------------------------------ voice lines

    // How many numbered lang lines back each trigger, so the server can pick one at random. Lang keys are
    // pilaf_mech.voice.<group>.<0..count-1> (in the dmz_ragnarok lang files); every index MUST exist there.
    private static final int LINES_SPAWN = 3;
    private static final int LINES_STOMP = 3;
    private static final int LINES_ROCKET = 3;
    private static final int LINES_MISSILE = 3;
    private static final int LINES_KI_BEAM = 3;
    private static final int LINES_GROUND_POUND = 3;
    private static final int LINES_HURT = 3;
    private static final int LINES_ENRAGE = 2;
    private static final int LINES_PHASE2 = 2;
    private static final int LINES_LOW_HEALTH = 3;
    private static final int LINES_DEATH = 2;
    private static final int LINES_KILL = 3;

    /**
     * Show a randomly chosen line of a trigger group to nearby players and play its voice sound.
     *
     * @param forced when true the line always plays (phase/enrage/death beats); otherwise it is gated by the
     *               shared voice cooldown and the given roll chance so attack/hurt chatter never spams.
     */
    private void say(String group, int count, SoundEvent sound, float chance, boolean forced) {
        if (level().isClientSide()) {
            return;
        }
        if (!forced && (voiceCd > 0 || random.nextFloat() >= chance)) {
            return;
        }
        int idx = count <= 1 ? 0 : random.nextInt(count);
        Component line = Component.translatable("pilaf_mech.voice.name").withStyle(net.minecraft.ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(net.minecraft.ChatFormatting.GOLD))
                .append(Component.translatable("pilaf_mech.voice." + group + "." + idx)
                        .withStyle(net.minecraft.ChatFormatting.WHITE));
        for (Player p : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(50))) {
            p.sendSystemMessage(line);
        }
        if (sound != null) {
            level().playSound(null, blockPosition(), sound, SoundSource.HOSTILE, 2.5f, 1.0f);
        }
        voiceCd = voiceCooldownTicks;
    }

    /** A non-forced attack line: rolls {@link #voiceChance} and respects the cooldown. */
    private void sayAttack(String group, int count) {
        say(group, count, ModSounds.PILAF_VOICE_ATTACK.get(), voiceChance, false);
    }

    /**
     * Apply a mob-attack hit and, if it just killed a player, roll a gloating kill line. A single wrapper so the
     * kill check lives in one place instead of at every {@code hurt} call site.
     */
    private void dealDamage(LivingEntity e, float amount) {
        boolean wasAlive = e.isAlive();
        e.hurt(damageSources().mobAttack(this), amount);
        if (wasAlive && !e.isAlive() && e instanceof Player) {
            // A kill is worth commenting on more often than a routine swing, but still capped by the cooldown so a
            // barrage that fells several players at once yields at most one taunt.
            say("kill", LINES_KILL, ModSounds.PILAF_VOICE_KILL.get(), Math.min(1f, voiceChance * 2f), false);
        }
    }

    // ------------------------------------------------------------------ combat integration

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.GENERIC_KILL)) {
            return super.hurt(source, amount);
        }
        // Immune while booting up or during the overdrive re-arm, so a transition cannot be skipped.
        int s = getStance();
        if (s == ST_ACTIVATING || s == ST_ENRAGE || s == ST_DEAD) {
            return false;
        }
        boolean applied = super.hurt(source, amount);
        // Only a meaningful hit earns an indignant yelp, and the cooldown/chance in say() keep it from spamming.
        if (applied && !level().isClientSide() && amount >= 15f) {
            say("hurt", LINES_HURT, ModSounds.PILAF_VOICE_HURT.get(), voiceChance, false);
        }
        return applied;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            setStance(ST_DEAD);
            play("death");
            say("death", LINES_DEATH, ModSounds.PILAF_VOICE_DEATH.get(), 1f, true);
            level().playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 3f, 0.6f);
            particles(ParticleTypes.LARGE_SMOKE, 1.5, 40, 1.0);
        }
        super.die(source);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void customServerAiStep() {
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
        tag.putInt("pm_stance", getStance());
        tag.putFloat("pm_power", getPowerScale());
        tag.putDouble("pm_damage_unit", damageUnit);
        tag.putBoolean("pm_low_said", lowHealthSaid);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("pm_stance")) {
            setStance(tag.getInt("pm_stance"));
        }
        if (tag.contains("pm_power")) {
            this.entityData.set(POWER, tag.getFloat("pm_power"));
        }
        if (tag.contains("pm_damage_unit")) {
            damageUnit = tag.getDouble("pm_damage_unit");
        }
        lowHealthSaid = tag.getBoolean("pm_low_said");
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 2, this::predicate));
    }

    private transient int clientActionId = -1;
    private transient boolean clientPlaying = false;

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        AnimationController<?> controller = state.getController();
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
        if (state.isMoving()) {
            // The Pilaf Mech rig ships one walk loop (no separate run); phase 2 just moves it faster via the
            // MOVEMENT_SPEED bump, so both phases loop "walk". Every name here MUST exist in
            // dmz_ragnarok:animations/entity/pilaf_mech.animation.json (GeckoLib crashes client-side on a missing clip).
            controller.setAnimation(RawAnimation.begin().thenLoop("walk"));
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
