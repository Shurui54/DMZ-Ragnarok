package net.shurui.shuruisutilities.saibaman;

import net.minecraft.nbt.CompoundTag;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

/**
 * SaibamanPet.toml: the admin-tunable stats for the tamed saibaman companion, edited from the SU admin GUI's
 * "Saibaman Pets" editor. Baked static fields are read by {@link SaibamanPetEntity}; {@link #applyAndSave} is the
 * editor's write path. Follows {@link net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes} exactly (single
 * record, clamp-and-persist, live re-bake onto loaded entities).
 */
public class ConfigSaibamanPet
{
    private static final String category = "SaibamanPets";

    // baked values
    public static double maxHealth;
    public static double attackDamage;
    public static double moveSpeed;
    // fraction of max health at or below which the pet self-destructs (0.0-1.0)
    public static double explosionThreshold;
    public static double explosionRadius;
    public static double explosionDamage;
    // default per-player live-pet cap. Declared ahead of the baked field below because a static initialiser may only
    // read a constant by simple name if that constant is declared first.
    public static final int DEFAULT_MAX_PETS = 3;
    // the most live saibaman pets one player may have at once on THIS server (per shard, not per network). Initialised
    // to the default so a cap check that somehow runs before bakeConfig (e.g. during construction) is never zero.
    public static int maxPetsPerPlayer = DEFAULT_MAX_PETS;

    static ForgeConfigSpec.DoubleValue SUmaxHealth;
    static ForgeConfigSpec.DoubleValue SUattackDamage;
    static ForgeConfigSpec.DoubleValue SUmoveSpeed;
    static ForgeConfigSpec.DoubleValue SUexplosionThreshold;
    static ForgeConfigSpec.DoubleValue SUexplosionRadius;
    static ForgeConfigSpec.DoubleValue SUexplosionDamage;
    static ForgeConfigSpec.IntValue SUmaxPetsPerPlayer;

    // defaults, also used by SaibamanPetEntity.createAttributes so the attribute supplier matches the config
    public static final double DEFAULT_MAX_HEALTH = 20.0;
    public static final double DEFAULT_ATTACK_DAMAGE = 4.0;
    public static final double DEFAULT_MOVE_SPEED = 0.3;
    public static final double DEFAULT_THRESHOLD = 0.2;
    public static final double DEFAULT_RADIUS = 3.0;
    public static final double DEFAULT_DAMAGE = 6.0;
    // DEFAULT_MAX_PETS sits with the baked fields at the top of the class, not here, so it can seed maxPetsPerPlayer.
    public static final int MAX_PETS_MIN = 1, MAX_PETS_MAX = 256;

    // quality scaling: a harvested saibaman's combat stats (max health, attack damage, near-death explosion damage)
    // are multiplied by qualityMultiplier(ratio), where ratio is the fraction of the crop's growth time a player
    // spent charging ki beside it (0..1). The multiplier runs linearly from QUALITY_MIN_MULT at ratio 0 to
    // QUALITY_MAX_MULT at ratio 1, so a neglected seed is noticeably weaker and a fully tended one noticeably
    // stronger, without either extreme being useless or absurd. The midpoint (ratio 0.5) is exactly 1.0, which is
    // also the multiplier a legacy pet saved before this feature loads at, so existing pets are left unchanged.
    // These are plain constants (not in the toml) on purpose: they are a design curve, not an admin knob, so the
    // SaibamanPet.toml editor GUI and its parameter list stay untouched.
    public static final double QUALITY_MIN_MULT = 0.6;
    public static final double QUALITY_MAX_MULT = 1.4;

    public static double qualityMultiplier(float ratio)
    {
        double r = ratio < 0.0f ? 0.0 : (ratio > 1.0f ? 1.0 : ratio);
        return QUALITY_MIN_MULT + (QUALITY_MAX_MULT - QUALITY_MIN_MULT) * r;
    }

    // A harvested saibaman scales to a PERCENTAGE of its owner's live DragonMineZ stats: its max health tracks the
    // owner's max health and its melee attack tracks the owner's (no-form-multiplier) melee damage, both times a
    // per-tier percentage. The tier is decided by how long a player charged ki beside the growing crop (see
    // tierFromChargedRatio). Tier 1 is TIER_BASE_PERCENT of the owner's stats and every tier above is worth
    // (1 + TIER_STEP) times the tier below it, so each tier is "10% more than the last". Top tier is
    // 0.50 * 1.10^5 = about 81% of the owner, so a saibaman is a strong helper that never outclasses its owner.
    // These are plain design constants (not toml knobs), exactly like QUALITY_MIN_MULT above, so the SaibamanPet.toml
    // editor and its parameter list stay untouched. The flat maxHealth/attackDamage toml values remain the FALLBACK
    // for a pet whose owner has no readable DragonMineZ character (see SaibamanPetEntity.spawnTamed).
    // SIX tiers, one per saibaman COLOUR. DragonMineZ ships six saga saibaman textures
    // (textures/entity/sagas/saga_saibaman1..6.png), the pet already carried a 1..6 skin variant, and the tier now IS
    // that variant: a saibaman's colour tells you its tier on sight instead of being decorative randomness.
    public static final int TIER_COUNT = 6;
    public static final double TIER_BASE_PERCENT = 0.50;
    // 10% per tier, so tier 6 is 0.50 * 1.10^5 = about 81% of the owner. Still short of the owner's own stats, which
    // is the point: a saibaman is a strong helper that never outclasses the player who grew it.
    public static final double TIER_STEP = 0.10;

