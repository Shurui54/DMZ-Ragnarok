package net.shurui.shuruisutilities.space;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;
import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;
import net.shurui.shuruisutilities.saiyan.SaiyanArmorSets;

/**
 * A wild-planet GARRISON saiyan rendered as a DragonMineZ-style CUSTOM CHARACTER (a DMZ race skin), NOT a DMZ saga mob.
 * It keeps the {@link DBSagasEntity} chassis so it inherits the full saga-fighter AI, the flagless confinement goal and
 * the suppress-transform stability of {@link PlanetGarrisonDefenderEntity}, but its client renderer reimplements DMZ's
 * player body/face/hair/tail/armor layer stack driven by the synced appearance fields below (DMZ's own render path is
 * hard-typed to {@code AbstractClientPlayer} and reads {@code StatsCapability}, which is player-only, so it cannot be
 * reused for a mob).
 *
 * <h2>Appearance is server-authoritative, shared and persisted</h2>
 * The whole look is a {@link SaiyanAppearance}: {@link #randomize} rolls it once at spawn on the server through the
 * shared {@link SaiyanAppearance#roll} (so the passive town saiyans roll the SAME distribution without duplicating the
 * logic), every field is synced via {@link SynchedEntityData} so the client can render it, and mirrored to NBT so a
 * chunk reload keeps the same face. Colours are packed 0xRRGGBB ints (converted to DMZ's float RGB on the client).
 *
 * <h2>The three named NPCs</h2>
 * A small share of the pool rolls into one of {@link SaiyanAppearance.NamedSaiyan} (GuiltyRex, RainbowDemon776,
 * Fenris_RE). These are stronger (see {@link #getStrengthMultiplier()}) and wear the REAL Minecraft skin of the matching
 * account, resolved asynchronously client-side; if the skin cannot be resolved they fall back to the custom-character
 * look with their specified hair/tail colour.
 */
public class PlanetSaiyanGarrisonEntity extends DBSagasEntity implements PlanetGarrisonHome, SaiyanAppearance
{
    // named NPCs hit this multiple of the rolled battle power so they are recognisably stronger than the generic
    // garrison. Applied in PlanetGarrison.spawnOne before the shared stat derivation, so it flows into health, melee and
    // NPC defense uniformly through the existing divisors.
    private static final double NAMED_STRENGTH_MULTIPLIER = 3.0;

