package net.shurui.shuruisutilities.ragnarok;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.saiyan.RgNpcFallbackAppearance;

/**
 * A ragnarok character that actually FIGHTS: DragonMineZ's saga-fighter chassis wearing one of the rgnpc models.
 *
 * <h2>Why this exists next to {@link RgNpcEntity}</h2>
 * {@link RgNpcEntity} is a display NPC. It is a plain {@code Mob} with no goals, which is right for a statue in a
 * town square and useless everywhere else: picked as a spawner mob, a raid boss or a quest target it stands
 * there, unanimated and harmless, because there is no combat chassis underneath it to animate or to fight. Every
 * editor that can choose a character is choosing something that is supposed to be an OPPONENT, so those pickers
 * point here instead, and the display NPC stays available for decoration.
 *
 * <p>Extending {@link DBSagasEntity} inherits the whole saga fighter for free: the goal set (float, ki skill,
 * melee, stroll, look, nearest-player and hurt-by targeting), the ki-blast pool, the combat movement, and the
 * animation controllers. This is the same trick {@link net.shurui.shuruisutilities.space.PlanetGarrisonDefenderEntity}
 * uses; that one is a garrison guard confined to a planet disc, this one is the general-purpose version with no
 * such leash, so the two are siblings rather than one wrapping the other.
 *
 * <h2>How an arbitrary rgnpc model rides a saga entity</h2>
 * DragonMineZ resolves a saga entity's geo and texture from its entity TYPE, inside its own renderer. Because this
 * is a separate registered type with its OWN renderer
 * ({@code net.shurui.shuruisutilities.ragnarok.client.RgNpcFighterRenderer}), DragonMineZ's saga renderer never
 * sees it, and our GeoModel resolves geo and texture from the synced entry id in {@link #MODEL_ID} while serving
 * DragonMineZ's {@code saga_base.animation.json}. The rgnpc geos were retargeted onto that same saga rig, so the
 * inherited controllers drive their bones: DragonMineZ AI on the server, ragnarok art on the client.
 *
 * <p>DragonMineZ is mandatory for every addon in this suite, so extending its class directly with no compat guard
 * is correct: this file cannot load without it, and it is always there.
 */
// Implements RgNpcFallbackAppearance so a client without this fighter's rgnpc model draws a generated DragonMineZ saiyan
// (derived client-side from the UUID, no synced fields) rather than a plain Steve. All appearance getters come from the
// interface's UUID-derived defaults; nothing is added to this class.
public class RgNpcFighterEntity extends DBSagasEntity implements RgNpcFallbackAppearance
{
    /** The rgnpc ENTRY id (not a geo name) this fighter wears, synced so the client renderer can resolve it. */
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(RgNpcFighterEntity.class, EntityDataSerializers.STRING);

    /** Uniform render/hitbox scale, synced. Matches {@link RgNpcEntity}'s knob so both read the same way. */
    private static final EntityDataAccessor<Float> SCALE =
            SynchedEntityData.defineId(RgNpcFighterEntity.class, EntityDataSerializers.FLOAT);

    private static final String KEY_MODEL = "su_rgnpc_model";
    private static final String KEY_SCALE = "su_rgnpc_scale";

    private static final float DEFAULT_SCALE = 1.0F;

    /** Fallback box for a model with no measured size, matching a saga humanoid. */
    private static final float FALLBACK_WIDTH = 0.6F;
    private static final float FALLBACK_HEIGHT = 1.8F;

    /**
     * DragonMineZ's own tier ids: 1 SIMPLE, 2 TACTICAL, 3 ADVANCED. A fighter spawned with no tier set would sit
     * at 0, which is not a tier DragonMineZ knows, so {@link #selfSetup} puts it here. TACTICAL rather than
     * ADVANCED because this is what an editor gets WITHOUT asking for anything: a competent opponent, with the
     * heavier tier left as a deliberate choice ({@code setAiTierById} is public and callers may raise it).
     */
    private static final int DEFAULT_AI_TIER = 2;

    /** One-shot flag for the first server tick; see {@link #selfSetup}. */
    private boolean selfSetupDone;

    public RgNpcFighterEntity(EntityType<? extends DBSagasEntity> type, Level level)
    {
        super(type, level);
    }

    /** The saga chassis's own attribute base; every spawn path overrides the values afterwards. */
    public static AttributeSupplier.Builder createAttributes()
    {
        return DBSagasEntity.createAttributes();
    }