    // percentage of the owner's stat a tier is worth. tier is clamped to 1..TIER_COUNT.
    public static double tierMultiplier(int tier)
    {
        int t = tier < 1 ? 1 : (tier > TIER_COUNT ? TIER_COUNT : tier);
        return TIER_BASE_PERCENT * Math.pow(1.0 + TIER_STEP, t - 1);
    }

    // Tier is decided by the FRACTION of a crop's growth that a player spent charging beside it, not by an absolute
    // number of charged minutes.
    //
    // ABSOLUTE MINUTES WERE A TRAP. The old thresholds (10/25/40/60 charged minutes) were calibrated against the
    // four-hour senzu pace this crop used to borrow. The moment the grow time was retuned to wheat's, a crop could
    // finish long before 60 minutes of charging could physically be banked, so the top tiers became unreachable no
    // matter how attentively it was tended. A ratio holds its meaning at any grow time: charge beside it the whole
    // way and you get the top tier whether that took four hours or twenty minutes.
    //
    // Even fifths up to tier 5, then a deliberately tight last step: tier 6 wants 95% of the grow charged, not 80%,
    // so the top colour means someone actually stayed with the crop rather than wandering off near the end.
    private static final double[] TIER_RATIO_FLOOR = { 0.0, 0.20, 0.40, 0.60, 0.80, 0.95 };

    /**
     * Map "how much of its growth was charged" to a tier 1..{@link #TIER_COUNT}.
     *
     * @param chargedTicks ticks during which a player was charging ki in range
     * @param totalTicks   ticks the crop took to grow; a non-positive value degrades to tier 1 rather than dividing
     */
    public static int tierFromChargedRatio(long chargedTicks, long totalTicks)
    {
        if (totalTicks <= 0L || chargedTicks <= 0L)
            return 1;
        double ratio = (double) chargedTicks / (double) totalTicks;
        int tier = 1;
        for (int i = 0; i < TIER_RATIO_FLOOR.length && i < TIER_COUNT; i++)
        {
            if (ratio >= TIER_RATIO_FLOOR[i])
                tier = i + 1;
        }
        return tier;
    }

    // spec ranges (mirror the defineInRange calls, reused by the clamp in applyAndSave).
    // The three STAT ranges have no ceiling on purpose. They used to stop at 1024 health, 1024 damage and 2.0 speed,
    // which is a number somebody picked, not a limit the attribute system has, and the only way a ceiling on an
    // operator's own toml ever shows itself is as "the value I set is not the value that was saved". The floors stay:
    // 1 health because a 0-health pet is dead on spawn, and 0 for damage and speed because negatives are meaningless.
    // The other two ranges are genuinely bounded by what they mean, not by taste: the threshold is a fraction of max
    // health, and the radius is a blast area the server has to resolve block by block.
    public static final double HEALTH_MIN = 1.0, HEALTH_MAX = Double.MAX_VALUE;
    public static final double DAMAGE_MIN = 0.0, DAMAGE_MAX = Double.MAX_VALUE;
    public static final double SPEED_MIN = 0.0, SPEED_MAX = Double.MAX_VALUE;
    public static final double THRESHOLD_MIN = 0.0, THRESHOLD_MAX = 1.0;
    public static final double RADIUS_MIN = 0.0, RADIUS_MAX = 32.0;

    public static void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.push(category);

