package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import net.shurui.shuruisutilities.hoverbike.HoverbikeItems;
import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;
import net.shurui.shuruisutilities.saiyan.SaiyanArmorSets;

/**
 * A saiyan TRADER for Planet Vegeta's town: a vanilla {@link Villager} subclass that sells saiyan gear for emeralds,
 * mirroring the trade MECHANISM of DragonMineZ's {@code NamekTraderEntity} (a static trade pool, three random offers on
 * first open, one more per restock, lazy {@link #getOffers}). Currency is {@code minecraft:emerald}.
 *
 * <h2>Look</h2>
 * The trader carries the SAME {@link SaiyanAppearance} fields as the town's other saiyans and reuses the shared
 * custom-character render stack, so it visually belongs to the town. It rolls with named NPCs DISABLED
 * ({@code roll(random, false)}): a shopkeeper must never wear one of the three real-player skins, which are reserved for
 * the rare garrison warriors. That keeps a single renderer while giving the trader a plain saiyan look.
 *
 * <h2>Not a Namek population mechanic</h2>
 * DMZ's village-alert system (a Namek mechanic) is deliberately NOT copied: hurting this trader raises no swarm. The
 * never-despawn and no-baby-aging overrides ARE copied so the town's shop is permanent. It is a {@link PlanetGarrisonHome}
 * so the shared flagless confinement goal keeps it inside the stamped town disc once the populate task assigns a home.
 * It never carries the garrison's {@code su_planet_defender} marker, so it is never swept and never blocks a claim.
 */
