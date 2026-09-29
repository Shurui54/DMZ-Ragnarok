package net.shurui.shuruisutilities.space;

import com.dragonminez.common.init.EntityAttributes;
import com.dragonminez.common.init.entities.IBattlePower;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;
import net.shurui.shuruisutilities.saiyan.SaiyanArmorSets;

/**
 * A genuinely PASSIVE saiyan inhabitant of Planet Vegeta's stamped town. Unlike {@link PlanetSaiyanGarrisonEntity} (a
 * hostile {@code DBSagasEntity} whose chassis force-targets any player through {@code DBSagasEntity.hurt}), a citizen is
 * forked onto a plain {@link PathfinderMob} so nothing on the saga chassis can make it lethal, and it never targets
 * anything.
 *
 * <h2>It is a {@link SaiyanAppearance}</h2>
 * A citizen carries and syncs the SAME custom-character appearance fields as the garrison saiyan and rolls them the same
 * way, through the shared {@link SaiyanAppearance#roll}, so the town's inhabitants match the warriors that guard it and
 * the whole client render stack (model, body/face/tail, hair, armor) draws them unchanged.
 *
 * <h2>Passive by construction, and permanently</h2>
 * <ul>
 *   <li>No target goals are installed at all (only wander / panic / look), so nothing acquires a target.</li>
 *   <li>{@link #setTarget} is overridden to refuse, so even a mixin, another mod or a goal added later cannot flip a
 *       townsperson into an attacker (belt and braces).</li>
 *   <li>It is {@link #setPersistenceRequired persistent} so the town never despawns while unwatched.</li>
 * </ul>
 *
 * <h2>Marker flag (load bearing)</h2>
 * A citizen self-stamps {@link #CITIZEN_FLAG} in its persistentData and NEVER the garrison's {@code su_planet_defender}
 * key. That separation is deliberate: {@code PlanetGarrison.sweepOrphans} only discards entities carrying the DEFENDER
 * flag, and {@code PlanetGarrison.claimBlocked} only counts DEFENDER-flagged entities, so a citizen is never swept and
 * never blocks a planet claim.
 *
 * <h2>Seam for the populate task</h2>
 * The populate-on-arrival system (a separate task) spawns citizens and then calls {@link #randomize}, {@link
 * #setHomePlanet} and {@link #equipSaiyanArmor}. Until a home is assigned the confinement goal is inert, so a citizen
 * placed without one simply wanders freely rather than misbehaving.
 */
