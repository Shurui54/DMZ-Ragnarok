package net.shurui.shuruisutilities.hoverbike;

import net.minecraft.nbt.CompoundTag;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

// Hoverbikes.toml: per-bike base speed (blocks/tick-ish scalar), shared sprint mult, sound toggle
public class ConfigHoverbikes
{
    private static final String category = "Hoverbikes";

    // baked speeds, 1-4 (index 0 unused)
    public static final double[] speed = new double[5];

    public static double sprintMultiplier;
    public static boolean soundEnabled;
    /** Blocks a player may walk away from their deployed vehicle before it is recalled. 0 turns the recall off. */
    public static int recallDistance = 10;

    static final ForgeConfigSpec.DoubleValue[] SUspeed = new ForgeConfigSpec.DoubleValue[5];
    static ForgeConfigSpec.DoubleValue SUsprintMultiplier;
    static ForgeConfigSpec.BooleanValue SUsoundEnabled;
    static ForgeConfigSpec.IntValue SUrecallDistance;

    private static final double[] DEFAULT_SPEED = { 0.0, 0.35, 0.40, 0.45, 0.50 };

    public static void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.push(category);

        for (int v = 1; v <= 4; v++)
        {
            SUspeed[v] = BUILDER
                    .comment("Base ground speed of hoverbike " + v + " (blocks/tick scalar on rider forward input).")
                    .defineInRange("speed_" + v, DEFAULT_SPEED[v], SPEED_MIN, SPEED_MAX);
        }

        SUsprintMultiplier = BUILDER
                .comment("Speed multiplier applied while the riding player holds the sprint key.")
                .defineInRange("sprintMultiplier", 1.6, SPRINT_MIN, SPRINT_MAX);

        SUsoundEnabled = BUILDER.comment("Play the looping engine/rev sound while a hoverbike is driven.")
                .define("soundEnabled", true);

        SUrecallDistance = BUILDER.comment("Blocks a player may go from their deployed vehicle (hoverbike, space pod, nimbus "
                        + "or time machine) before it is recalled to its slot. Leaving it in another dimension counts as "
                        + "too far. 0 turns the recall off.")
                .defineInRange("recallDistance", 10, 0, Integer.MAX_VALUE);

        BUILDER.pop();
    }

    public static void bakeConfig(boolean reload)
    {
        for (int v = 1; v <= 4; v++)
            speed[v] = SUspeed[v].get();
        sprintMultiplier = SUsprintMultiplier.get();
        soundEnabled = SUsoundEnabled.get();
        recallDistance = SUrecallDistance.get();

        // on a live reload push the baked speed onto every loaded bike so the client picks it up on a dedicated
        // server. the server-start bake (reload==false) runs before any level exists, and the refresh no-ops
        // without a server anyway; spawn + first-tick refresh cover that.
        if (reload)
            HoverbikeEntity.refreshAllLoadedFromConfig();
    }

    // No ceiling on either. 5.0 and 10.0 were numbers somebody picked, and a ceiling on the operator's own toml only
    // ever shows itself as "the speed I set is not the speed that was saved". The floors stay: a negative speed would
    // drive the bike backwards on forward input, and a sprint multiplier below 1 would make sprinting slower.
    public static final double SPEED_MIN = 0.0, SPEED_MAX = Double.MAX_VALUE;
    public static final double SPRINT_MIN = 1.0, SPRINT_MAX = Double.MAX_VALUE;

    private static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // write new settings into the spec, persist Hoverbikes.toml, re-bake so it takes effect live. clamped to
    // spec ranges. from the admin editor after the op-gate. safe pre-server-start: then it only updates baked
    // fields. newSpeed is indexed 1-4 (0 ignored).
    public static void applyAndSave(double[] newSpeed, double newSprint, boolean newSound)
    {
        boolean specLoaded = SUsprintMultiplier != null;
        for (int v = 1; v <= 4; v++)
        {
            double s = clamp(newSpeed[v], SPEED_MIN, SPEED_MAX);
            speed[v] = s;
            if (specLoaded && SUspeed[v] != null)
                SUspeed[v].set(s);
        }
        sprintMultiplier = clamp(newSprint, SPRINT_MIN, SPRINT_MAX);
        soundEnabled = newSound;
        if (specLoaded)
        {
            SUsprintMultiplier.set(sprintMultiplier);
            SUsoundEnabled.set(soundEnabled);
            try
            {
                // any one save() flushes the whole backing file
                SUsprintMultiplier.save();
            }
            catch (IllegalStateException ignored)
            {
                // no file assigned yet (spec not loaded); baked fields still updated
            }
        }

        // push the new speed onto every loaded bike. the GUI editor path calls this directly and doesn't fire
        // ConfigReloadEvent, and COMMON config isn't synced, so without this the client drives on stale values
        // on a dedicated server. edits are rare so the full scan is fine.
        HoverbikeEntity.refreshAllLoadedFromConfig();
    }

    /** Snapshot the baked values for {@code ShardStateSync}. Deterministic order so the hash only moves on a real edit. */
    public static CompoundTag saveState()
    {
        CompoundTag t = new CompoundTag();
        for (int v = 1; v <= 4; v++)
            t.putDouble("speed_" + v, speed[v]);
        t.putDouble("sprintMultiplier", sprintMultiplier);
        t.putBoolean("soundEnabled", soundEnabled);
        return t;
    }

    /**
     * Apply a sibling server's Hoverbikes.toml through the SAME path the local editor uses, so the values are clamped,
     * persisted to this server's own toml, and re-baked onto every loaded bike live, with no restart. Whole-record
     * last-write-wins, the same trade the config-file sync makes: a concurrent edit of the SAME record keeps the later
     * one, and there is nothing per-key here to drop.
     */
    public static void mergeState(CompoundTag t)
    {
        if (t == null)
            throw new IllegalArgumentException("null hoverbike config state");
        double[] newSpeed = new double[5];
        for (int v = 1; v <= 4; v++)
            newSpeed[v] = t.getDouble("speed_" + v);
        applyAndSave(newSpeed, t.getDouble("sprintMultiplier"), t.getBoolean("soundEnabled"));
    }
}