    @Override
    protected void defineSynchedData()
    {
        super.defineSynchedData();
        this.entityData.define(MODEL_ID, RgNpcModels.DEFAULT_ID);
        this.entityData.define(SCALE, DEFAULT_SCALE);
    }

    /** The rgnpc entry id this fighter wears; always an installed entry once {@link #setModelId} has run. */
    public String getModelId()
    {
        return this.entityData.get(MODEL_ID);
    }

    /**
     * Set the character. The id is stored SANITISED but VERBATIM (never pre-resolved), matching how ids are
     * persisted and read: {@link RgNpcModels} applies the legacy alias at every read site, and the renderer draws
     * a real model for a live-or-aliased id and the generated saiyan for an id that names nothing live. Storing the
     * raw id is what lets an unmatched id (a character dropped when the model set was replaced) reach that saiyan
     * fallback instead of silently becoming the wrong character: collapsing it to the default entry here would draw
     * {@code 2stars} for it, which is exactly the "wrong model" the bundling was meant to avoid. It is still
     * crash-proof because sanitising guarantees a legal resource path and the renderer never hands GeckoLib a
     * missing real geo (an unmatched id borrows a DragonMineZ race geo). Only a blank/empty id falls back to the
     * default entry. Mirrors {@link RgNpcEntity#setModelId}.
     */
    public void setModelId(String id)
    {
        String clean = RgNpcModels.sanitize(id);
        this.entityData.set(MODEL_ID, clean.isEmpty() ? RgNpcModels.DEFAULT_ID : clean);
        // The box comes from the model, so it has to be rebuilt when the model changes (server side).
        refreshDimensions();
    }

    public float getScaleValue()
    {
        return this.entityData.get(SCALE);
    }

    public void setScaleValue(float scale)
    {
        this.entityData.set(SCALE, scale <= 0 ? DEFAULT_SCALE : scale);
        refreshDimensions();
    }

    /**
     * The box the MODEL measures, not a fixed humanoid one.
     *
     * <p>The cast runs from children to giants, so a single 0.6x1.8 box would leave a large character unhittable
     * around the edges and a small one swinging at air. Crash-proof by contract: this is called during
     * construction, before the synched data exists, so every step tolerates a null holder and falls back.
     */
    @Override
    public EntityDimensions getDimensions(Pose pose)
    {
        try
        {
            if (this.entityData == null)
                return EntityDimensions.scalable(FALLBACK_WIDTH, FALLBACK_HEIGHT);
            float scale = getScaleValue();
            if (scale <= 0)
                scale = DEFAULT_SCALE;
            float[] size = RgNpcModelSizes.sizeFor(RgNpcModels.geoId(getModelId()));
            if (size == null || size.length < 2 || size[0] <= 0 || size[1] <= 0)
                return EntityDimensions.scalable(FALLBACK_WIDTH * scale, FALLBACK_HEIGHT * scale);
            return EntityDimensions.scalable(size[0] * scale, size[1] * scale);
        }
        catch (Throwable t)
        {
            // Called from the entity constructor's own dimension refresh on some paths, i.e. potentially before
            // the synched keys exist. A hitbox is not worth a crash on either thread.
            return EntityDimensions.scalable(FALLBACK_WIDTH, FALLBACK_HEIGHT);
        }
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions)
    {
        return dimensions.height * 0.85F;
    }

    /**
     * Never run DragonMineZ's native saga transform, which spawns a NEW form entity and discards this one.
     *
     * <p>Two things would break at once: the new entity is an ordinary saga type, so the chosen character would
     * vanish mid-fight, and it carries a different UUID, which every caller that tracks what it spawned (the raid
     * instance's live-enemy set, the dungeon floor-boss registry, the airdrop guard stamp) identifies its own
     * spawn by. A transform chain is still available deliberately, through sdu's own engine, which is written to
     * carry identity across a swap.
     */
    @Override
    protected boolean hasTransformation()
    {
        return false;
    }

