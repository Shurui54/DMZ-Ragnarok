package net.shurui.dev.shuruis_dmz_dungeons.block;

import java.util.ArrayList;
import java.util.List;

// Per-theme, per-TIER starter loot so a fresh floor's crates are never empty before an admin edits them. A tier with
// no configured drops falls back to dropsFor(theme, tier); an unset zeni range (max <= 0) to zeniFor(theme, tier).
// VANILLA ids only, on purpose: this class must not classload DMZ/SU types, and vanilla ids always resolve.
//
// Tiers scale the SAME theme table up: rarer tiers raise counts and add a higher-value topper, so a mythic visibly
// out-rewards a common from one floor. Zeni scales the same way.
public final class DungeonCrateDefaults {

    private DungeonCrateDefaults() {
    }

    // TESTING DEFAULT - REMOVE BEFORE RELEASE.
    // While this is true, EVERY tier's default pool is a single guaranteed nether star at 100%, so any crate
    // opened obviously proves the whole pipeline works (marker -> tier roll -> reward reveal). The real per-theme,
    // per-tier tables below are untouched: flip this ONE line to false to drop the star and restore them.
    public static final boolean TESTING_NETHER_STAR = true;

    // default drops for a theme AND tier. each chance is a pick weight (DungeonCrateLoot.rollReward), so a higher one
    // is the likely reward and the topper the occasional jackpot. Fresh list every call, so no caller mutates a template.
    public static List<CustomDrop> dropsFor(String theme, CrateTier tier) {
        if (TESTING_NETHER_STAR) {
            // guaranteed testing reward: a lone entry is always the chosen pick, so every tier drops a nether star.
            List<CustomDrop> testing = new ArrayList<>();
            testing.add(new CustomDrop("minecraft:nether_star", 1, 1, 100.0f));
            return testing;
        }
        List<CustomDrop> base = baseDropsFor(theme);
        int t = tier.ordinal();               // 0..3
        int countBonus = t;                    // rarer tiers stack more of each item
        List<CustomDrop> out = new ArrayList<>();
        for (CustomDrop d : base) {
            out.add(new CustomDrop(d.itemId, d.minCount + countBonus, d.maxCount + countBonus, d.chance));
        }
        // tier topper: a high-value headline item for the rarer pools. common has none; each rarer tier adds a stronger one.
        String topper = switch (tier) {
            case COMMON -> null;
            case UNCOMMON -> "minecraft:diamond";
            case RARE -> "minecraft:netherite_scrap";
            case MYTHIC -> "minecraft:netherite_ingot";
        };
        if (topper != null) {
            int min = 1;
            int max = 1 + t;
            float chance = switch (tier) {
                case UNCOMMON -> 35.0f;
                case RARE -> 25.0f;
                case MYTHIC -> 18.0f;
                default -> 0.0f;
            };
            out.add(new CustomDrop(topper, min, max, chance));
        }
        // mythic also carries a genuine trophy so the orange latch means something on any theme.
        if (tier == CrateTier.MYTHIC) {
            out.add(new CustomDrop("minecraft:enchanted_golden_apple", 1, 1, 6.0f));
        }
        return out;
    }

    // default zeni range {min, max} for a theme and tier, paid once per refresh window on a player's first open. tiers
    // multiply the theme base: common 1x, uncommon 2x, rare 4x, mythic 8x.
    public static int[] zeniFor(String theme, CrateTier tier) {
        int[] base = baseZeniFor(theme);
        int mult = 1 << tier.ordinal();       // 1, 2, 4, 8
        return new int[]{ base[0] * mult, base[1] * mult };
    }

