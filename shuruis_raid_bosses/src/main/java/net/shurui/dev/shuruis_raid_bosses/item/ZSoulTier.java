package net.shurui.dev.shuruis_raid_bosses.item;

import net.minecraft.ChatFormatting;
import net.shurui.dev.shuruis_raid_bosses.Config;

/**
 * Z-Soul tiers, ascending. Each grants headroom past the global cap as a configurable percentage of it.
 * Defaults: bronze 25%, silver 50%, gold 76%, prismatic 100% (doubles the cap).
 */
public enum ZSoulTier {
    BRONZE("bronze", ChatFormatting.GOLD),
    SILVER("silver", ChatFormatting.GRAY),
    GOLD("gold", ChatFormatting.YELLOW),
    PRISMATIC("prismatic", ChatFormatting.LIGHT_PURPLE);

    public final String id;
    public final ChatFormatting colour;

    ZSoulTier(String id, ChatFormatting colour) {
        this.id = id;
        this.colour = colour;
    }

    /** Extra headroom this tier grants, as a percentage of the global stat cap (from config). */
    public double percentOfCap() {
        // .get() reflects a retune from another shard: RaidStateSync's zsoul:tuning pushes it in via
        // ConfigValue.set() (see RaidStateSync.writeZSoulTuning).
        return switch (this) {
            case BRONZE -> Config.ZSOUL_BRONZE_PERCENT.get();
            case SILVER -> Config.ZSOUL_SILVER_PERCENT.get();
            case GOLD -> Config.ZSOUL_GOLD_PERCENT.get();
            case PRISMATIC -> Config.ZSOUL_PRISMATIC_PERCENT.get();
        };
    }

    /** Maximum beyond-cap bonus (in stat points) this tier allows given the global cap {@code globalCap}. */
    public int maxBonus(int globalCap) {
        return (int) Math.round(globalCap * (percentOfCap() / 100.0));
    }
}