public class PlanetSaiyanCitizenEntity extends PathfinderMob
        implements GeoEntity, PlanetGarrisonHome, SaiyanAppearance, IBattlePower, net.shurui.dev.sdu.api.SpaceTownNpc
{
    /** persistentData marker written on every citizen. Deliberately NOT the garrison's {@code su_planet_defender}. */
    public static final String CITIZEN_FLAG = "su_planet_citizen";

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // saga_base looped clips (served by the shared model) so a standing citizen breathes and a wandering one strides,
    // instead of the frozen T-pose a no-controller GeoEntity would show.
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    // saga_base's dedicated tail sway. The idle/walk clips do NOT touch the tail bones, so a saiyan's tail only moves if a
    // SEPARATE controller loops this clip, exactly as DragonMineZ's own DBSagasEntity registers a standalone tail_controller
    // alongside its movement controller. Without this the tail hangs frozen behind a moving body.
    private static final RawAnimation TAIL = RawAnimation.begin().thenLoop("tail");

    private static final EntityDataAccessor<Boolean> MALE =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BODY_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_ID =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYES_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> NOSE_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MOUTH_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SKIN_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TAIL_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE1_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE2_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> NAMED =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    // synced so a DragonMineZ scouter (which reads getBattlePower on the CLIENT entity) shows this citizen's tier band.
    private static final EntityDataAccessor<Integer> BATTLE_POWER =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);
    // synced scouter colour worn on the head (0 = none). Rolled at spawn and drawn by PlanetSaiyanScouterLayer.
    private static final EntityDataAccessor<Integer> SCOUTER_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanCitizenEntity.class, EntityDataSerializers.INT);

    // NBT keys shared in spelling with the garrison saiyan (they are per-entity, so there is no collision) so a citizen
    // keeps its exact face across a reload.
    private static final String KEY_MALE = "su_saiyan_male";
    private static final String KEY_BODY = "su_saiyan_body";
    private static final String KEY_HAIR = "su_saiyan_hair";
    private static final String KEY_EYES = "su_saiyan_eyes";
    private static final String KEY_NOSE = "su_saiyan_nose";
    private static final String KEY_MOUTH = "su_saiyan_mouth";
    private static final String KEY_SKIN_COLOR = "su_saiyan_skin_color";
    private static final String KEY_TAIL_COLOR = "su_saiyan_tail_color";
    private static final String KEY_HAIR_COLOR = "su_saiyan_hair_color";
    private static final String KEY_EYE1 = "su_saiyan_eye1";
    private static final String KEY_EYE2 = "su_saiyan_eye2";
    private static final String KEY_NAMED = "su_saiyan_named";
    private static final String KEY_HOME = "su_saiyan_home";
    private static final String KEY_BATTLE_POWER = "su_saiyan_bp";
    private static final String KEY_SCOUTER = "su_saiyan_scouter";

    // the id of the planet this citizen belongs to, or empty before it is assigned. Server-authoritative.
    private String homePlanetId = "";

    // transient guard so appearance is rolled exactly once. randomize() (the PlanetGarrison / populate-task call) and
    // finalizeSpawn (the spawner / egg / region path) both set it; finalizeSpawn refuses to roll if it is already set, so
    // the two entry points can never double-roll or clobber each other. Not persisted: finalizeSpawn does not run on an
    // NBT reload, so a loaded citizen keeps the face it already had regardless of this flag.
    private boolean appearanceRolled = false;

    public PlanetSaiyanCitizenEntity(EntityType<? extends PathfinderMob> type, Level level)
    {
        super(type, level);
        // the town must not despawn while nobody is looking.
        this.setPersistenceRequired();
        // self-stamp the citizen marker the instant the entity exists, so it is present before any world join, sweep or
        // claim scan. Idempotent across reloads. NEVER the garrison's su_planet_defender key.
        this.getPersistentData().putBoolean(CITIZEN_FLAG, true);
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 16.0D)
                // ATTACK_DAMAGE and DragonMineZ's KI_BLAST_DAMAGE are added so a Vegeta saiyan actually CARRIES melee and
                // ki damage numbers: PlanetGarrison.applyVegetaSaiyanStats writes both, and DMZ's MobBattlePowerHelper reads
                // them. Mob.createMobAttributes ships neither, so without these two those setAttribute calls would no-op.
                // The citizen still never attacks: it installs no target goal and setTarget refuses one, so carrying an
                // attack value changes nothing about its passive behaviour.
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(EntityAttributes.KI_BLAST_DAMAGE.get());
    }

    @Override
    protected void defineSynchedData()
    {
        super.defineSynchedData();
        this.entityData.define(MALE, true);
        this.entityData.define(BODY_TYPE, 1);
        this.entityData.define(HAIR_ID, 1);
        this.entityData.define(EYES_TYPE, 0);
        this.entityData.define(NOSE_TYPE, 0);
        this.entityData.define(MOUTH_TYPE, 0);
        this.entityData.define(SKIN_COLOR, 0xFFDBAC);
        this.entityData.define(TAIL_COLOR, SaiyanAppearance.DEFAULT_TAIL_COLOR);
        this.entityData.define(HAIR_COLOR, 0x000000);
        this.entityData.define(EYE1_COLOR, 0x3A2A1A);
        this.entityData.define(EYE2_COLOR, 0x111111);
        this.entityData.define(NAMED, 0);
        this.entityData.define(BATTLE_POWER, 0);
        this.entityData.define(SCOUTER_COLOR, SaiyanAppearance.SCOUTER_NONE);
    }

    @Override
    protected void registerGoals()
    {
        // wander / panic / look at player / random look, mirroring DMZ's NamekVillagerEntity. Deliberately NO target
        // goals in either selector, so a citizen never acquires anything to attack.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.25D));
        this.goalSelector.addGoal(2, new RandomStrollGoal(this, 0.9D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
        // flagless self-confinement, inert until the populate task assigns a home planet; keeps a citizen inside the
        // stamped town disc rather than wandering off into the void.
        this.goalSelector.addGoal(0, new PlanetGarrisonDefenderConfineGoal(this));
    }

    /**
     * Refuse EVERY target. A citizen is never hostile; forcing the target to null here means no mixin, no other mod and
     * no goal added later can turn a townsperson into an attacker. Belt and braces on top of installing no target goals.
     */
    @Override
    public void setTarget(@Nullable LivingEntity target)
    {
        super.setTarget(null);
    }

    /**
     * Roll this citizen's whole appearance once, on the server, at spawn, through the shared {@link
     * SaiyanAppearance#roll}. All fields are synced and persisted immediately.
     */
    public void randomize(RandomSource random)
    {
        SaiyanAppearance.Roll roll = SaiyanAppearance.roll(random, true);
        this.entityData.set(MALE, roll.male());
        this.entityData.set(BODY_TYPE, roll.bodyType());
        this.entityData.set(HAIR_ID, roll.hairId());
        this.entityData.set(EYES_TYPE, roll.eyesType());
        this.entityData.set(NOSE_TYPE, roll.noseType());
        this.entityData.set(MOUTH_TYPE, roll.mouthType());
        this.entityData.set(SKIN_COLOR, roll.skinColor());
        this.entityData.set(TAIL_COLOR, roll.tailColor());
        this.entityData.set(HAIR_COLOR, roll.hairColor());
        this.entityData.set(EYE1_COLOR, roll.eye1Color());
        this.entityData.set(EYE2_COLOR, roll.eye2Color());
        this.entityData.set(NAMED, roll.named());
        // only the three named NPCs carry a floating name; a generic citizen gets none.
        SaiyanAppearance.applyNameTag(this, this);
        // every town saiyan wears a scouter; roll its colour on the server so it syncs and persists with the rest of the
        // look. Rolled AFTER the shared appearance roll so it draws from this citizen's own stream and never perturbs the
        // garrison's separate roll on a generated planet.
        this.entityData.set(SCOUTER_COLOR, SaiyanAppearance.rollScouterColor(random));
        this.appearanceRolled = true;
    }

    /**
     * Give a citizen placed by ANY vanilla spawn path (advanced spawner, spawn egg, NPC region, raid, dungeon) the same
     * randomized look and armor the planet-populate task gives it, so no spawner-placed citizen is left pale and bare.
     * The populate task in {@link PlanetGarrison} does its own {@code create + randomize + equip + addFreshEntity} and
     * never routes through here, so those citizens are untouched; the {@link #appearanceRolled} guard makes a double
     * entry a no-op regardless. This does NOT run on an NBT reload, which is right: a reloaded citizen keeps its face.
     */
    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData spawnData, @Nullable CompoundTag dataTag)
    {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        if (!this.appearanceRolled)
        {
            // randomize already rolls the scouter for a citizen, so equipping armor completes the same look the town gets.
            randomize(level.getRandom());
            equipSaiyanArmor();
        }
        return data;
    }

    /**
     * Equip one randomly chosen whole saiyan armor set with a zero drop chance, exactly as the garrison saiyans are
     * geared (these are the planet's inhabitants). The set is picked from the shared {@link SaiyanArmorSets} pool so the
     * town shows the full spread of saiyan regalia, not only Vegeta's armor. A missing/renamed DMZ item is skipped.
     */
    public void equipSaiyanArmor()
    {
        SaiyanArmorSets.equip(this, this.getRandom());
    }

    @Override
    public boolean isMale()
    {
        return this.entityData.get(MALE);
    }

    @Override
    public int getBodyType()
    {
        return this.entityData.get(BODY_TYPE);
    }

    @Override
    public int getHairId()
    {
        return this.entityData.get(HAIR_ID);
    }

    @Override
    public int getEyesType()
    {
        return this.entityData.get(EYES_TYPE);
    }

    @Override
    public int getNoseType()
    {
        return this.entityData.get(NOSE_TYPE);
    }

    @Override
    public int getMouthType()
    {
        return this.entityData.get(MOUTH_TYPE);
    }

    @Override
    public int getSkinColor()
    {
        return this.entityData.get(SKIN_COLOR);
    }

    @Override
    public int getTailColor()
    {
        return this.entityData.get(TAIL_COLOR);
    }

    @Override
    public int getHairColor()
    {
        return this.entityData.get(HAIR_COLOR);
    }

    @Override
    public int getEye1Color()
    {
        return this.entityData.get(EYE1_COLOR);
    }

    @Override
    public int getEye2Color()
    {
        return this.entityData.get(EYE2_COLOR);
    }

    @Override
    public NamedSaiyan getNamed()
    {
        return NamedSaiyan.byId(this.entityData.get(NAMED));
    }

    @Override
    public int getScouterColor()
    {
        return this.entityData.get(SCOUTER_COLOR);
    }

    // A SYNCED battle power so a DragonMineZ scouter reads this citizen's tier. DMZ's ScouterHUD reads getBattlePower on
    // the CLIENT-side entity; the interface DMZ mixes into every LivingEntity backs it with a NON-synced field, so only a
    // synced override (exactly what DBSagasEntity does) actually reaches the client. PlanetGarrison.applyVegetaSaiyanStats
    // sets this to the tier health times the shared battle-power divisor.
    @Override
    public int getBattlePower()
    {
        return this.entityData.get(BATTLE_POWER);
    }

    @Override
    public void setBattlePower(int battlePower)
    {
        this.entityData.set(BATTLE_POWER, battlePower);
    }

    @Override
    public String getHomePlanetId()
    {
        return this.homePlanetId;
    }

    /** Assign the town's planet, so the confinement goal keeps this citizen inside the stamped town disc. */
    public void setHomePlanet(String planetId)
    {
        this.homePlanetId = planetId == null ? "" : planetId;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        // one movement controller so the citizen idles when still and walks when wandering; the head is additionally
        // driven by the model's setCustomAnimations. Without this the GeoEntity would stand in a frozen bind pose.
        controllers.add(new AnimationController<>(this, "move", 5, state ->
                state.setAndContinue(state.isMoving() ? WALK : IDLE)));
        // a second, independent controller that loops the tail sway on the tail1..tail5 bones, which the movement clips
        // leave untouched. Mirrors DBSagasEntity's tail_controller so the town saiyans' tails move like the garrison's.
        controllers.add(new AnimationController<>(this, "tail", 5, state -> state.setAndContinue(TAIL)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return this.geoCache;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(KEY_MALE, isMale());
        tag.putInt(KEY_BODY, getBodyType());
        tag.putInt(KEY_HAIR, getHairId());
        tag.putInt(KEY_EYES, getEyesType());
        tag.putInt(KEY_NOSE, getNoseType());
        tag.putInt(KEY_MOUTH, getMouthType());
        tag.putInt(KEY_SKIN_COLOR, getSkinColor());
        tag.putInt(KEY_TAIL_COLOR, getTailColor());
        tag.putInt(KEY_HAIR_COLOR, getHairColor());
        tag.putInt(KEY_EYE1, getEye1Color());
        tag.putInt(KEY_EYE2, getEye2Color());
        tag.putInt(KEY_NAMED, this.entityData.get(NAMED));
        tag.putInt(KEY_BATTLE_POWER, this.entityData.get(BATTLE_POWER));
        tag.putInt(KEY_SCOUTER, this.entityData.get(SCOUTER_COLOR));
        if (!this.homePlanetId.isEmpty())
        {
            tag.putString(KEY_HOME, this.homePlanetId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_MALE))
        {
            this.entityData.set(MALE, tag.getBoolean(KEY_MALE));
        }
        if (tag.contains(KEY_BODY))
        {
            this.entityData.set(BODY_TYPE, tag.getInt(KEY_BODY));
        }
        if (tag.contains(KEY_HAIR))
        {
            this.entityData.set(HAIR_ID, tag.getInt(KEY_HAIR));
        }
        if (tag.contains(KEY_EYES))
        {
            this.entityData.set(EYES_TYPE, tag.getInt(KEY_EYES));
        }
        if (tag.contains(KEY_NOSE))
        {
            this.entityData.set(NOSE_TYPE, tag.getInt(KEY_NOSE));
        }
        if (tag.contains(KEY_MOUTH))
        {
            this.entityData.set(MOUTH_TYPE, tag.getInt(KEY_MOUTH));
        }
        if (tag.contains(KEY_SKIN_COLOR))
        {
            this.entityData.set(SKIN_COLOR, tag.getInt(KEY_SKIN_COLOR));
        }
        if (tag.contains(KEY_TAIL_COLOR))
        {
            this.entityData.set(TAIL_COLOR, tag.getInt(KEY_TAIL_COLOR));
        }
        if (tag.contains(KEY_HAIR_COLOR))
        {
            this.entityData.set(HAIR_COLOR, tag.getInt(KEY_HAIR_COLOR));
        }
        if (tag.contains(KEY_EYE1))
        {
            this.entityData.set(EYE1_COLOR, tag.getInt(KEY_EYE1));
        }
        if (tag.contains(KEY_EYE2))
        {
            this.entityData.set(EYE2_COLOR, tag.getInt(KEY_EYE2));
        }
        if (tag.contains(KEY_NAMED))
        {
            this.entityData.set(NAMED, tag.getInt(KEY_NAMED));
        }
        if (tag.contains(KEY_BATTLE_POWER))
        {
            this.entityData.set(BATTLE_POWER, tag.getInt(KEY_BATTLE_POWER));
        }
        if (tag.contains(KEY_SCOUTER))
        {
            this.entityData.set(SCOUTER_COLOR, tag.getInt(KEY_SCOUTER));
        }
        this.homePlanetId = tag.getString(KEY_HOME);
        // a citizen restored from NBT already has its face; finalizeSpawn will not run for it, so mark it rolled so no
        // later entry point re-rolls it.
        this.appearanceRolled = true;
        // re-stamp the marker on load in case a legacy save predates it. Never the defender key.
        this.getPersistentData().putBoolean(CITIZEN_FLAG, true);
    }
}
