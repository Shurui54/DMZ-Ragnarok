package net.shurui.shuruisutilities.vehicleworkbench;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

/**
 * The [VehicleTiers] section of Hoverbikes.toml: how far vehicles can be upgraded and what each tier is worth.
 * Every multiplier is linear in the tier: tier t gives {@code 1 + perTier * (t - 1)}.
 *
 * <p>Read on the server, which is where tiers are granted and where the multipliers are stamped onto the vehicle (the
 * hoverbike's synced speed, a DMZ vehicle's synced attribute modifiers), so a client's own copy never decides them.
 * The benches send the server's max tier and switches to their screen through menu data slots.
 */
public final class VehicleTierConfig
{
    private VehicleTierConfig() {}

    private static final String CATEGORY = "VehicleTiers";

    /** Hard ceiling: the upgrade bench has five vehicle slots, and merging into tier t+1 takes t copies. */
    public static final int TIER_CEILING = 6;

    public static int maxTier = 4;
    public static double speedPerTier = 0.12D;
    public static double accelerationPerTier = 0.15D;
    public static double climbPerTier = 0.10D;
    public static double durabilityPerTier = 0.50D;
    public static boolean mergeEnabled = true;
    public static boolean kitsEnabled = true;

    private static ForgeConfigSpec.IntValue cMaxTier;
    private static ForgeConfigSpec.DoubleValue cSpeed;
    private static ForgeConfigSpec.DoubleValue cAccel;
    private static ForgeConfigSpec.DoubleValue cClimb;
    private static ForgeConfigSpec.DoubleValue cDurability;
    private static ForgeConfigSpec.BooleanValue cMerge;
    private static ForgeConfigSpec.BooleanValue cKits;

    public static void load(Builder b)
    {
        b.push(CATEGORY);
        cMaxTier = b.comment("Highest vehicle tier (1 turns upgrading off). Applies to the hoverbikes and DragonMineZ's "
                        + "car, Capsule Corp. 576 and Frieza capsule (the dmz_ragnarok:tiered_vehicles item tag).")
                .defineInRange("maxTier", 4, 1, TIER_CEILING);
        cSpeed = b.comment("Extra top speed per tier above 1 (0.12 = +12% per tier).")
                .defineInRange("speedPerTier", 0.12D, 0.0D, 10.0D);
        cAccel = b.comment("Extra acceleration per tier above 1.")
                .defineInRange("accelerationPerTier", 0.15D, 0.0D, 10.0D);
        cClimb = b.comment("Extra climb and dive speed per tier above 1, for flying vehicles.")
                .defineInRange("climbPerTier", 0.10D, 0.0D, 10.0D);
        cDurability = b.comment("Extra hits a hoverbike takes before it breaks, per tier above 1. DragonMineZ vehicles "
                        + "have no durability (a hit packs them up), so this is hoverbike only.")
                .defineInRange("durabilityPerTier", 0.50D, 0.0D, 10.0D);
        cMerge = b.comment("Allow merging at the compact console: two tier 1 or tier 2 copies make the next tier, then "
                        + "three tier 3 copies make tier 4, and so on (tier t needs t copies).")
                .define("mergeEnabled", true);
        cKits = b.comment("Allow upgrade kits at the compact console (one vehicle plus kits; the kit count doubles "
                        + "each tier, set per kit in its vehicle_upgrade recipe).")
                .define("kitsEnabled", true);
        b.pop();
    }

    public static void bake()
    {
        if (cMaxTier == null)
            return;
        maxTier = cMaxTier.get();
        speedPerTier = cSpeed.get();
        accelerationPerTier = cAccel.get();
        climbPerTier = cClimb.get();
        durabilityPerTier = cDurability.get();
        mergeEnabled = cMerge.get();
        kitsEnabled = cKits.get();
    }
}
