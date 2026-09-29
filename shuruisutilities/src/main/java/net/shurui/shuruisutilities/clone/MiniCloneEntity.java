package net.shurui.shuruisutilities.clone;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * A short-lived miniature clone of a caster, summoned by the {@code su_mini_clone} technique. It is a real server
 * entity (a vanilla {@link TamableAnimal}), not a client-only puppet, so the vanilla glowing outline and normal
 * targeting work on it; the CLIENT renderer (written separately) reads the synced fields below to draw it at 60%
 * scale wearing either the owner's player skin or a Majin Buu / Cell Jr. tint.
 *
 * <p>DragonMineZ is a mandatory dependency, so this class references {@link StatsData} directly (as
 * {@code SaibamanPetEntity} does) but every capability read is wrapped in {@code try/catch(Throwable)} so a statless
 * owner or a drifted DMZ build degrades to a config fallback rather than crashing.
 *
 * <h2>Lifetime</h2>
 * The clone lives 90 seconds. Expiry is an ABSOLUTE game time ({@code level().getGameTime() + LIFETIME_TICKS}),
 * persisted in NBT, NOT a per-tick counter: a counter pauses whenever the clone's chunk unloads, which would let it
 * outlive its 90 seconds. It is discarded server side in {@link #tick()} once game time passes the stored deadline.
 * It deliberately does NOT call {@code setPersistenceRequired}, so vanilla despawn stays as a safety net against an
 * orphan that somehow loses its owner.
 */
public class MiniCloneEntity extends TamableAnimal
{
    /** The three ways a clone can be drawn. The renderer keys off {@link #getVariant()}. */
    public enum Variant
    {
        /** Wears the owner's player skin, resolved on the client from {@link #getOwnerName()} / {@link #getOwnerProfileId()}. */
        PLAYER_COPY,
        /** A miniature Majin Buu, tinted by {@link #getTintColor()} (the caster's Majin body colour). */
        BUU,
        /** A miniature Cell Jr., tinted by {@link #getTintColor()} (the caster's bio-android hair colour). */
        CELL_JR;

        public int getId()
        {
            return ordinal();
        }

        public static Variant byId(int id)
        {
            Variant[] values = values();
            if (id < 0 || id >= values.length)
            {
                return PLAYER_COPY;
            }
            return values[id];
        }
    }

    /** Render scale: a clone is 60% of the caster. Fixed, so it is a constant rather than synced state. */
    public static final float CLONE_SCALE = 0.6F;

    /** 90 seconds, in ticks. */
    public static final int LIFETIME_TICKS = 1800;

    /** The fraction of the caster's BASE stats a clone receives: one third. */
    public static final double STAT_FRACTION = 1.0 / 3.0;

    // Safe fallbacks when the owner has no readable DMZ character (statless, or a drifted build).
    private static final double FALLBACK_BASE_HEALTH = 20.0;
    private static final double FALLBACK_BASE_MELEE = 2.0;

    private static final EntityDataAccessor<Integer> VARIANT =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TINT =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> OWNER_NAME =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<UUID>> OWNER_PROFILE =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    // DragonMineZ race appearance of the caster, meaningful only for PLAYER_COPY. The client render layer paints the
    // caster's race body textures (Namekian green, Frost Demon layers, Shadow Dragon, etc) over the vanilla skin, tinted
    // by these colours the way DMZ's own DMZSkinLayer does. Colours are packed 0xRRGGBB, resolved server side.
    private static final EntityDataAccessor<String> RACE_NAME =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> BODY_TYPE =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BODY_COLOR1 =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BODY_COLOR2 =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BODY_COLOR3 =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_COLOR =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE1_COLOR =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE2_COLOR =
            SynchedEntityData.defineId(MiniCloneEntity.class, EntityDataSerializers.INT);

    /** Identity for a colour multiply: 0xFFFFFF leaves the texture unchanged, so it is the safe "no tint" default. */
    private static final int NO_TINT = 0xFFFFFF;

    private static final String KEY_VARIANT = "su_clone_variant";
    private static final String KEY_TINT = "su_clone_tint";
    private static final String KEY_OWNER_NAME = "su_clone_owner_name";
    private static final String KEY_OWNER_PROFILE = "su_clone_owner_profile";
    private static final String KEY_EXPIRE = "su_clone_expire";
    private static final String KEY_RACE = "su_clone_race";
    private static final String KEY_BODY_TYPE = "su_clone_body_type";
    private static final String KEY_BODY1 = "su_clone_body1";
    private static final String KEY_BODY2 = "su_clone_body2";
    private static final String KEY_BODY3 = "su_clone_body3";
    private static final String KEY_HAIR = "su_clone_hair";
    private static final String KEY_EYE1 = "su_clone_eye1";
    private static final String KEY_EYE2 = "su_clone_eye2";

    /** Absolute game time at which this clone is discarded. 0 means unset (a freshly created, not-yet-spawned clone). */
    private long expireGameTime;

    public MiniCloneEntity(EntityType<? extends MiniCloneEntity> type, Level level)
    {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, FALLBACK_BASE_HEALTH * STAT_FRACTION)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.ATTACK_DAMAGE, FALLBACK_BASE_MELEE * STAT_FRACTION)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void defineSynchedData()
    {
        super.defineSynchedData();
        this.entityData.define(VARIANT, Variant.PLAYER_COPY.getId());
        this.entityData.define(TINT, 0xFFFFFF);
        this.entityData.define(OWNER_NAME, "");
        this.entityData.define(OWNER_PROFILE, Optional.empty());
        this.entityData.define(RACE_NAME, "");
        this.entityData.define(BODY_TYPE, 0);
        this.entityData.define(BODY_COLOR1, NO_TINT);
        this.entityData.define(BODY_COLOR2, NO_TINT);
        this.entityData.define(BODY_COLOR3, NO_TINT);
        this.entityData.define(HAIR_COLOR, NO_TINT);
        this.entityData.define(EYE1_COLOR, NO_TINT);
        this.entityData.define(EYE2_COLOR, NO_TINT);
    }

    /**
     * The {@code SaibamanPetEntity} goal set: float, melee its target, follow the owner, wander, and look around, with
     * the same two owner-help target goals and self-defence. Every target path is filtered by the owner guards in
     * {@link #canAttack(LivingEntity)} / {@link #wantsToAttack(LivingEntity, LivingEntity)} so the caster and the
     * caster's other clones can never become a target.
     */
    @Override
    protected void registerGoals()
    {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.4D, 10.0F, 3.0F, false));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(3, (new HurtByTargetGoal(this)).setAlertOthers());
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, false));
    }

    @Override
    public boolean canAttack(LivingEntity target)
    {
        if (isSameOwnerParty(target))
        {
            return false;
        }
        return super.canAttack(target);
    }

    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner)
    {
        if (isSameOwnerParty(target))
        {
            return false;
        }
        // Adopt the owner's fight only against actual hostiles, so helping the caster never turns into piling on a
        // passive mob the caster happened to hit. Self-defence is not routed through here (HurtByTargetGoal sets its
        // target directly), so a clone still fights back anything that damages it.
        return target instanceof Monster;
    }

    /** True when the target is this clone's owner, or another clone / tamed pet of the same owner. */
    private boolean isSameOwnerParty(LivingEntity target)
    {
        if (target == null)
        {
            return false;
        }
        LivingEntity owner = getOwner();
        if (owner == null)
        {
            return false;
        }
        if (target == owner)
        {
            return true;
        }
        if (target instanceof TamableAnimal other)
        {
            UUID myOwner = getOwnerUUID();
            return myOwner != null && myOwner.equals(other.getOwnerUUID());
        }
        return false;
    }

    @Override
    public void tick()
    {
        super.tick();
        if (this.level().isClientSide)
        {
            return;
        }
        // Absolute-game-time expiry: robust to chunk unloads that would freeze a tick counter.
        if (this.expireGameTime > 0L && this.level().getGameTime() >= this.expireGameTime)
        {
            this.discard();
        }
    }

    /** Start the 90 second clock from now. Called once at spawn. */
    public void beginLifetime()
    {
        this.expireGameTime = this.level().getGameTime() + LIFETIME_TICKS;
    }

    /**
     * Ticks of life this clone has left, floored at zero.
     *
     * <p>The stored {@link #expireGameTime} is an ABSOLUTE game time on THIS level, which is meaningless on another
     * server (game time does not match between two worlds). A shard hop reads this REMAINING count on the origin and
     * rebuilds the deadline from the destination's own game time with {@link #setRemainingLifetimeTicks}, so a carried
     * clone resumes with the time it had left rather than a fresh 90 seconds or an instant death. A not-yet-started
     * clone (expiry unset) reports its full lifetime.
     */
    public long getRemainingLifetimeTicks()
    {
        if (this.expireGameTime <= 0L)
        {
            return LIFETIME_TICKS;
        }
        return Math.max(0L, this.expireGameTime - this.level().getGameTime());
    }

    /**
     * Rebuild the absolute expiry from THIS level's game time plus a carried remaining count. Used on the destination
     * side of a shard hop to resume the clock the origin measured, in the destination's own game time.
     */
    public void setRemainingLifetimeTicks(long remaining)
    {
        this.expireGameTime = this.level().getGameTime() + Math.max(0L, remaining);
    }

    @Override
    public void remove(RemovalReason reason)
    {
        if (!this.level().isClientSide && reason.shouldDestroy())
        {
            MiniCloneRegistry.untrack(this.getUUID());
            MiniClone.clearGreenOutline(this);
        }
        super.remove(reason);
    }

    private static boolean loggedOwnerStatFailure;

    /**
     * Bake this clone's combat stats from a snapshot of its owner's BASE DragonMineZ stats, scaled to
     * {@link #STAT_FRACTION}. "Base" means WITHOUT any transformation/form multiplier, so a transformed caster does
     * not summon a stronger clone.
     *
     * <p>Melee uses {@code getMeleeDamageNoMultipliers()}, which DMZ already strips of form multipliers. Health has no
     * form-free accessor: {@code getMaxHealth()} reads the fully modified MAX_HEALTH attribute, and
     * {@code getHealthBonus()} (the value of DMZ's additive "DMZ Health" attribute modifier) is scaled by
     * {@code getTotalMultiplier("VIT")}, which multiplies in {@code getFormMultiplier} and {@code getStackFormMultiplier}.
     * So the base health is recovered by dividing that form component back out and re-adding the vanilla 20 base the
     * attribute carries. This is EXACT in DMZ's default multiplicative-multiplier mode
     * ({@code getMultiplicationInsteadOfAdditionForMultipliers}); in the optional additive mode the multipliers are
     * summed rather than multiplied, so the division is a close approximation. In base form both form multipliers are
     * 1.0, so a non-transformed caster's base health is read exactly either way.
     */
    public void bakeBaseStatsFromOwner(@Nullable Player owner)
    {
        double baseHealth = FALLBACK_BASE_HEALTH;
        double baseMelee = FALLBACK_BASE_MELEE;
        if (owner != null && StatsCapability.INSTANCE != null)
        {
            try
            {
                StatsData data = owner.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
                if (data != null)
                {
                    double formMult = data.getFormMultiplier("VIT");
                    double stackMult = data.getStackFormMultiplier("VIT");
                    if (formMult <= 0.0)
                    {
                        formMult = 1.0;
                    }
                    if (stackMult <= 0.0)
                    {
                        stackMult = 1.0;
                    }
                    double formFreeHealthBonus = data.getHealthBonus() / (formMult * stackMult);
                    baseHealth = 20.0 + formFreeHealthBonus;
                    baseMelee = data.getMeleeDamageNoMultipliers();
                }
            }
            catch (Throwable t)
            {
                if (!loggedOwnerStatFailure)
                {
                    loggedOwnerStatFailure = true;
                    LoggingHandler.sulog.warn(
                            "[MiniClone] DragonMineZ owner-stat read failed; clone fell back to base stats. Cause: {}",
                            t.toString());
                }
            }
        }

        setBase(Attributes.MAX_HEALTH, Math.max(1.0, baseHealth * STAT_FRACTION));
        setBase(Attributes.ATTACK_DAMAGE, Math.max(0.0, baseMelee * STAT_FRACTION));
        if (this.getHealth() > this.getMaxHealth())
        {
            this.setHealth(this.getMaxHealth());
        }
    }

    private void setBase(Attribute attribute, double value)
    {
        AttributeInstance inst = this.getAttribute(attribute);
        if (inst != null)
        {
            inst.setBaseValue(value);
        }
    }

    public Variant getVariant()
    {
        return Variant.byId(this.entityData.get(VARIANT));
    }

    public void setVariant(Variant variant)
    {
        this.entityData.set(VARIANT, variant == null ? Variant.PLAYER_COPY.getId() : variant.getId());
    }

    /** Packed 0xRRGGBB. Meaningful for {@link Variant#BUU} and {@link Variant#CELL_JR}; 0xFFFFFF (no tint) otherwise. */
    public int getTintColor()
    {
        return this.entityData.get(TINT) & 0xFFFFFF;
    }

    public void setTintColor(int rgb)
    {
        this.entityData.set(TINT, rgb & 0xFFFFFF);
    }

    /** The caster's name, used by the client to resolve the skin for a {@link Variant#PLAYER_COPY}. */
    public String getOwnerName()
    {
        return this.entityData.get(OWNER_NAME);
    }

    public void setOwnerName(String name)
    {
        this.entityData.set(OWNER_NAME, name == null ? "" : name);
    }

    /** The caster's UUID, used by the client to resolve the skin for a {@link Variant#PLAYER_COPY}. */
    public UUID getOwnerProfileId()
    {
        return this.entityData.get(OWNER_PROFILE).orElse(null);
    }

    public void setOwnerProfileId(@Nullable UUID id)
    {
        this.entityData.set(OWNER_PROFILE, Optional.ofNullable(id));
    }

    /** The caster's DMZ race id ({@code human}, {@code namekian}, a custom id like {@code shadow_dragon}, ...). */
    public String getRaceName()
    {
        return this.entityData.get(RACE_NAME);
    }

    public void setRaceName(String race)
    {
        this.entityData.set(RACE_NAME, race == null ? "" : race);
    }

    /** The caster's DMZ body type, which picks the {@code bodytype_<N>_layerK} texture set. */
    public int getBodyType()
    {
        return this.entityData.get(BODY_TYPE);
    }

    public void setBodyType(int bodyType)
    {
        this.entityData.set(BODY_TYPE, Math.max(0, bodyType));
    }

    /** Packed 0xRRGGBB primary body colour; multiplied over the race's first body layer. */
    public int getBodyColor1()
    {
        return this.entityData.get(BODY_COLOR1) & 0xFFFFFF;
    }

    /** Packed 0xRRGGBB secondary body colour; multiplied over the race's second body layer. */
    public int getBodyColor2()
    {
        return this.entityData.get(BODY_COLOR2) & 0xFFFFFF;
    }

    /** Packed 0xRRGGBB tertiary body colour; multiplied over the race's third body layer. */
    public int getBodyColor3()
    {
        return this.entityData.get(BODY_COLOR3) & 0xFFFFFF;
    }

    /** Packed 0xRRGGBB hair colour; DMZ multiplies it over the race's fourth body layer (antennae, fins). */
    public int getHairColor()
    {
        return this.entityData.get(HAIR_COLOR) & 0xFFFFFF;
    }

    /** Packed 0xRRGGBB first eye colour. Synced for completeness; the body-layer pass does not consume it. */
    public int getEye1Color()
    {
        return this.entityData.get(EYE1_COLOR) & 0xFFFFFF;
    }

    /** Packed 0xRRGGBB second eye colour. Synced for completeness; the body-layer pass does not consume it. */
    public int getEye2Color()
    {
        return this.entityData.get(EYE2_COLOR) & 0xFFFFFF;
    }

    /**
     * Copy the caster's DMZ race appearance onto this clone, server side, so the client render layer can repaint the
     * vanilla skin with the race's body textures. Colours are already parsed to packed 0xRRGGBB by the caller (which
     * keeps DMZ's client-only ColorUtils off the server). Only PLAYER_COPY consumes these; the themed variants ignore
     * them.
     */
    public void setRaceAppearance(String race, int bodyType, int bodyColor1, int bodyColor2, int bodyColor3,
                                  int hairColor, int eye1Color, int eye2Color)
    {
        setRaceName(race);
        setBodyType(bodyType);
        this.entityData.set(BODY_COLOR1, bodyColor1 & 0xFFFFFF);
        this.entityData.set(BODY_COLOR2, bodyColor2 & 0xFFFFFF);
        this.entityData.set(BODY_COLOR3, bodyColor3 & 0xFFFFFF);
        this.entityData.set(HAIR_COLOR, hairColor & 0xFFFFFF);
        this.entityData.set(EYE1_COLOR, eye1Color & 0xFFFFFF);
        this.entityData.set(EYE2_COLOR, eye2Color & 0xFFFFFF);
    }

    /** The render scale of a clone: 60% of the caster. */
    public float getCloneScale()
    {
        return CLONE_SCALE;
    }

    @Override
    public boolean isFood(ItemStack stack)
    {
        return false;
    }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob parent)
    {
        return null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        tag.putInt(KEY_VARIANT, getVariant().getId());
        tag.putInt(KEY_TINT, getTintColor());
        tag.putString(KEY_OWNER_NAME, getOwnerName());
        UUID profile = getOwnerProfileId();
        if (profile != null)
        {
            tag.putUUID(KEY_OWNER_PROFILE, profile);
        }
        tag.putLong(KEY_EXPIRE, this.expireGameTime);
        tag.putString(KEY_RACE, getRaceName());
        tag.putInt(KEY_BODY_TYPE, getBodyType());
        tag.putInt(KEY_BODY1, getBodyColor1());
        tag.putInt(KEY_BODY2, getBodyColor2());
        tag.putInt(KEY_BODY3, getBodyColor3());
        tag.putInt(KEY_HAIR, getHairColor());
        tag.putInt(KEY_EYE1, getEye1Color());
        tag.putInt(KEY_EYE2, getEye2Color());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        setVariant(Variant.byId(tag.getInt(KEY_VARIANT)));
        if (tag.contains(KEY_TINT))
        {
            setTintColor(tag.getInt(KEY_TINT));
        }
        if (tag.contains(KEY_OWNER_NAME))
        {
            setOwnerName(tag.getString(KEY_OWNER_NAME));
        }
        setOwnerProfileId(tag.hasUUID(KEY_OWNER_PROFILE) ? tag.getUUID(KEY_OWNER_PROFILE) : null);
        this.expireGameTime = tag.getLong(KEY_EXPIRE);
        if (tag.contains(KEY_RACE))
        {
            setRaceName(tag.getString(KEY_RACE));
        }
        setBodyType(tag.getInt(KEY_BODY_TYPE));
        setRaceAppearance(getRaceName(), tag.getInt(KEY_BODY_TYPE),
                tag.contains(KEY_BODY1) ? tag.getInt(KEY_BODY1) : NO_TINT,
                tag.contains(KEY_BODY2) ? tag.getInt(KEY_BODY2) : NO_TINT,
                tag.contains(KEY_BODY3) ? tag.getInt(KEY_BODY3) : NO_TINT,
                tag.contains(KEY_HAIR) ? tag.getInt(KEY_HAIR) : NO_TINT,
                tag.contains(KEY_EYE1) ? tag.getInt(KEY_EYE1) : NO_TINT,
                tag.contains(KEY_EYE2) ? tag.getInt(KEY_EYE2) : NO_TINT);
    }

    /**
     * Spawn one clone already tamed to {@code owner}, at {@code pos}, wearing {@code variant} tinted by {@code tint},
     * scaled to one third of the owner's BASE stats, its 90 second clock started, healed to full, and added to the
     * level. Returns the spawned clone, or {@code null} if creation failed.
     */
    public static MiniCloneEntity spawnClone(ServerLevel level, BlockPos pos, Player owner,
                                             Variant variant, int tint)
    {
        MiniCloneEntity clone = MiniCloneEntities.MINI_CLONE.get().create(level);
        if (clone == null)
        {
            return null;
        }
        clone.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        clone.setVariant(variant);
        clone.setTintColor(tint);
        if (owner != null)
        {
            clone.tame(owner);
            clone.setOwnerName(owner.getGameProfile().getName());
            clone.setOwnerProfileId(owner.getUUID());
        }
        clone.bakeBaseStatsFromOwner(owner);
        clone.setHealth(clone.getMaxHealth());
        clone.beginLifetime();
        level.addFreshEntity(clone);
        return clone;
    }
}