        SUmaxHealth = BUILDER.comment("Max health of a tamed saibaman pet.")
                .defineInRange("maxHealth", DEFAULT_MAX_HEALTH, HEALTH_MIN, HEALTH_MAX);
        SUattackDamage = BUILDER.comment("Melee attack damage of a tamed saibaman pet.")
                .defineInRange("attackDamage", DEFAULT_ATTACK_DAMAGE, DAMAGE_MIN, DAMAGE_MAX);
        SUmoveSpeed = BUILDER.comment("Movement-speed attribute of a tamed saibaman pet.")
                .defineInRange("moveSpeed", DEFAULT_MOVE_SPEED, SPEED_MIN, SPEED_MAX);
        SUexplosionThreshold = BUILDER
                .comment("Fraction of max health at or below which the pet self-destructs (0.0-1.0).")
                .defineInRange("explosionThreshold", DEFAULT_THRESHOLD, THRESHOLD_MIN, THRESHOLD_MAX);
        SUexplosionRadius = BUILDER.comment("Radius (blocks) of the near-death explosion. Breaks no blocks.")
                .defineInRange("explosionRadius", DEFAULT_RADIUS, RADIUS_MIN, RADIUS_MAX);
        SUexplosionDamage = BUILDER
                .comment("Peak damage of the near-death explosion at its centre (falls off to 0 at the edge). "
                        + "Never harms the owner or the owner's other pets.")
                .defineInRange("explosionDamage", DEFAULT_DAMAGE, DAMAGE_MIN, DAMAGE_MAX);
        SUmaxPetsPerPlayer = BUILDER
                .comment("Maximum number of live saibaman pets one player may have at once on this server. "
                        + "Counted per server (per shard), not across the shard network. Growing a new pet is "
                        + "refused (the crop is left standing) once a player is at this many live pets.")
                .defineInRange("maxPetsPerPlayer", DEFAULT_MAX_PETS, MAX_PETS_MIN, MAX_PETS_MAX);

        BUILDER.pop();
    }

    public static void bakeConfig(boolean reload)
    {
        maxHealth = SUmaxHealth.get();
        attackDamage = SUattackDamage.get();
        moveSpeed = SUmoveSpeed.get();
        explosionThreshold = SUexplosionThreshold.get();
        explosionRadius = SUexplosionRadius.get();
        explosionDamage = SUexplosionDamage.get();
        maxPetsPerPlayer = SUmaxPetsPerPlayer.get();

        if (reload)
        {
            SaibamanPetEntity.refreshAllLoadedFromConfig();
        }
    }

    private static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Write new settings into the spec, persist SaibamanPet.toml, re-bake so it takes effect live, and push the new
     * stats onto every loaded pet. Values are clamped to the spec ranges. Safe before server start (spec not loaded):
     * then only the baked fields are updated.
     */
    public static void applyAndSave(double newMaxHealth, double newAttack, double newSpeed,
                                    double newThreshold, double newRadius, double newDamage)
    {
        maxHealth = clamp(newMaxHealth, HEALTH_MIN, HEALTH_MAX);
        attackDamage = clamp(newAttack, DAMAGE_MIN, DAMAGE_MAX);
        moveSpeed = clamp(newSpeed, SPEED_MIN, SPEED_MAX);
        explosionThreshold = clamp(newThreshold, THRESHOLD_MIN, THRESHOLD_MAX);
        explosionRadius = clamp(newRadius, RADIUS_MIN, RADIUS_MAX);
        explosionDamage = clamp(newDamage, DAMAGE_MIN, DAMAGE_MAX);

        boolean specLoaded = SUmaxHealth != null;
        if (specLoaded)
        {
            SUmaxHealth.set(maxHealth);
            SUattackDamage.set(attackDamage);
            SUmoveSpeed.set(moveSpeed);
            SUexplosionThreshold.set(explosionThreshold);
            SUexplosionRadius.set(explosionRadius);
            SUexplosionDamage.set(explosionDamage);
            try
            {
                // any one save() flushes the whole backing file
                SUmaxHealth.save();
            }
            catch (IllegalStateException ignored)
            {
                // no file assigned yet (spec not loaded); baked fields still updated
            }
        }

        SaibamanPetEntity.refreshAllLoadedFromConfig();
    }

    /**
     * Snapshot the network-shared baked stats for {@code ShardStateSync}. Deliberately EXCLUDES
     * {@link #maxPetsPerPlayer}: that cap is per server (per shard), as its own config comment states, so it must
     * stay local and never travel.
     */
    public static CompoundTag saveState()
    {
        CompoundTag t = new CompoundTag();
        t.putDouble("maxHealth", maxHealth);
        t.putDouble("attackDamage", attackDamage);
        t.putDouble("moveSpeed", moveSpeed);
        t.putDouble("explosionThreshold", explosionThreshold);
        t.putDouble("explosionRadius", explosionRadius);
        t.putDouble("explosionDamage", explosionDamage);
        return t;
    }

    /**
     * Apply a sibling server's SaibamanPet.toml stats through the SAME {@link #applyAndSave} path the local editor
     * uses: clamped, persisted to this server's toml, and re-baked onto every loaded pet live, no restart. The
     * per-shard pet cap is not in the payload, so it is left exactly as this server has it. Whole-record last-write
     * -wins for the six shared stats.
     */
    public static void mergeState(CompoundTag t)
    {
        if (t == null)
            throw new IllegalArgumentException("null saibaman pet config state");
        applyAndSave(t.getDouble("maxHealth"), t.getDouble("attackDamage"), t.getDouble("moveSpeed"),
                t.getDouble("explosionThreshold"), t.getDouble("explosionRadius"), t.getDouble("explosionDamage"));
    }
}
