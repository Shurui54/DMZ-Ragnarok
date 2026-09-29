package net.shurui.shuruisutilities.space;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import net.shurui.shuruisutilities.saiyan.RgNpcFallbackAppearance;

/**
 * The VISIBLE, killable wild-planet GARRISON defender: a real combat NPC a guild must beat before it can claim an
 * unowned planet. It extends DragonMineZ's {@link DBSagasEntity} so it inherits the FULL saga-fighter chassis (AI
 * goals, melee, ki-skill pool, targeting, combat movement) for free, exactly as {@link
 * net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntity} does. This is what the user asked for: the same
 * AI as DragonMineZ saga fighters, at the lowest tier (see {@link PlanetGarrison} where the tier is set to {@code
 * AiTier.SIMPLE}, DragonMineZ's own weakest tier, the one its saibaman uses).
 *
 * <h2>How an arbitrary rgnpc GeckoLib model rides a DragonMineZ saga entity</h2>
 * DragonMineZ renders its saga entities with {@code DBSagaModel}, which resolves the geo/texture from the entity
 * TYPE's registry path via {@link DBSagasEntity#getGeckolibModelName()} inside DragonMineZ's OWN renderer
 * ({@code DBSagasRenderer}). Because this defender is registered as a SEPARATE SU entity type with its OWN renderer
 * ({@link net.shurui.shuruisutilities.client.space.PlanetGarrisonDefenderRenderer} over {@link
 * net.shurui.shuruisutilities.client.space.PlanetGarrisonDefenderModel}), DragonMineZ's saga renderer never touches
 * it. Our GeoModel instead resolves the geo and texture from the synced rgnpc entry id in {@link #MODEL_ID} through
 * {@link RgNpcModels}, exactly like {@link net.shurui.shuruisutilities.ragnarok.client.RgNpcModel} does, and serves
 * DragonMineZ's own {@code saga_base.animation.json}. That works because the rgnpc geos were retargeted onto the same
 * saga_base rig DragonMineZ animates, so the inherited saga animation controllers drive the rgnpc bones. Net result:
 * DragonMineZ AI on the server, approved rgnpc art on the client, with neither side fighting the other.
 *
 * <p>DragonMineZ is a mandatory dependency of every addon in this suite, so extending {@link DBSagasEntity} directly
 * (no compat guard) is correct: the class simply cannot load without DragonMineZ, which is always present.
 *
 * <h2>Not the clash holder (do not confuse)</h2>
 * This VISIBLE surface fighter is a different thing from {@link PlanetDefenderEntity} / {@link
 * PlanetDefenderEntities}, the INVISIBLE, invulnerable beam-clash HOLDER the space-side planet-buster spawns. Only the
 * word "defender" overlaps. See the mirror note on {@link PlanetDefenderEntity}.
 */
