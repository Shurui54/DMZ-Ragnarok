package net.shurui.dev.sdu.combat;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.CombatConfig;

// port of DMZ 2.1.3's player mitigation curve (StatsData#calculatePostMitigationDamage) reapplied to a MOB
// victim, so DMZ's resistance mitigation matches on curve-managed fighters (DMZ's real curve is player-only,
// its LOWEST onLivingHurt early-returns on non-players). mob adaptations:
//   baseDefense = our stored dmz_npc_defense, already on DMZ's getDefense() scale, fed in directly.
//   isGuardBroken -> false for mobs (skip stage 1 guard-break decay).
//   armorPenetration -> 0: DMZ's mob else-branch already folded it into the amount, don't re-apply (stage 2).
// all constants come from the same public DMZ config getters DMZ uses, so the mob curve tracks the player
// curve on a retune. stage 8 (Protection enchant) skipped, armor/player-only.
public final class NpcDefenseCurve {

    // DMZ default RaceStatsConfig defenseScaling (1.0). getStatScaling("DEF") isn't reachable per-mob (no
    // race/class); the default is faithful since our stored defense is on the getDefense() scale the k_factor
    // is derived against.
    private static final double DEF_STAT_SCALING = 1.0;

    private NpcDefenseCurve() {
    }

    // mob-adapted port of calculatePostMitigationDamage(incomingDamage, false, 0.0). incomingDamage is the DMZ
    // raw on the victim (for an ARMOR=0 curve mob == LivingDamageEvent#getAmount() at LOWEST). returns >= 0.
    public static double postMitigation(double incomingDamage, double baseDefense) {
        if (!(baseDefense > 0.0) || !(incomingDamage > 0.0)) {
            return Math.max(0.0, incomingDamage);
        }
        CombatConfig cc = ConfigManager.getCombatConfig();

        // Stage 1 (guard-break decay): isGuardBroken == false for mobs → skipped.
        // Stage 2 (armor penetration): armorPenetration == 0 for mobs (already folded in) → skipped.
        double rawFlatMitigation = baseDefense;

        // Stage 5, full-negation early-out: if flat mitigation dwarfs the hit, cancel it entirely.
        if (cc.getCancelDamageEventIfMitigationTooHigh()
                && rawFlatMitigation >= incomingDamage * cc.getCancelDamageMitigationThreshold()) {
            return 0.0;
        }

        // Stage 6, flat absorb, capped at a fraction of the incoming hit.
        double flatAbsorbCap = incomingDamage * cc.getFlatMitigationMaxAbsorbFraction();
        double flatMitigation = Math.min(rawFlatMitigation, flatAbsorbCap);
        double postFlatDamage = Math.max(0.0, incomingDamage - flatMitigation);

        // Stage 7, percentage curve. expectedMaxStats mirrors DMZ:
        //   maxLevelValueInsteadOfStats ? maxValue * 6 / 2 : maxValue.
        int maxValue = ConfigManager.getServerConfig().getGameplay().getMaxValue();
        boolean maxLevelValue = ConfigManager.getServerConfig().getGameplay().getMaxLevelValueInsteadOfStats();
        double expectedMaxStats = maxLevelValue ? (double) maxValue * 6.0 / 2.0 : (double) maxValue;
        double expectedMaxDef = expectedMaxStats * DEF_STAT_SCALING;
        double k_factor = Math.max(12.0, expectedMaxDef * cc.getDefenseReductionScale());
        // baseDefense > 0 here, so DMZ's positive branch: def / (k + def).
        double baseReduction = baseDefense / (k_factor + baseDefense);
        double baseCap = baseDamageReductionCap(cc);
        baseReduction = Math.min(baseReduction, baseCap);
        double remainingDamage = postFlatDamage * (1.0 - baseReduction);

        // Stage 8 (Protection enchantment): SKIPPED: player/armor-only, inert for mobs.
        double afterEnchant = remainingDamage;

        // Stage 9, adaptive defense mitigation. Reachable via public config only (parityRatio/parityValue/
        // zeroRatio/cap); the ratio is incomingDamage/rawFlatMitigation, no player state required, so it IS
        // ported. Mirrors DMZ's computeAdaptativeDefenseMitigation.
        if (cc.getEnableAdaptativeDefenseMitigation() && rawFlatMitigation > 0.0) {
            double ratio = incomingDamage / rawFlatMitigation;
            afterEnchant *= 1.0 - computeAdaptativeDefenseMitigation(cc, ratio);
        }

        return Math.max(0.0, afterEnchant);
    }

    // mirror of DMZ's computeAdaptativeDefenseMitigation, pure config + ratio.
    private static double computeAdaptativeDefenseMitigation(CombatConfig cfg, double ratio) {
        if (!Double.isFinite(ratio) || ratio <= 0.0) {
            return 0.0;
        }
        double parityRatio = cfg.getAdaptativeMitigationParityRatio();
        double parityValue = cfg.getAdaptativeMitigationParityValue();
        double zeroRatio = cfg.getAdaptativeMitigationZeroRatio();
        double cap = cfg.getAdaptativeDefenseMitigationCap();
        double slope = parityValue / (zeroRatio - parityRatio);
        double mitigation = parityValue + slope * (parityRatio - ratio);
        if (!Double.isFinite(mitigation) || mitigation <= 0.0) {
            return 0.0;
        }
        return Math.min(mitigation, cap);
    }

    // getBaseDamageReductionCap() returns a boxed Double that may be null; DMZ default 0.75.
    private static double baseDamageReductionCap(CombatConfig cc) {
        Double cap = cc.getBaseDamageReductionCap();
        return cap != null ? cap : 0.75; // DMZ default baseDamageReductionCap
    }
}
