package net.shurui.shuruisutilities.ragnarok;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.shurui.shuruisutilities.saiyan.RgNpcFallbackAppearance;
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
 * A single data-driven GeckoLib display NPC that can render any of the installed "rgnpc" models. WHICH model,
 * texture and render scale it uses are synced per entity via {@link SynchedEntityData}, so all of them are
 * spawnable from one registry entry (see {@link RgNpcEntities}). Modeled on sdu's ShenronDisplayEntity but
 * fully self-contained: SU has no compile or runtime dependency on sdu.
 *
 * <p>Behaviour is deliberately minimal and safe for a display piece: no AI goals, not pushable, not pickable,
 * does not despawn on its own, and interaction is a no-op (never opens a GUI). It is not a combat entity and is
 * not wired into any existing SU NPC system; that is a separate decision. It is spawnable and removable by
 * command (see {@code /rgentity}) and by the vanilla {@code /kill} selector.
 */
// Implements RgNpcFallbackAppearance so that, when this NPC's rgnpc model is not installed on a client, the renderer can
// draw a generated DragonMineZ saiyan (derived client-side from the UUID, no synced fields) instead of a plain Steve. The
// interface supplies every appearance getter as a default reading the one UUID-derived roll; nothing is added here.
public class RgNpcEntity extends Mob implements GeoEntity, RgNpcFallbackAppearance {