    /**
     * What this fighter is CALLED, which is what a kill message prints.
     *
     * <h2>Why this override exists</h2>
     * Without it a death read "Steve was slain by dmz_ragnarok.rgnpc_fighter": Minecraft asks the entity type for
     * its name, the type asks for the translation key {@code entity.dmz_ragnarok.rgnpc_fighter}, and with no lang
     * entry for that key the raw key is what gets printed. A lang entry alone would fix the ugliness but would
     * make every one of them the same generic word, when the whole point of this entity is that it IS a specific
     * character.
     *
     * <p>So, in order: an admin's custom name wins, because somebody typed it on purpose. Otherwise the rgnpc
     * model id is turned into a readable name, so a fighter wearing the "angol" model dies as Angol. Only a
     * fighter with no model at all falls through to the translated type name, which now has a real lang entry
     * behind it.
     */
    @Override
    protected Component getTypeName()
    {
        String model = getModelId();
        if (model != null && !model.isBlank() && !RgNpcModels.DEFAULT_ID.equals(model))
        {
            String pretty = prettyModelName(model);
            if (!pretty.isEmpty())
            {
                return Component.literal(pretty);
            }
        }
        return super.getTypeName();
    }

    /**
     * Turn an rgnpc entry id into something worth printing: {@code "angol"} to {@code "Angol"},
     * {@code "red_ribbon_soldier"} to {@code "Red Ribbon Soldier"}.
     *
     * <p>Underscores are the only word boundary that actually exists in these ids. A run-together id like
     * {@code "cyclopianguard"} is left as one capitalised word rather than guessed at, because splitting it would
     * mean a dictionary and a wrong split reads worse than no split.
     */
    private static String prettyModelName(String id)
    {
        StringBuilder out = new StringBuilder(id.length());
        boolean startOfWord = true;
        for (int i = 0; i < id.length(); i++)
        {
            char c = id.charAt(i);
            if (c == '_' || c == '-')
            {
                out.append(' ');
                startOfWord = true;
                continue;
            }
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = false;
        }
        return out.toString().trim();
    }

    @Override
    public EntityType<? extends DBSagasEntity> getNextTransform()
    {
        return null;
    }

    /**
     * Graceful degradation only. This fighter's own renderer resolves the geo and texture from {@link #MODEL_ID},
     * so DragonMineZ's saga model name is never consulted; a real saga id is returned so that anything else which
     * ever asks still resolves to a file that exists.
     */
    @Override
    public String getGeckolibModelName()
    {
        return "saga_zarbon";
    }

    @Override
    public void tick()
    {
        super.tick();
        if (!this.level().isClientSide && !this.selfSetupDone)
        {
            this.selfSetupDone = true;
            selfSetup();
        }
        // Lift out of solid blocks if a ki crater regen, knockback or a dash has buried this fighter. Throttled and
        // only acts when actually walled in, so it is a no-op on a fighter standing free. Shares sdu's NpcUnstuck
        // with the terrain-regen sweep, which only checks once when a crater fills and so misses a later burial.
        net.shurui.dev.sdu.entity.NpcUnstuck.tickSelfHeal(this);
    }

    /**
     * Fill in the two things a saga fighter needs that a caller has no reason to know about.
     *
     * <p>Done on the first server tick rather than at construction because every spawn path applies its stats at a
     * different moment: the spawner and the NPC region configure the entity before it joins the world, the raid
     * instance stats it after building it, and a floor guardian is stamped from a config in between. By the first
     * tick all of them have finished, whichever order they used.
     *
     * <ul>
     *   <li>AI TIER: a fighter left at tier 0 is at no tier DragonMineZ recognises. Only set when unset, so a
     *       caller that chose one keeps it.</li>
     *   <li>BATTLE POWER: what the scouter reads. Derived the same way the raid module derives it,
     *       round(melee + ki blast damage), so a ragnarok fighter and a raid boss report power on one scale.
     *       Only set when it is still zero, for the same reason.</li>
     * </ul>
     */
    private void selfSetup()
    {
        try
        {
            if (getAiTierId() <= 0)
                setAiTierById(DEFAULT_AI_TIER);
            if (getBattlePower() <= 0)
            {
                double melee = getAttributeValue(Attributes.ATTACK_DAMAGE);
                double ki = getKiBlastDamage();
                setBattlePower((int) Math.round(melee + ki));
            }
        }
        catch (Throwable ignored)
        {
            // Cosmetic/AI polish. A fighter that spawned is better than a crashed tick loop.
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        tag.putString(KEY_MODEL, getModelId());
        tag.putFloat(KEY_SCALE, getScaleValue());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_MODEL))
            setModelId(tag.getString(KEY_MODEL));
        if (tag.contains(KEY_SCALE))
            setScaleValue(tag.getFloat(KEY_SCALE));
    }
}
