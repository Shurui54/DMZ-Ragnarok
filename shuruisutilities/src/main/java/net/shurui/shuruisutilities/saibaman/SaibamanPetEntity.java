package net.shurui.shuruisutilities.saibaman;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

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

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * A TAMED saibaman companion: a vanilla-style pet built on {@link TamableAnimal}, wearing DragonMineZ's saibaman
 * GeckoLib art. It deliberately does NOT extend DragonMineZ's {@code SagaSaibamanEntity} / {@code DBSagasEntity}.
 * That entity is a hostile Monster whose {@code hurt} calls {@code setTarget} directly on the attacker, bypassing
 * {@code canAttack}; subclassing it would make a tamed saibaman retaliate against its own owner with no clean way to
 * suppress it. The vanilla tamable chassis gives correct owner-aware targeting (sit, follow, defend, retaliate for
 * the owner) for free, which is the whole reason for this base.
 *
 * <p>This is a {@link TamableAnimal}, NOT a DragonMineZ entity, so DragonMineZ's player/entity-join stat handler
 * never touches it. That is why NO {@code dmz_stats_configured} / {@code dmz_npc_defense} marker is stamped here:
 * those keys exist only to stop DragonMineZ re-clobbering the stats of ITS OWN saga entities at join, and they would
 * be cargo cult on an entity DragonMineZ ignores entirely.
 *
 * <p>All tunable stats (max health, attack damage, move speed, and the near-death explosion threshold, radius and
 * damage) come from {@link ConfigSaibamanPet}, edited from the SU admin GUI. {@link #applyConfigStats()} pushes the
 * current config onto the entity; {@link #spawnTamed} applies it at spawn and {@link #refreshAllLoadedFromConfig()}
 * re-applies it to already-loaded pets after a live edit, mirroring the hoverbike editor's live re-bake.
 */
public class SaibamanPetEntity extends TamableAnimal implements GeoEntity
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // saibaman skin variant 1..6, matching DragonMineZ's textures/entity/sagas/saga_saibaman{1..6}.png. Synced so
    // the client renderer can pick the texture, persisted so a pet keeps its face across a reload.
    private static final EntityDataAccessor<Integer> VARIANT =
            SynchedEntityData.defineId(SaibamanPetEntity.class, EntityDataSerializers.INT);

    private static final String KEY_VARIANT = "su_saibaman_variant";

    // the number of distinct saibaman skins DragonMineZ ships (saga_saibaman1..6.png).
    public static final int VARIANT_COUNT = 6;

    // quality 0..1: the fraction of the growing crop's life a player charged ki beside it, decided at harvest and then
    // fixed for the pet's whole life. Scales the combat stats via ConfigSaibamanPet.qualityMultiplier and picks the
    // pet's tier name/colour. Synced so a client could read it, persisted so a pet keeps its quality across a reload.
    private static final EntityDataAccessor<Float> QUALITY =
            SynchedEntityData.defineId(SaibamanPetEntity.class, EntityDataSerializers.FLOAT);

    private static final String KEY_QUALITY = "su_saibaman_quality";

    // quality a pet defaults to before it is set, and the value a legacy pet (saved before this feature) loads at.
    // 0.5 maps to a 1.0 stat multiplier, so legacy pets and any pet spawned without an explicit quality are unchanged.
    public static final float DEFAULT_QUALITY = 0.5f;

    // the pet's tier 1..ConfigSaibamanPet.TIER_COUNT, decided at harvest by how long a player charged ki beside the
    // growing crop. It fixes the pet's display name/colour and (at spawn) the percentage of the owner's stats the pet
    // was scaled to. Synced so a client could read it, persisted so the pet keeps its tier across a reload. This is the
    // primary quality field going forward; the legacy QUALITY float above is only read to derive a tier for pets saved
    // before tiers existed.
    private static final EntityDataAccessor<Integer> TIER =
            SynchedEntityData.defineId(SaibamanPetEntity.class, EntityDataSerializers.INT);

    private static final String KEY_TIER = "su_saibaman_tier";

    // tier a pet defaults to before it is set. spawnTamed always sets a real tier, so this is only a floor.
    public static final int DEFAULT_TIER = 1;

    // quality tiers, ascending (index 0 = tier 1). The tier decides the pet's display name and colour. Kept in code
    // (not config) because it is the same design curve as ConfigSaibamanPet.tierMultiplier.
    private enum QualityTier
    {
        FEEBLE("feeble", ChatFormatting.GRAY),
        SCRAPPY("scrappy", ChatFormatting.WHITE),
        STURDY("sturdy", ChatFormatting.GREEN),
        FIERCE("fierce", ChatFormatting.AQUA),
        SAVAGE("savage", ChatFormatting.LIGHT_PURPLE),
        ELITE("elite", ChatFormatting.GOLD);

        final String key;
        final ChatFormatting color;

        QualityTier(String key, ChatFormatting color)
        {
            this.key = key;
            this.color = color;
        }

        // legacy: derive a tier from a pre-tier pet's stored quality ratio, using the original quality bands.
        static QualityTier from(float ratio)
        {
            if (ratio < 0.20f) return FEEBLE;
            if (ratio < 0.40f) return SCRAPPY;
            if (ratio < 0.60f) return STURDY;
            if (ratio < 0.80f) return FIERCE;
            return ELITE;
        }

        // the tier for a 1-based tier index (tier 1 = FEEBLE .. tier 5 = ELITE), clamped to the enum range.
        static QualityTier ofIndex(int tier)
        {
            QualityTier[] values = values();
            int i = tier - 1;
            if (i < 0) i = 0;
            if (i >= values.length) i = values.length - 1;
            return values[i];
        }
    }

    // guards the near-death self-destruct so it fires exactly once. Set the tick it triggers, never reset.
    private boolean detonating = false;

    /** Flight speed attribute value. Roughly double a vanilla walking pace so the pet keeps up with a flying owner. */
    private static final double FLYING_SPEED = 0.6D;

    /**
     * Follow speed multiplier, and the distance band the pet holds around its owner.
     *
     * <p>Faster than the old ground follow (1.0) so it closes distance rather than trailing, and it starts following
     * from further out ({@code FOLLOW_START}) because a flying owner opens a gap much faster than a walking one.
     */
    private static final double FOLLOW_SPEED = 1.6D;
    private static final float FOLLOW_START = 12.0F;
    private static final float FOLLOW_STOP = 3.0F;

    public SaibamanPetEntity(EntityType<? extends SaibamanPetEntity> type, Level level)
    {
        super(type, level);
        // Flying pet. A hovering move control plus a flying navigation is the vanilla parrot/allay recipe: the mob
        // paths through open air instead of being confined to walkable ground, which is what lets it keep up with an
        // owner who is flying. maxTurn 20 and hoversInPlace true so it holds station rather than drifting down when
        // it has nowhere to be.
        this.moveControl = new FlyingMoveControl(this, 20, true);
        // Flying pets take no fall damage: with air pathing it would otherwise be hurt by its own navigation the
        // moment a path ended above ground.
        this.setPathfindingMalus(BlockPathTypes.DANGER_FIRE, -1.0F);
        this.setPathfindingMalus(BlockPathTypes.WATER, -1.0F);
    }

    /**
     * Air navigation, so the pet paths through open space. {@code setCanOpenDoors(false)} / {@code setCanFloat(true)}
     * mirror the vanilla flying-tamable setup; {@code setCanPassDoors} is left on so it can still follow an owner
     * indoors instead of stalling at a doorway.
     */
    @Override
    protected PathNavigation createNavigation(Level level)
    {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        navigation.setCanPassDoors(true);
        return navigation;
    }

    /** Flying: never hurt by falling, since its own pathing routinely leaves it in the air. */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source)
    {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state,
                                   net.minecraft.core.BlockPos pos)
    {
        // no fall distance accumulation at all, so nothing else can turn it into damage later
    }

    /**
     * Base attribute set. Values here are safe literals that match {@link ConfigSaibamanPet}'s defaults; the live
     * config values are pushed on at spawn by {@link #applyConfigStats()}, so a config edit does not need the
     * attribute supplier rebuilt.
     */
    public static AttributeSupplier.Builder createAttributes()
    {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, ConfigSaibamanPet.DEFAULT_MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, ConfigSaibamanPet.DEFAULT_MOVE_SPEED)
                .add(Attributes.ATTACK_DAMAGE, ConfigSaibamanPet.DEFAULT_ATTACK_DAMAGE)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                // Flight speed is a SEPARATE attribute from movement speed and is the one the flying navigation
                // actually uses; without it the pet flies at the vanilla default and trails badly behind an owner
                // who is moving. Set well above walking pace so "follows by flying fast" holds.
                .add(Attributes.FLYING_SPEED, FLYING_SPEED);
    }

    @Override
    protected void defineSynchedData()
    {
        super.defineSynchedData();
        this.entityData.define(VARIANT, 1);
        this.entityData.define(QUALITY, DEFAULT_QUALITY);
        this.entityData.define(TIER, DEFAULT_TIER);
    }

    /**
     * Vanilla tamable goal set: float, sit when ordered, melee its target, follow the owner, wander, and look
     * around. The target goals only ever pick a hostile or whatever hurt the owner, and are further filtered by the
     * owner guards in {@link #canAttack(LivingEntity)} and {@link #wantsToAttack(LivingEntity, LivingEntity)} so the
     * owner can never become a target down any path.
     */
    @Override
    protected void registerGoals()
    {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0D, true));
        // Flying follow. The trailing `true` is FollowOwnerGoal's own canFly flag: with it set the goal teleports
        // and paths in three dimensions instead of demanding walkable ground, which is the difference between a pet
        // that keeps up with a flying owner and one that gets stuck underneath them.
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, FOLLOW_SPEED, FOLLOW_START, FOLLOW_STOP, true));
        // Wanders through the air rather than along the ground, to match the flying navigation.
        this.goalSelector.addGoal(4, new WaterAvoidingRandomFlyingGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        // RETALIATION ONLY, on these two: they adopt whatever hurt the owner / whatever the owner is fighting. They
        // are further filtered by wantsToAttack below, so helping the owner can never turn into starting a fight
        // with a passive mob the owner happened to hit.
        this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(3, (new HurtByTargetGoal(this)).setAlertOthers());
        // defend the owner from nearby hostiles. Monster covers vanilla hostiles and DragonMineZ saga fighters
        // (which ARE Monsters); another player's SaibamanPetEntity is a TamableAnimal, not a Monster, so pets never
        // auto-aggro each other.
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, false));
    }

    /**
     * Hard owner guard for every goalSelector/targetSelector path that consults {@code canAttack} (HurtByTargetGoal,
     * NearestAttackableTargetGoal, MeleeAttackGoal's continue check). The owner and any other pet owned by the same
     * owner can never be attacked. Unlike DragonMineZ's saga entity, the vanilla base routes all of these through
     * this method, so this single override is sufficient to cover them.
     */
    @Override
    public boolean canAttack(LivingEntity target)
    {
        if (isSameOwnerParty(target))
        {
            return false;
        }
        return super.canAttack(target);
    }

    /**
     * The other owner-aware gate: OwnerHurtByTargetGoal / OwnerHurtTargetGoal ask this before adopting the owner's
     * attacker or victim as a target. Refuse the owner and same-owner pets, so "help the owner" can never turn into
     * "hit the owner" (e.g. the owner punching their own pet does not make it help-attack the owner).
     */
    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner)
    {
        if (isSameOwnerParty(target))
        {
            return false;
        }
        // HOSTILES ONLY. Both owner-help goals feed through here, and without this a pet adopts whatever its owner
        // swings at: punch a cow, a villager or another player and the saibaman piles in. That is the "attacks
        // everything it sees" behaviour. Restricting adoption to Monsters means helping the owner only ever means
        // joining a fight against something that was already hostile.
        //
        // Self-defence is deliberately NOT routed through here: HurtByTargetGoal sets its target directly when
        // something actually damages the pet, so a player who attacks a saibaman still gets fought back. Refusing
        // that too would make pets free to kill.
        return target instanceof Monster;
    }

    // true if target is this pet's owner, or another tamed pet sharing this pet's owner.
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
    public InteractionResult mobInteract(Player player, InteractionHand hand)
    {
        if (this.level().isClientSide)
        {
            // only the owner gets the sit-toggle / dismiss feedback; anyone else just passes.
            return isOwnedBy(player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (isOwnedBy(player))
        {
            // Dismiss path, freeing a saibaman-cap slot: a DELIBERATE two-step action so it cannot happen by
            // accident (players sneak constantly). First order the pet to sit (a plain right-click), then
            // sneak-right-click a SITTING pet to send it away. The blast/loot-free discard runs remove(DISCARDED),
            // which frees the slot in SaibamanPetRegistry. A high-tier pet earned by charging is not deleted on a
            // single stray click.
            if (player.isShiftKeyDown())
            {
                if (this.isOrderedToSit())
                {
                    player.sendSystemMessage(Component.translatable(
                            "message.dmz_ragnarok.core.saibaman.dismissed", getName()));
                    this.discard();
                    return InteractionResult.SUCCESS;
                }
                player.sendSystemMessage(Component.translatable(
                        "message.dmz_ragnarok.core.saibaman.dismiss_needs_sit"));
                return InteractionResult.SUCCESS;
            }
            this.setOrderedToSit(!this.isOrderedToSit());
            this.jumping = false;
            this.navigation.stop();
            this.setTarget(null);
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    /**
     * Free the owner's cap slot only on a PERMANENT removal (killed or discarded). A chunk unload or dimension change
     * ({@code reason.shouldDestroy()} false) leaves the pet tracked, because the entity still exists on disk and still
     * counts against the cap; it re-registers via {@link SaibamanPetRegistry#onJoin} when its chunk loads again.
     */
    @Override
    public void remove(RemovalReason reason)
    {
        if (!this.level().isClientSide && reason.shouldDestroy())
        {
            SaibamanPetRegistry.untrack(this.getUUID());
        }
        super.remove(reason);
    }

    // log an owner-stat read failure once per server run, not once per spawn, so a drifted DragonMineZ build cannot spam.
    private static boolean loggedOwnerStatFailure;

    /**
     * Bake this pet's combat stats (max health, attack damage) from a SNAPSHOT of its owner's live DragonMineZ stats,
     * scaled by the pet's tier: the pet's max health tracks the owner's max health and its attack tracks the owner's
     * (no-form-multiplier) melee damage, each times {@link ConfigSaibamanPet#tierMultiplier(int)}. The snapshot is taken
     * once, at harvest, and then fixed for the pet's life (the base values persist through vanilla's attribute save), so
     * the pet does not grow or shrink as its owner powers up or down later.
     *
     * <p>If the owner has no readable DragonMineZ character (no {@link StatsData}, or a drifted build), it falls back to
     * the flat {@link ConfigSaibamanPet} max-health/attack values, still scaled by the tier multiplier, so an
     * owner-less or non-DMZ spawn still produces a sensibly tiered pet instead of crashing. Move speed always comes from
     * config (a poor saibaman is weaker, not slower, so it keeps up with its owner). Does NOT re-fill health;
     * {@link #spawnTamed} heals to full after this.
     */
    public void bakeStatsFromOwner(@Nullable Player owner, int tier)
    {
        double mult = ConfigSaibamanPet.tierMultiplier(tier);
        double ownerMaxHealth = 0.0;
        double ownerMelee = 0.0;
        if (owner != null && StatsCapability.INSTANCE != null)
        {
            try
            {
                StatsData data = owner.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
                if (data != null)
                {
                    ownerMaxHealth = data.getMaxHealth();
                    // no-form-multiplier melee so a transformed owner does not spawn a wildly inflated pet; the tier
                    // (earned by charging) is what makes a saibaman strong, not the owner's momentary form.
                    ownerMelee = data.getMeleeDamageNoMultipliers();
                }
            }
            catch (Throwable t)
            {
                if (!loggedOwnerStatFailure)
                {
                    loggedOwnerStatFailure = true;
                    LoggingHandler.sulog.warn(
                            "[Saibaman] DragonMineZ owner-stat read failed; tamed saibaman fell back to config stats. Cause: {}",
                            t.toString());
                }
            }
        }

        double health = ownerMaxHealth > 0.0 ? ownerMaxHealth * mult : ConfigSaibamanPet.maxHealth * mult;
        double attack = ownerMelee > 0.0 ? ownerMelee * mult : ConfigSaibamanPet.attackDamage * mult;
        setBase(Attributes.MAX_HEALTH, Math.max(1.0, health));
        setBase(Attributes.ATTACK_DAMAGE, Math.max(0.0, attack));
        setBase(Attributes.MOVEMENT_SPEED, ConfigSaibamanPet.moveSpeed);
        if (this.getHealth() > this.getMaxHealth())
        {
            this.setHealth(this.getMaxHealth());
        }
    }

    /**
     * Re-apply the live-tunable config to this pet after an admin edit. Only the config-driven fields are touched: move
     * speed (and a clamp so a lowered value cannot leave a pet above its max). The combat stats (health, attack) are a
     * fixed owner-scaled snapshot taken at harvest by {@link #bakeStatsFromOwner} and persisted as attribute base
     * values, so they are deliberately NOT re-derived here; the explosion is read live from config at detonate time.
     */
    public void reapplyLiveConfig()
    {
        setBase(Attributes.MOVEMENT_SPEED, ConfigSaibamanPet.moveSpeed);
        if (this.getHealth() > this.getMaxHealth())
        {
            this.setHealth(this.getMaxHealth());
        }
    }

    private void setBase(net.minecraft.world.entity.ai.attributes.Attribute attribute, double value)
    {
        AttributeInstance inst = this.getAttribute(attribute);
        if (inst != null)
        {
            inst.setBaseValue(value);
        }
    }

    @Override
    public void tick()
    {
        super.tick();
        if (this.level().isClientSide || this.detonating)
        {
            return;
        }
        // trigger once when health falls to or below the configured fraction of max health, while still alive.
        float max = this.getMaxHealth();
        if (max > 0.0F && this.isAlive() && this.getHealth() > 0.0F
                && this.getHealth() <= max * ConfigSaibamanPet.explosionThreshold)
        {
            detonate();
        }
    }

    /**
     * Canon near-death self-destruct. The saibaman dies in the process. The blast breaks NO blocks and never harms
     * the owner: rather than a vanilla {@code Level.explode} (which would damage everything in range including the
     * owner and also risk block edits depending on interaction mode), the damage is applied MANUALLY in a radius so
     * the owner and same-owner pets can be excluded precisely, and only particles + sound are borrowed from vanilla.
     */
    private void detonate()
    {
        this.detonating = true;
        double x = this.getX();
        double y = this.getY() + this.getBbHeight() * 0.5D;
        double z = this.getZ();
        double radius = Math.max(0.0D, ConfigSaibamanPet.explosionRadius);
        // the signature kamikaze also scales with tier: a higher-tier saibaman goes out with a bigger bang.
        double damage = Math.max(0.0D, ConfigSaibamanPet.explosionDamage * ConfigSaibamanPet.tierMultiplier(getTier()));

        if (this.level() instanceof ServerLevel server && radius > 0.0D && damage > 0.0D)
        {
            DamageSource source = this.damageSources().explosion(this, this);
            AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
            List<LivingEntity> victims = server.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != this && e.isAlive() && !isSameOwnerParty(e));
            for (LivingEntity victim : victims)
            {
                double dist = Math.sqrt(victim.distanceToSqr(x, y, z));
                if (dist > radius)
                {
                    continue;
                }
                // linear falloff from full damage at the centre to zero at the edge.
                float dealt = (float) (damage * (1.0D - dist / radius));
                if (dealt > 0.0F)
                {
                    victim.hurt(source, dealt);
                }
            }
            // spectacle only, no block interaction: the emitter particle + generic explosion sound.
            server.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        this.level().playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
                4.0F, (1.0F + (this.level().random.nextFloat() - this.level().random.nextFloat()) * 0.2F) * 0.7F);
        // the saibaman is consumed by its own blast. discard (not kill) so there is no death message or loot.
        this.discard();
    }

    public int getVariant()
    {
        return this.entityData.get(VARIANT);
    }

    public void setVariant(int variant)
    {
        this.entityData.set(VARIANT, Mth.clamp(variant, 1, VARIANT_COUNT));
    }

    public float getQuality()
    {
        return this.entityData.get(QUALITY);
    }

    public void setQuality(float quality)
    {
        this.entityData.set(QUALITY, Mth.clamp(quality, 0.0f, 1.0f));
    }

    public int getTier()
    {
        return this.entityData.get(TIER);
    }

    public void setTier(int tier)
    {
        this.entityData.set(TIER, Mth.clamp(tier, 1, ConfigSaibamanPet.TIER_COUNT));
    }

    // set the pet's display name to its tier, coloured by tier (e.g. a gold "Elite Saibaman"). The custom name is NOT
    // marked always-visible, so it does not clutter a whole farm with floating labels; vanilla still shows it when the
    // owner looks directly at the pet, which is the "look at it to see how good it is" feedback. This is the only
    // persistent quality readout, and it is an entity name tag, not GUI chrome.
    private void applyTierName()
    {
        QualityTier tier = QualityTier.ofIndex(getTier());
        Component name = Component.translatable("entity.dmz_ragnarok.saibaman_pet.tier." + tier.key)
                .append(" ")
                .append(Component.translatable("entity.dmz_ragnarok.saibaman_pet"))
                .withStyle(tier.color);
        this.setCustomName(name);
    }

    // the on-hatch chat line, naming the tier the seed produced in the tier's colour. A chat message, not GUI chrome.
    private static Component hatchMessage(int tier)
    {
        QualityTier band = QualityTier.ofIndex(tier);
        Component tierName = Component.translatable("entity.dmz_ragnarok.saibaman_pet.tier." + band.key)
                .withStyle(band.color);
        return Component.translatable("message.dmz_ragnarok.core.saibaman.hatched", tierName);
    }

    @Override
    public boolean isFood(net.minecraft.world.item.ItemStack stack)
    {
        return false;
    }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob parent)
    {
        // saibaman pets do not breed; they are grown from a plant. A follow-up task owns that plant.
        return null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        tag.putInt(KEY_VARIANT, getVariant());
        tag.putFloat(KEY_QUALITY, getQuality());
        tag.putInt(KEY_TIER, getTier());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_VARIANT))
        {
            setVariant(tag.getInt(KEY_VARIANT));
        }
        // legacy pets have no quality tag and keep DEFAULT_QUALITY (a 1.0 stat multiplier), so they are unchanged.
        if (tag.contains(KEY_QUALITY))
        {
            setQuality(tag.getFloat(KEY_QUALITY));
        }
        // tier is the primary field going forward. A pet saved before tiers existed has no tier tag: derive its tier
        // from its stored legacy quality band so its name still reads correctly (its persisted attribute stats are
        // untouched, so a legacy pet's combat power does not change).
        if (tag.contains(KEY_TIER))
        {
            setTier(tag.getInt(KEY_TIER));
        }
        else if (tag.contains(KEY_QUALITY))
        {
            setTier(QualityTier.from(tag.getFloat(KEY_QUALITY)).ordinal() + 1);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "controller", 4, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state)
    {
        // DragonMineZ's saga_saibaman animation ships idle + walk; drive walk while moving, idle otherwise.
        if (state.isMoving())
        {
            state.getController().setAnimation(RawAnimation.begin().then("walk", Animation.LoopType.LOOP));
        }
        else
        {
            state.getController().setAnimation(RawAnimation.begin().then("idle", Animation.LoopType.LOOP));
        }
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }

    /**
     * Spawn ONE saibaman pet already tamed to {@code owner}, scaled to a percentage of the owner's DragonMineZ stats
     * decided by {@code tier}, with a random skin variant chosen, a tier name applied, healed to full, and added to the
     * level. This is the exact entry point the crop's harvest calls. Returns the spawned entity, or {@code null} if
     * creation failed.
     *
     * @param level the server level to spawn into
     * @param pos   the block position to spawn at (entity is centred on it)
     * @param owner the player who owns the pet (also the stat source the pet is scaled from)
     * @param tier  1..{@link ConfigSaibamanPet#TIER_COUNT}, decided by how long a player charged ki beside the crop;
     *              picks the tier name and the percentage of the owner's stats the pet is scaled to
     */
    public static SaibamanPetEntity spawnTamed(ServerLevel level, BlockPos pos, Player owner, int tier)
    {
        SaibamanPetEntity pet = SaibamanPetEntities.SAIBAMAN_PET.get().create(level);
        if (pet == null)
        {
            return null;
        }
        pet.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        // COLOUR IS THE TIER. The skin variant used to be rolled at random and told you nothing; now the six DMZ
        // saibaman textures are the six tiers in order, so a saibaman's colour reads its strength on sight. Clamped
        // inside setVariant, so a tier outside 1..6 still lands on a real texture.
        pet.setVariant(tier);
        pet.setTier(tier);
        if (owner != null)
        {
            pet.tame(owner);
        }
        // bake the owner-scaled stats for this tier, then fill health so the pet spawns at full.
        pet.bakeStatsFromOwner(owner, tier);
        pet.setHealth(pet.getMaxHealth());
        pet.applyTierName();
        pet.setPersistenceRequired();
        level.addFreshEntity(pet);
        // tell the owner what they grew. spawnTamed always runs server-side, so owner is a ServerPlayer here.
        if (owner instanceof ServerPlayer serverOwner)
        {
            serverOwner.sendSystemMessage(hatchMessage(tier));
        }
        return pet;
    }

    /**
     * Re-apply the current config to every loaded saibaman pet across all levels after a live admin edit, mirroring
     * {@code HoverbikeEntity.refreshAllLoadedFromConfig}. A full scan is fine because admin edits are rare. No-op if
     * no server is running.
     */
    public static void refreshAllLoadedFromConfig()
    {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return;
        }
        for (ServerLevel level : server.getAllLevels())
        {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities())
            {
                if (e instanceof SaibamanPetEntity pet)
                {
                    pet.reapplyLiveConfig();
                }
            }
        }
    }
}