    // Synced: the entry id (NOT a geo name), the texture base name, and a uniform render scale. The entry id
    // maps to a (geo, texture) pair in RgNpcModels; the client GeoModel resolves both defensively.
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(RgNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TEXTURE_ID =
            SynchedEntityData.defineId(RgNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> SCALE =
            SynchedEntityData.defineId(RgNpcEntity.class, EntityDataSerializers.FLOAT);
    // Synced animation-set selector, so the animation library can be retargeted per entity later without a
    // code change. Currently only DEFAULT_ANIM_SET is used; a wrong value falls back to it on the client.
    private static final EntityDataAccessor<String> ANIM_SET =
            SynchedEntityData.defineId(RgNpcEntity.class, EntityDataSerializers.STRING);

    // Current NBT keys. The model id is the only piece that is also a stable model reference, so its legacy key
    // is honoured on read (see readAdditionalSaveData) for NPCs saved before the July 2026 model-set rename.
    private static final String KEY_MODEL = "su_rgnpc_model";
    private static final String KEY_TEXTURE = "su_rgnpc_texture";
    private static final String KEY_SCALE = "su_rgnpc_scale";
    private static final String KEY_ANIM = "su_rgnpc_anim";
    // Marker written ONLY on NPCs spawned by the /rgentity gridall review-grid command. It exists so the
    // companion /rgentity gridclear can delete exactly those NPCs and nothing else: a plain rgnpc an admin
    // placed by hand with /rgentity spawn never carries this flag, so the cleanup can never touch it. It is a
    // pure server-side bookkeeping flag (not synced to clients: the client renders the model identically either
    // way), persisted next to the model string via the same addAdditionalSaveData path so the flag survives a
    // save/reload and the grid stays removable in a later session.
    private static final String KEY_GRID_REVIEW = "su_rgnpc_gridreview";
    // PRE-RENAME keys, read-only fallbacks so NPCs saved before the July 2026 rename keep their config. We never
    // write these; the next save promotes the entity to the current keys.
    private static final String LEGACY_KEY_MODEL = "su_ninjin_model";
    private static final String LEGACY_KEY_TEXTURE = "su_ninjin_texture";
    private static final String LEGACY_KEY_SCALE = "su_ninjin_scale";
    private static final String LEGACY_KEY_ANIM = "su_ninjin_anim";

    // VISUAL TUNING VALUE: default uniform render scale. The models were authored at varied sizes; 1.0 is the
    // neutral starting point (render the geo as-authored) so nothing is silently squashed or blown up. Adjust
    // per model after a launch test. Overridable per entity via the synced SCALE field / command arg.
    public static final float DEFAULT_SCALE = 1.0f;

    // VISUAL TUNING VALUES: collision hitbox, roughly a humanoid. Most models are bipeds near player size, so
    // 0.7 wide x 1.95 tall (a hair under a vanilla player's 1.8 to avoid clipping ceilings) is a safe start. It
    // only affects collision/selection, not rendering; retune after a launch test, especially for the giant
    // models (oozaru / hirudegarn) whose visuals far exceed this box.
    public static final float HITBOX_WIDTH = 0.7f;
    public static final float HITBOX_HEIGHT = 1.95f;

    /**
     * The one currently supported animation set: DMZ's saga_base library, whose bones
     * (root/waist/head/body/left_arm/right_arm/left_leg/right_leg) match the rig the models were retargeted onto.
     * Clip names ("idle", "walk") are verified to exist in this file. Kept as a named constant so per model
     * animation sets can be added later by syncing a different value into ANIM_SET.
     */
    public static final String DEFAULT_ANIM_SET = "saga_base";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Server-side only: true when this NPC was spawned by the /rgentity gridall review grid (see KEY_GRID_REVIEW).
    // Not a SynchedEntityData field on purpose: nothing on the client depends on it, so syncing it would be pure
    // bandwidth. Defaults false so a normally spawned rgnpc is never mistaken for a review-grid entity.
    private boolean gridReviewMarker = false;

    public RgNpcEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    /**
     * Collision box sized to the actual model rather than the one shared registered box. Looks up the current
     * geo's baked {width, height} in {@link RgNpcModelSizes} and multiplies BOTH by the synced render SCALE, so
     * hits land where the model visually appears (a deliberate coupling: SCALE here is a user-facing render
     * multiplier, unlike DMZ where visual scale and hitbox are decoupled).
     *
     * <p>DEFENSIVE: this can be called before synced data is populated (during construction, before the entity is
     * added). It must never throw and never NPE, so it falls back to the registered base 0.7 x 1.95 whenever
     * {@code entityData} is null, the model id is blank, or the geo has no baked row. The registered
     * {@code .sized(...)} value only seeds pre-sync placement once this override exists.
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        EntityDimensions base = EntityDimensions.scalable(HITBOX_WIDTH, HITBOX_HEIGHT);
        // entityData is assigned by the superclass constructor before ours runs, but guard anyway: a subclass
        // constructor path or a very early call could see it null, and getDimensions must be crash-proof there.
        if (this.entityData == null) {
            return base;
        }
        String modelId = getModelId();
        if (modelId == null || modelId.isBlank()) {
            return base;
        }
        float[] size = RgNpcModelSizes.sizeFor(RgNpcModels.geoId(modelId));
        if (size == null || size.length < 2) {
            return base;
        }
        float scale = getScaleValue();
        if (scale <= 0) {
            scale = DEFAULT_SCALE;
        }
        return EntityDimensions.scalable(size[0] * scale, size[1] * scale);
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        // Derive from the passed-in box rather than hardcoding so it tracks per-model height and SCALE. Low
        // priority for an AI-less display piece, but keeps the eye / look ray near the visual head.
        return dimensions.height * 0.85f;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(MODEL_ID, RgNpcModels.DEFAULT_ID);
        this.entityData.define(TEXTURE_ID, RgNpcModels.defaultTexture(RgNpcModels.DEFAULT_ID));
        this.entityData.define(SCALE, DEFAULT_SCALE);
        this.entityData.define(ANIM_SET, DEFAULT_ANIM_SET);
    }

    @Override
    protected void registerGoals() {
        // No AI goals: this is a static display piece.
    }

    public String getModelId() {
        return this.entityData.get(MODEL_ID);
    }

    public void setModelId(String id) {
        // only ever store an installed, sanitised entry id so a bad value can never be synced to clients and
        // crash their render thread. an unknown id is first routed through the legacy remap (pre-rename saved
        // ids), and only if that also fails does it degrade to the default entry.
        //
        // DELIBERATELY UNGATED. This is the deserialisation entry point (readAdditionalSaveData calls it on every
        // chunk load), so it must never consult Shurui's Key. If it did, a saved gated NPC would be rewritten to
        // the default and its real id destroyed on the next save whenever key detection momentarily returned false
        // during a load (a key-mod update, a load-order quirk, or one of the classloader edge cases KeyGate was
        // hardened against for Mohist). KeyGate.present() re-probes every call and by design does not cache a
        // negative, so any single false read would be permanent, silent, world-wide data loss. The key gate lives
        // only on applyModelChoice, the apply-a-new-choice path. Do NOT add a gate check here.
        String clean = RgNpcModels.sanitize(id);
        String resolved = RgNpcModels.resolveId(clean);
        this.entityData.set(MODEL_ID, resolved != null ? resolved : RgNpcModels.DEFAULT_ID);
        // Rebuild the collision box for the new model. On the client the box also rebuilds via
        // onSyncedDataUpdated; this covers the server-authoritative set so the AABB is correct there too.
        refreshDimensions();
    }

    /**
     * Apply a model id chosen at runtime, i.e. the /suentity command path. This is the ONLY entry point that
     * consults Shurui's Key: a key-locked model degrades to the default entry when the key is absent. present()
     * holds in singleplayer without the key too, so the gate cannot be bypassed by picking a gated id there.
     *
     * <p>The command surface already refuses gated ids up front (they never appear in tab completion and are
     * rejected at execute), so this check is defence in depth on the apply side. It is kept OFF setModelId on
     * purpose: setModelId is the load path, and gating a deserialise would silently destroy saved gated ids the
     * first time key detection ever read false during a chunk load. Never route deserialisation through here.
     */
    public void applyModelChoice(String id) {
        String resolved = RgNpcModels.resolveId(RgNpcModels.sanitize(id));
        // CoreGateHooks, installed only by the Ragnarok Key (keyless default: locked models degrade to the default).
        if (resolved != null && RgNpcModels.isGated(resolved)
                && !net.shurui.dev.sdu.api.key.CoreGateHooks.get().rgModelsUnlocked()) {
            resolved = RgNpcModels.DEFAULT_ID;
        }
        // Delegate the store + box rebuild to setModelId; re-sanitising an already-resolved id is idempotent.
        setModelId(resolved != null ? resolved : RgNpcModels.DEFAULT_ID);
    }

    public String getTextureId() {
        return this.entityData.get(TEXTURE_ID);
    }

    public void setTextureId(String id) {
        // store the sanitised texture id so a bad value can never be synced. a blank / unsanitisable value
        // degrades to the current model's default texture. the client still sanitises again defensively.
        String clean = RgNpcModels.sanitize(id);
        this.entityData.set(TEXTURE_ID, clean.isEmpty()
                ? RgNpcModels.defaultTexture(getModelId()) : clean);
    }

    public float getScaleValue() {
        return this.entityData.get(SCALE);
    }

    public void setScaleValue(float scale) {
        this.entityData.set(SCALE, scale <= 0 ? DEFAULT_SCALE : scale);
        // SCALE multiplies the collision box, so rebuild it when the render scale changes (server side).
        refreshDimensions();
    }

    public String getAnimSet() {
        return this.entityData.get(ANIM_SET);
    }

    public void setAnimSet(String set) {
        this.entityData.set(ANIM_SET, set == null || set.isBlank() ? DEFAULT_ANIM_SET : set);
    }

    /** True when this NPC was spawned by the /rgentity gridall review grid, so /rgentity gridclear may remove it. */
    public boolean isGridReviewMarker() {
        return this.gridReviewMarker;
    }

    /** Flag this NPC as a review-grid spawn so it becomes eligible for /rgentity gridclear cleanup. */
    public void setGridReviewMarker(boolean marker) {
        this.gridReviewMarker = marker;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        // When MODEL_ID or SCALE arrives from the server, rebuild the box so the CLIENT collision / selection box
        // tracks the visible model live (e.g. when a command changes the model on an existing NPC).
        if (MODEL_ID.equals(key) || SCALE.equals(key)) {
            refreshDimensions();
        }
    }

    // Non-interactable display: interaction never opens a GUI and does nothing.
    @Override
    public net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Display piece: removable by command (/kill uses out-of-world / generic-kill) but immune to gameplay damage so
        // it is not knocked around or destroyed by mobs, lava, etc.
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.GENERIC_KILL)) {
            return super.hurt(source, amount);
        }
        return false;
    }

    @Override
    public boolean isPickable() {
        return true; // selectable so it can be right-clicked / targeted; interaction is still a no-op.
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 5, this::movementPredicate));
        // Second, independent controller: loops DMZ's "tail" clip forever, regardless of movement. In GeckoLib
        // 4.x each controller drives only the bones its clip names, so this coexists with "movement": "tail"
        // touches only tail1-tail9 / tail1bio-tail6bio, while idle/walk never touch any tail bone. On models with
        // no tail bones GeckoLib finds no matching targets and silently no-ops; no crash, no spam.
        controllers.add(new AnimationController<>(this, "tail", 5, this::tailPredicate));
    }

    private <T extends GeoAnimatable> PlayState movementPredicate(AnimationState<T> state) {
        // Play "walk" when moving on the ground, "idle" otherwise. isMoving() is GeckoLib's own horizontal-speed
        // check, which works from synced position deltas on the client. If a model has no matching bones (the two
        // rig-less models), GeckoLib finds nothing to animate and simply renders it static; no crash.
        if (state.isMoving()) {
            state.getController().setAnimation(RawAnimation.begin().then("walk", Animation.LoopType.LOOP));
        } else {
            state.getController().setAnimation(RawAnimation.begin().then("idle", Animation.LoopType.LOOP));
        }
        return PlayState.CONTINUE;
    }

    private <T extends GeoAnimatable> PlayState tailPredicate(AnimationState<T> state) {
        // Always loop the "tail" clip. Clip name verified against saga_base.animation.json in the DMZ jar; a wrong
        // name would fail silently in GeckoLib. Models without tail bones simply have nothing to move here.
        state.getController().setAnimation(RawAnimation.begin().then("tail", Animation.LoopType.LOOP));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(KEY_MODEL, getModelId());
        tag.putString(KEY_TEXTURE, getTextureId());
        tag.putFloat(KEY_SCALE, getScaleValue());
        tag.putString(KEY_ANIM, getAnimSet());
        // Only persist the review-grid marker when it is actually set, so a hand-placed rgnpc's save data stays
        // free of the key and can never be swept by /rgentity gridclear.
        if (this.gridReviewMarker) {
            tag.putBoolean(KEY_GRID_REVIEW, true);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Read the current key first; fall back to the PRE-RENAME key when the current one is absent, so NPCs
        // spawned before the July 2026 rename keep their model/texture/scale. setModelId additionally routes a
        // legacy entry id through RgNpcModels#resolveId, so an old saved model id maps to its current entry.
        if (tag.contains(KEY_MODEL)) {
            setModelId(tag.getString(KEY_MODEL));
        } else if (tag.contains(LEGACY_KEY_MODEL)) {
            setModelId(tag.getString(LEGACY_KEY_MODEL));
        }
        if (tag.contains(KEY_TEXTURE)) {
            setTextureId(tag.getString(KEY_TEXTURE));
        } else if (tag.contains(LEGACY_KEY_TEXTURE)) {
            setTextureId(tag.getString(LEGACY_KEY_TEXTURE));
        }
        if (tag.contains(KEY_SCALE)) {
            setScaleValue(tag.getFloat(KEY_SCALE));
        } else if (tag.contains(LEGACY_KEY_SCALE)) {
            setScaleValue(tag.getFloat(LEGACY_KEY_SCALE));
        }
        if (tag.contains(KEY_ANIM)) {
            setAnimSet(tag.getString(KEY_ANIM));
        } else if (tag.contains(LEGACY_KEY_ANIM)) {
            setAnimSet(tag.getString(LEGACY_KEY_ANIM));
        }
        // Restore the review-grid marker so a grid spawned in an earlier session is still cleanable after reload.
        // Absent key reads as false (getBoolean default), which is exactly right for every hand-placed NPC.
        this.gridReviewMarker = tag.getBoolean(KEY_GRID_REVIEW);
        // A pre-change garrison defender may still carry the retired "su_rgnpc_defender" tag here. We deliberately do
        // not read it: RgNpcEntity is a pure display piece again, so an unknown/retired tag is simply ignored, which is
        // harmless. The server-start sweep (PlanetGarrison.sweepOrphans) discards any such legacy defender.
        // Restored model / scale may differ from the seeded defaults, so rebuild the box once fields are back.
        refreshDimensions();
    }
}
