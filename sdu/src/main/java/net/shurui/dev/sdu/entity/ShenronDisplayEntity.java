package net.shurui.dev.sdu.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A fully custom, non-interactable Shenron display entity spawned by the SDU Shenron-shrine feature: pure
 * spectacle, immune to all damage except fall / out-of-world, no collision/push/pickable, Float +
 * LookAtPlayer + RandomLookAround goals, and a single looping "idle" GeckoLib animation. Deliberately decoupled
 * from DMZ's own {@code DragonWishEntity}/wish system: interaction is a NO-OP; wish selection happens through
 * the SDU {@code WishSelectScreen} pushed to the summoner only.
 *
 * <p>On summon it records the summoner UUID + shrine pos and, when {@code darkenSky} is set, the pre-summon day
 * time to restore on despawn (persisted in NBT for chunk-unload / restart safety). It despawns after
 * {@code summonDurationTicks} or ~100 ticks after a wish is granted, restoring day time + clear weather like DMZ.
 */
public class ShenronDisplayEntity extends Mob implements GeoEntity {

    private static final EntityDataAccessor<String> GEO =
            SynchedEntityData.defineId(ShenronDisplayEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TEXTURE =
            SynchedEntityData.defineId(ShenronDisplayEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> SCALE =
            SynchedEntityData.defineId(ShenronDisplayEntity.class, EntityDataSerializers.FLOAT);

    public static final String DEFAULT_GEO = "dmz_ragnarok:geo/entity/shenron.geo.json";
    public static final String DEFAULT_TEXTURE = "dmz_ragnarok:textures/entity/shenron.png";
    /** GeckoLib animation library shipped by the DMZ shenron (idle loop). */
    public static final String ANIM = "dragonminez:animations/entity/dragon/shenron.animation.json";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private java.util.UUID summoner;
    private long shrinePos = 0L;
    private boolean grantedWish = false;
    private int despawnDelay = -1;
    private int lifetime = net.shurui.dev.sdu.shenron.ShrineColorConfig.DEFAULT_DURATION;
    private int age = 0;
    private boolean darkenSky = false;
    private long savedDayTime = -1L;

    public ShenronDisplayEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(GEO, DEFAULT_GEO);
        this.entityData.define(TEXTURE, DEFAULT_TEXTURE);
        this.entityData.define(SCALE, 1.0f);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 35.0F));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    public String getGeo() {
        return this.entityData.get(GEO);
    }

    public void setGeo(String geo) {
        this.entityData.set(GEO, geo == null || geo.isBlank() ? DEFAULT_GEO : geo);
    }

    public String getTexture() {
        return this.entityData.get(TEXTURE);
    }

    public void setTexture(String tex) {
        this.entityData.set(TEXTURE, tex == null || tex.isBlank() ? DEFAULT_TEXTURE : tex);
    }

    public float getScaleValue() {
        return this.entityData.get(SCALE);
    }

    public void setScaleValue(float scale) {
        this.entityData.set(SCALE, scale <= 0 ? 1.0f : scale);
    }

    public void setSummoner(java.util.UUID uuid) {
        this.summoner = uuid;
    }

    public java.util.UUID getSummoner() {
        return summoner;
    }

    public void setShrinePos(net.minecraft.core.BlockPos pos) {
        this.shrinePos = pos == null ? 0L : pos.asLong();
    }

    public boolean hasGrantedWish() {
        return grantedWish;
    }

    /** Mark the wish granted and start the short despawn countdown (mirrors DMZ's 100-tick delay). */
    public void markWishGranted() {
        this.grantedWish = true;
        if (despawnDelay < 0) {
            despawnDelay = 100;
        }
    }

    public void configureLifetime(int summonDurationTicks, boolean darkenSky, long savedDayTime) {
        this.lifetime = Math.max(1, summonDurationTicks);
        this.darkenSky = darkenSky;
        this.savedDayTime = savedDayTime;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        age++;
        if (despawnDelay > 0) {
            despawnDelay--;
            if (despawnDelay == 0) {
                discard();
                return;
            }
        }
        if (age >= lifetime) {
            discard();
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide() && level() instanceof ServerLevel serverLevel && darkenSky) {
            // Restore exactly like DMZ: clear weather and restore the pre-summon day time.
            serverLevel.setWeatherParameters(6000, 0, false, false);
            if (savedDayTime >= 0) {
                serverLevel.setDayTime(savedDayTime);
            }
            darkenSky = false; // guard against double restore.
        }
        super.remove(reason);
    }

    // Non-interactable spectacle: no interaction ever opens a GUI (wish select comes via a packet).
    @Override
    public net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Immune to everything except fall damage and the out-of-world / generic-kill sources.
        if (source.is(DamageTypes.FALL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)
                || source.is(DamageTypes.GENERIC_KILL)) {
            return super.hurt(source, amount);
        }
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
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
    public void registerControllers(software.bernie.geckolib.core.animation.AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        state.getController().setAnimation(RawAnimation.begin().then("idle", Animation.LoopType.LOOP));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("sdu_geo", getGeo());
        tag.putString("sdu_texture", getTexture());
        tag.putFloat("sdu_scale", getScaleValue());
        if (summoner != null) {
            tag.putUUID("sdu_summoner", summoner);
        }
        tag.putLong("sdu_shrine_pos", shrinePos);
        tag.putBoolean("sdu_granted", grantedWish);
        tag.putInt("sdu_despawn_delay", despawnDelay);
        tag.putInt("sdu_lifetime", lifetime);
        tag.putInt("sdu_age", age);
        tag.putBoolean("sdu_darken_sky", darkenSky);
        tag.putLong("sdu_saved_day_time", savedDayTime);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("sdu_geo")) {
            setGeo(tag.getString("sdu_geo"));
        }
        if (tag.contains("sdu_texture")) {
            setTexture(tag.getString("sdu_texture"));
        }
        if (tag.contains("sdu_scale")) {
            setScaleValue(tag.getFloat("sdu_scale"));
        }
        if (tag.hasUUID("sdu_summoner")) {
            summoner = tag.getUUID("sdu_summoner");
        }
        shrinePos = tag.getLong("sdu_shrine_pos");
        grantedWish = tag.getBoolean("sdu_granted");
        despawnDelay = tag.contains("sdu_despawn_delay") ? tag.getInt("sdu_despawn_delay") : -1;
        lifetime = tag.contains("sdu_lifetime") ? tag.getInt("sdu_lifetime")
                : net.shurui.dev.sdu.shenron.ShrineColorConfig.DEFAULT_DURATION;
        age = tag.getInt("sdu_age");
        darkenSky = tag.getBoolean("sdu_darken_sky");
        savedDayTime = tag.contains("sdu_saved_day_time") ? tag.getLong("sdu_saved_day_time") : -1L;
    }
}