    private static final EntityDataAccessor<Boolean> MALE =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BODY_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_ID =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYES_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> NOSE_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MOUTH_TYPE =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SKIN_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TAIL_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE1_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE2_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> NAMED =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);
    // synced scouter colour worn on the head (0 = none). Mirrors the citizen's field EXACTLY, but is rolled ONLY on the
    // finalizeSpawn (spawner / egg / region) path, never in randomize(): a generated-planet garrison deliberately wears
    // none (see SaiyanAppearance.getScouterColor's javadoc), so its look must not change.
    private static final EntityDataAccessor<Integer> SCOUTER_COLOR =
            SynchedEntityData.defineId(PlanetSaiyanGarrisonEntity.class, EntityDataSerializers.INT);

    // NBT keys (short, stable) so a saiyan keeps its exact face across a reload.
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
    private static final String KEY_SCOUTER = "su_saiyan_scouter";

    // the id of the planet this saiyan guards, or empty before it is assigned at spawn. Server-authoritative.
    private String homePlanetId = "";

    // transient guard so appearance is rolled exactly once. randomize() (the PlanetGarrison call) and finalizeSpawn (the
    // spawner / egg / region path) both set it; finalizeSpawn refuses to roll if it is already set, so the two entry
    // points can never double-roll or clobber each other. Not persisted: finalizeSpawn does not run on an NBT reload, so
    // a loaded saiyan keeps the face it already had regardless of this flag.
    private boolean appearanceRolled = false;

    public PlanetSaiyanGarrisonEntity(EntityType<? extends DBSagasEntity> type, Level level)
    {
        super(type, level);
        // grounded on purpose: a wild garrison defends a planet's SURFACE, so it must not fly off the disc into the
        // void, exactly like PlanetGarrisonDefenderEntity.
        this.setCanFly(false);
    }

    /** Standard DBSagasEntity attribute base, reused verbatim; {@link PlanetGarrison} overrides the values per spawn. */
    public static AttributeSupplier.Builder createAttributes()
    {
        return DBSagasEntity.createAttributes();
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
        this.entityData.define(SCOUTER_COLOR, SaiyanAppearance.SCOUTER_NONE);
    }

    /**
     * Roll this saiyan's whole appearance once, on the server, at spawn, through the shared {@link
     * SaiyanAppearance#roll} (named NPCs allowed). All fields are synced and persisted immediately.
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
        // only the three named NPCs carry a floating name; a generic garrison saiyan gets none.
        SaiyanAppearance.applyNameTag(this, this);
        // NO scouter is rolled here on purpose: the generated-planet garrison wears none (see
        // SaiyanAppearance.getScouterColor's javadoc). The scouter is added only on the finalizeSpawn path below.
        this.appearanceRolled = true;
    }

    /**
     * Give a saiyan placed by ANY vanilla spawn path (advanced spawner, spawn egg, NPC region, raid, dungeon) the same
     * randomized look and armor the generated-planet garrison gets, so no spawner-placed saiyan is left pale and bare.
     * The generated-planet path in {@link PlanetGarrison} does its own {@code create + randomize + addFreshEntity} and
     * never routes through here, so those saiyans are untouched; the {@link #appearanceRolled} guard makes a double entry
     * a no-op regardless. This does NOT run on an NBT reload, which is exactly right: a reloaded saiyan keeps its face.
     */
    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData spawnData, @Nullable CompoundTag dataTag)
    {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        if (!this.appearanceRolled)
        {
            RandomSource random = level.getRandom();
            randomize(random);
            SaiyanArmorSets.equip(this, random);
            // ONLY here, never in randomize(): a saiyan placed by a spawner / egg / region wears a rolled scouter, UNLIKE
            // the generated-planet garrison which deliberately wears none. Rolled after randomize so the whole look is set.
            this.entityData.set(SCOUTER_COLOR, SaiyanAppearance.rollScouterColor(random));
        }
        return data;
    }

    /**
     * The battle-power multiplier this saiyan applies to the garrison's rolled band. Named NPCs are a distinct, higher
     * tier; generic saiyans are 1.0. Read by {@link PlanetGarrison#spawnOne} before the shared stat derivation.
     */
    public double getStrengthMultiplier()
    {
        return isNamed() ? NAMED_STRENGTH_MULTIPLIER : 1.0;
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

    /** The named saiyan this entity is, or null if it is a generic garrison saiyan. */
    @Override
    public NamedSaiyan getNamed()
    {
        return NamedSaiyan.byId(this.entityData.get(NAMED));
    }

    // SCOUTER_NONE on a generated-planet garrison (randomize never rolls one); a real colour on a spawner/egg/region
    // saiyan (finalizeSpawn rolls one). Either way this reads the synced field so the client scouter layer draws it.
    @Override
    public int getScouterColor()
    {
        return this.entityData.get(SCOUTER_COLOR);
    }

    @Override
    public String getHomePlanetId()
    {
        return this.homePlanetId;
    }

    /** Assign the planet this saiyan guards, so its confinement goal keeps it on that planet's surface disc. */
    public void setHomePlanet(String planetId)
    {
        this.homePlanetId = planetId == null ? "" : planetId;
    }

    @Override
    protected boolean hasTransformation()
    {
        return false;
    }

    @Override
    public EntityType<? extends DBSagasEntity> getNextTransform()
    {
        return null;
    }

    @Override
    public String getGeckolibModelName()
    {
        // never consulted (our own renderer resolves the DMZ race geo), but a real saga id keeps any other path safe.
        return "saga_raditz";
    }

    @Override
    protected void registerGoals()
    {
        super.registerGoals();
        this.goalSelector.addGoal(0, new PlanetGarrisonDefenderConfineGoal(this));
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
        if (tag.contains(KEY_SCOUTER))
        {
            this.entityData.set(SCOUTER_COLOR, tag.getInt(KEY_SCOUTER));
        }
        // a saiyan restored from NBT already has its face; finalizeSpawn will not run for it, so mark it rolled so no
        // later entry point re-rolls it.
        this.appearanceRolled = true;
        this.homePlanetId = tag.getString(KEY_HOME);
    }
}