// Also implements RgNpcFallbackAppearance so a client without this defender's rgnpc model draws a generated DragonMineZ
// saiyan (derived client-side from the UUID, no synced fields) rather than a plain Steve. Every appearance getter comes
// from the interface's UUID-derived defaults; nothing is added to this class. RgNpcFallbackAppearance is in this package.
public class PlanetGarrisonDefenderEntity extends DBSagasEntity
        implements PlanetGarrisonHome, RgNpcFallbackAppearance, net.shurui.dev.sdu.api.SpaceDefenderNpc
{
    // The rgnpc ENTRY id (not a geo name) this defender wears, synced so the client renderer can resolve the geo and
    // texture. An entry id maps to a (geo, texture) pair in RgNpcModels, resolved defensively on both sides.
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(PlanetGarrisonDefenderEntity.class, EntityDataSerializers.STRING);

    // NBT key for the synced rgnpc entry id, so a defender keeps its face across a chunk reload.
    private static final String KEY_MODEL = "su_rgnpc_model";

    // NBT key for the id of the planet this defender guards. Read by the self-confinement goal to keep the fighter on
    // its planet's surface disc (see PlanetGarrisonDefenderConfineGoal). Persisted so confinement survives a reload.
    // This is the defender's OWN copy of its home; PlanetGarrison separately writes its bookkeeping marker
    // (su_planet_defender_planet) in ForgeData, so the two concerns stay decoupled.
    private static final String KEY_HOME = "su_garrison_home";

    // the id of the planet this defender guards, or empty before it is assigned at spawn. Server-authoritative.
    private String homePlanetId = "";

    public PlanetGarrisonDefenderEntity(EntityType<? extends DBSagasEntity> type, Level level)
    {
        super(type, level);
        // grounded on purpose: a wild garrison defends a planet's SURFACE, so it must not fly off the disc into the
        // void. DragonMineZ saga entities can fly by default (fly speed > 0); zeroing it keeps this one on the ground,
        // the same knob DragonMineZ's own saibaman uses. The horizontal disc clamp then handles any residual drift.
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
        this.entityData.define(MODEL_ID, RgNpcModels.DEFAULT_ID);
    }

    /** The rgnpc entry id this defender wears (always an installed entry after {@link #setModelId}). */
    public String getModelId()
    {
        return this.entityData.get(MODEL_ID);
    }

    /**
     * Set the rgnpc face. Only an installed, sanitised entry id is ever stored, so a stale or malformed id can never
     * be synced to a client and crash its render thread: an unknown id is routed through the legacy remap and, failing
     * that, degrades to the default entry. Mirrors {@link net.shurui.shuruisutilities.ragnarok.RgNpcEntity#setModelId}.
     */
    public void setModelId(String id)
    {
        String clean = RgNpcModels.sanitize(id);
        String resolved = RgNpcModels.resolveId(clean);
        this.entityData.set(MODEL_ID, resolved != null ? resolved : RgNpcModels.DEFAULT_ID);
    }

    /** The id of the planet this defender guards, or an empty string before it is assigned. */
    public String getHomePlanetId()
    {
        return this.homePlanetId;
    }

    /** Assign the planet this defender guards, so its confinement goal can keep it on that planet's surface disc. */
    public void setHomePlanet(String planetId)
    {
        this.homePlanetId = planetId == null ? "" : planetId;
    }

    /**
     * Never run DragonMineZ's native saga transform chain (which spawns a NEW form entity and discards this one). That
     * would swap the entity mid-fight, dropping its rgnpc face and, worse, breaking {@link PlanetGarrison}'s
     * defeat-tracking-by-UUID (the new entity would have a different UUID than the one recorded in the roster), so a
     * planet could be left with a live defender that the record no longer knows about. Forcing this false keeps the
     * entity identity stable for its whole life, exactly as the guild-raid clone does.
     */
    @Override
    protected boolean hasTransformation()
    {
        return false;
    }

    /** Never advertises a next form, for the same reason {@link #hasTransformation()} is false. */
    @Override
    public EntityType<? extends DBSagasEntity> getNextTransform()
    {
        return null;
    }

    /**
     * Graceful-degradation model name only. This defender's own renderer resolves the rgnpc geo/texture from {@link
     * #MODEL_ID}, so DragonMineZ's saga model name is never consulted for it; a real saga id is returned so the
     * resource always resolves if some other path ever asks.
     */
    @Override
    public String getGeckolibModelName()
    {
        return "saga_zarbon";
    }

    /**
     * Inherit DragonMineZ's full saga-fighter goal set (float, ki-skill, melee, stroll, look, and the nearest-player /
     * hurt-by target goals) unchanged, then add ONE flagless self-confinement goal that clamps the fighter to its
     * planet's surface disc. The confinement goal requests no AI flags, so it runs concurrently with every combat goal
     * and never interrupts the fight; it only pulls the fighter back if DragonMineZ's combat movement (which can
     * teleport toward a target with no bounds check) carries it off the disc. No guild-aware targeting is added here:
     * a wild defender simply fights whoever attacks or approaches, which is the plain saga behaviour the user wanted.
     */
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
        tag.putString(KEY_MODEL, getModelId());
        if (!this.homePlanetId.isEmpty())
        {
            tag.putString(KEY_HOME, this.homePlanetId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_MODEL))
        {
            setModelId(tag.getString(KEY_MODEL));
        }
        this.homePlanetId = tag.getString(KEY_HOME);
    }
}