public class SaiyanTraderEntity extends Villager
        implements GeoEntity, PlanetGarrisonHome, SaiyanAppearance, net.shurui.dev.sdu.api.SpaceTownNpc
{
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // saga_base looped clips (served by the shared model) so a standing trader breathes and a walking one strides,
    // instead of the frozen T-pose a no-controller GeoEntity would show.
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    // saga_base's dedicated tail sway. The idle/walk clips do NOT touch the tail bones, so a saiyan's tail only moves if a
    // SEPARATE controller loops this clip, exactly as DragonMineZ's own DBSagasEntity registers a standalone tail_controller
    // alongside its movement controller. Without this the tail hangs frozen behind a moving body.
    private static final RawAnimation TAIL = RawAnimation.begin().thenLoop("tail");

    // the shared saiyan-gear trade pool, built once when the class first loads (well after registries are frozen, since
    // an entity is only ever constructed at runtime). Emeralds in, saiyan gear out.
    private static final List<CustomTrade> TRADES = new ArrayList<>();

    static
    {
        // the space-pod chip is a genuine ENDGAME buy: SU's own curios pod chip (NOT DMZ's saiyan_ship, which would fork
        // the pod feature outside SU's curios system). Priced at the emerald cap plus a stack of diamonds, single use.
        addTrade(emeralds(64), new ItemStack(Items.DIAMOND, 16), su(HoverbikeItems.POD_CHIP.get(), 1), 1, 30);

        // saiyan armor: mid priced (the Vegeta set has no helmet, so only three pieces).
        addTrade(emeralds(24), ItemStack.EMPTY, dmz("vegeta_saiyan_armor_chestplate", 1), 4, 12);
        addTrade(emeralds(20), ItemStack.EMPTY, dmz("vegeta_saiyan_armor_leggings", 1), 4, 12);
        addTrade(emeralds(14), ItemStack.EMPTY, dmz("vegeta_saiyan_armor_boots", 1), 4, 10);

        // scouters: cheap cosmetic colour variants (no tiers).
        addTrade(emeralds(6), ItemStack.EMPTY, dmz("red_scouter", 1), 8, 4);
        addTrade(emeralds(6), ItemStack.EMPTY, dmz("blue_scouter", 1), 8, 4);
        addTrade(emeralds(6), ItemStack.EMPTY, dmz("green_scouter", 1), 8, 4);
        addTrade(emeralds(6), ItemStack.EMPTY, dmz("purple_scouter", 1), 8, 4);

        // radar chips: cheap filler, matching what the namek trader already sells.
        addTrade(emeralds(4), ItemStack.EMPTY, dmz("t1_radar_chip", 1), 8, 3);
        addTrade(emeralds(16), ItemStack.EMPTY, dmz("t2_radar_chip", 1), 4, 6);
    }

    // add a trade only if its output actually resolves, so a missing/renamed DMZ item simply drops that one offer from
    // the pool rather than seeding an empty trade.
    private static void addTrade(ItemStack input, ItemStack secondary, ItemStack output, int maxUses, int xp)
    {
        if (output.isEmpty())
        {
            return;
        }
        TRADES.add(new CustomTrade(input, secondary, output, maxUses, xp));
    }

    private static ItemStack emeralds(int count)
    {
        return new ItemStack(Items.EMERALD, count);
    }

    private static ItemStack su(Item item, int count)
    {
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    // resolve a DragonMineZ item by id; an empty stack if it is missing (then addTrade drops the offer).
    private static ItemStack dmz(String id, int count)
    {
        Item item = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("dragonminez", id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    private static final EntityDataAccessor<Boolean> MALE =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BODY_TYPE =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_ID =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYES_TYPE =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> NOSE_TYPE =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MOUTH_TYPE =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SKIN_COLOR =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TAIL_COLOR =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_COLOR =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE1_COLOR =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EYE2_COLOR =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);
    // synced scouter colour worn on the head (0 = none); a cosmetic the trader shares with the town's other saiyans.
    private static final EntityDataAccessor<Integer> SCOUTER_COLOR =
            SynchedEntityData.defineId(SaiyanTraderEntity.class, EntityDataSerializers.INT);

    // NBT keys (per-entity, so sharing the spelling with the other saiyans is safe).
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
    private static final String KEY_HOME = "su_saiyan_home";
    private static final String KEY_SCOUTER = "su_saiyan_scouter";

    // the id of the town's planet, or empty before it is assigned. Server-authoritative.
    private String homePlanetId = "";

    // transient guard so appearance is rolled exactly once. randomize() (the PlanetGarrison call) and finalizeSpawn (the
    // spawner / egg / region path) both set it; finalizeSpawn refuses to roll if it is already set, so the two entry
    // points can never double-roll or clobber each other. Not persisted: finalizeSpawn does not run on an NBT reload, so
    // a loaded trader keeps the face it already had regardless of this flag.
    private boolean appearanceRolled = false;

    public SaiyanTraderEntity(EntityType<? extends Villager> type, Level level)
    {
        super(type, level);
        // the town's shop must not despawn; the NITWIT profession stops the villager brain layering its own
        // profession-based trades or gossip on top of our fixed saiyan pool.
        this.setPersistenceRequired();
        this.setVillagerData(this.getVillagerData().setProfession(VillagerProfession.NITWIT));
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return Villager.createAttributes();
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
        this.entityData.define(SCOUTER_COLOR, SaiyanAppearance.SCOUTER_NONE);
    }

    @Override
    protected void registerGoals()
    {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.25D));
        this.goalSelector.addGoal(2, new RandomStrollGoal(this, 0.9D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
        // flagless self-confinement, inert until the populate task assigns a home planet.
        this.goalSelector.addGoal(0, new PlanetGarrisonDefenderConfineGoal(this));
    }

    @Override
    protected void updateTrades()
    {
        if (this.offers == null)
        {
            this.offers = new MerchantOffers();
        }
        RandomSource random = this.getRandom();
        if (this.offers.isEmpty())
        {
            // first open: three random offers from the pool.
            List<CustomTrade> available = new ArrayList<>(TRADES);
            for (int i = 0; i < 3 && !available.isEmpty(); i++)
            {
                CustomTrade trade = available.remove(random.nextInt(available.size()));
                this.offers.add(trade.createOffer());
            }
        }
        else
        {
            // restock: add one more offer not already present.
            List<CustomTrade> available = new ArrayList<>(TRADES);
            for (MerchantOffer offer : this.offers)
            {
                available.removeIf(trade -> trade.createOffer().equals(offer));
            }
            if (!available.isEmpty())
            {
                CustomTrade trade = available.get(random.nextInt(available.size()));
                this.offers.add(trade.createOffer());
            }
        }
    }

    @Override
    public MerchantOffers getOffers()
    {
        if (this.offers == null)
        {
            this.offers = new MerchantOffers();
            this.updateTrades();
        }
        return this.offers;
    }

    @Override
    public boolean isPersistenceRequired()
    {
        return true;
    }

    @Override
    public void checkDespawn()
    {
        // never despawn: the town's shop is permanent.
    }

    @Override
    public boolean wantsToSpawnGolem(long gameTime)
    {
        return false;
    }

    @Override
    public boolean showProgressBar()
    {
        return false;
    }

    @Override
    public void setBaby(boolean baby)
    {
        // the trader is always an adult; never age it into a baby.
    }

    // a saiyan shopkeeper must not hum, yelp or grunt like a villager. Ambient/death are muted (both null-safe in
    // LivingEntity), hurt uses a neutral player yell, and the two trade cues use a neutral pickup blip (they are played
    // directly and must stay non-null to avoid an NPE), so trading still gives audible feedback without the villager voice.

    @Override
    protected SoundEvent getAmbientSound()
    {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source)
    {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound()
    {
        return null;
    }

    @Override
    public SoundEvent getNotifyTradeSound()
    {
        return SoundEvents.EXPERIENCE_ORB_PICKUP;
    }

    @Override
    protected SoundEvent getTradeUpdatedSound(boolean success)
    {
        return SoundEvents.EXPERIENCE_ORB_PICKUP;
    }

    /**
     * Roll this trader's look once, on the server, through the shared {@link SaiyanAppearance#roll} with named NPCs
     * DISABLED, so a shopkeeper is always a plain custom-character saiyan.
     */
    public void randomize(RandomSource random)
    {
        SaiyanAppearance.Roll roll = SaiyanAppearance.roll(random, false);
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
        // the town's shopkeeper wears a scouter too; roll its colour on the server so it syncs and persists.
        this.entityData.set(SCOUTER_COLOR, SaiyanAppearance.rollScouterColor(random));
        this.appearanceRolled = true;
    }

    /**
     * Give a trader placed by ANY vanilla spawn path (advanced spawner, spawn egg, NPC region, raid, dungeon) the same
     * randomized look and armor the planet-populate task gives it, so no spawner-placed trader is left pale and bare.
     * The populate task in {@link PlanetGarrison} does its own {@code create + randomize + equip + addFreshEntity} and
     * never routes through here, so those traders are untouched; the {@link #appearanceRolled} guard makes a double
     * entry a no-op regardless. This does NOT run on an NBT reload, which is right: a reloaded trader keeps its face.
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
            // randomize already rolls the scouter for a trader, so equipping armor completes the same look the town gets.
            randomize(random);
            SaiyanArmorSets.equip(this, random);
        }
        return data;
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

    /** A trader is never a named NPC (rolled with named disabled), so this is always null. */
    @Override
    public NamedSaiyan getNamed()
    {
        return null;
    }

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

    /** Assign the town's planet, so the confinement goal keeps this trader inside the stamped town disc. */
    public void setHomePlanet(String planetId)
    {
        this.homePlanetId = planetId == null ? "" : planetId;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        // one movement controller so the trader idles when still and walks when moving; the head is additionally driven
        // by the model's setCustomAnimations. Without this the GeoEntity would stand in a frozen bind pose.
        controllers.add(new AnimationController<>(this, "move", 5, state ->
                state.setAndContinue(state.isMoving() ? WALK : IDLE)));
        // a second, independent controller that loops the tail sway on the tail1..tail5 bones, which the movement clips
        // leave untouched. Mirrors DBSagasEntity's tail_controller so the trader's tail moves like the garrison's.
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
        if (tag.contains(KEY_SCOUTER))
        {
            this.entityData.set(SCOUTER_COLOR, tag.getInt(KEY_SCOUTER));
        }
        this.homePlanetId = tag.getString(KEY_HOME);
        // a trader restored from NBT already has its face; finalizeSpawn will not run for it, so mark it rolled so no
        // later entry point re-rolls it.
        this.appearanceRolled = true;
    }

    private record CustomTrade(ItemStack input, ItemStack secInput, ItemStack output, int maxUses, int xp)
    {
        MerchantOffer createOffer()
        {
            return new MerchantOffer(this.input, this.secInput, this.output, this.maxUses, this.xp, 0.15F);
        }
    }
}