    private static List<CustomDrop> baseDropsFor(String theme) {
        String t = theme == null ? "OVERWORLD" : theme.toUpperCase();
        List<CustomDrop> out = new ArrayList<>();
        switch (t) {
            // STONY is off the theme picker but kept resolving so existing stony floors keep their starter loot.
            case "STONY" -> {
                out.add(new CustomDrop("minecraft:coal", 3, 6, 60.0f));
                out.add(new CustomDrop("minecraft:iron_ingot", 2, 4, 50.0f));
                out.add(new CustomDrop("minecraft:gold_ingot", 1, 3, 30.0f));
                out.add(new CustomDrop("minecraft:diamond", 1, 1, 8.0f));
            }
            case "NAMEK" -> {
                out.add(new CustomDrop("minecraft:emerald", 2, 5, 55.0f));
                out.add(new CustomDrop("minecraft:gold_ingot", 2, 4, 40.0f));
                out.add(new CustomDrop("minecraft:lapis_lazuli", 3, 6, 30.0f));
                out.add(new CustomDrop("minecraft:diamond", 1, 2, 18.0f));
            }
            case "NETHER" -> {
                out.add(new CustomDrop("minecraft:gold_ingot", 3, 6, 55.0f));
                out.add(new CustomDrop("minecraft:quartz", 4, 8, 50.0f));
                out.add(new CustomDrop("minecraft:diamond", 1, 2, 22.0f));
                out.add(new CustomDrop("minecraft:netherite_scrap", 1, 1, 5.0f));
            }
            case "END" -> {
                out.add(new CustomDrop("minecraft:ender_pearl", 1, 3, 45.0f));
                out.add(new CustomDrop("minecraft:emerald", 3, 6, 45.0f));
                out.add(new CustomDrop("minecraft:diamond", 2, 4, 30.0f));
                out.add(new CustomDrop("minecraft:netherite_scrap", 1, 1, 8.0f));
            }
            case "KAIO", "KAI" -> {
                out.add(new CustomDrop("minecraft:emerald", 4, 8, 45.0f));
                out.add(new CustomDrop("minecraft:diamond", 2, 5, 40.0f));
                out.add(new CustomDrop("minecraft:gold_block", 1, 2, 20.0f));
                out.add(new CustomDrop("minecraft:netherite_ingot", 1, 1, 6.0f));
            }
            case "OTHERWORLD" -> {
                out.add(new CustomDrop("minecraft:emerald", 5, 10, 45.0f));
                out.add(new CustomDrop("minecraft:diamond", 3, 6, 45.0f));
                out.add(new CustomDrop("minecraft:netherite_scrap", 1, 2, 15.0f));
                out.add(new CustomDrop("minecraft:netherite_ingot", 1, 1, 8.0f));
                out.add(new CustomDrop("minecraft:enchanted_golden_apple", 1, 1, 4.0f));
            }
            case "OVERWORLD" -> {
                out.add(new CustomDrop("minecraft:bread", 2, 5, 60.0f));
                out.add(new CustomDrop("minecraft:iron_ingot", 1, 3, 45.0f));
                out.add(new CustomDrop("minecraft:gold_ingot", 1, 2, 25.0f));
                out.add(new CustomDrop("minecraft:emerald", 1, 2, 15.0f));
            }
            default -> {
                out.add(new CustomDrop("minecraft:bread", 2, 5, 60.0f));
                out.add(new CustomDrop("minecraft:iron_ingot", 1, 3, 45.0f));
                out.add(new CustomDrop("minecraft:gold_ingot", 1, 2, 25.0f));
                out.add(new CustomDrop("minecraft:emerald", 1, 2, 15.0f));
            }
        }
        return out;
    }

    private static int[] baseZeniFor(String theme) {
        String t = theme == null ? "OVERWORLD" : theme.toUpperCase();
        return switch (t) {
            case "STONY" -> new int[]{100, 300};
            case "NAMEK" -> new int[]{200, 500};
            case "NETHER" -> new int[]{300, 700};
            case "END" -> new int[]{500, 1200};
            case "KAIO", "KAI" -> new int[]{800, 1800};
            case "OTHERWORLD" -> new int[]{1000, 2500};
            case "OVERWORLD" -> new int[]{50, 200};
            default -> new int[]{50, 200};
        };
    }
}
