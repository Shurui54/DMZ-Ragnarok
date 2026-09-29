package net.shurui.shuruisutilities.guilds.integration;

import java.util.Optional;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

/**
 * Soft bridge to DragonMineZ. A guild's battle power (and therefore its claim limit) is aggregated from
 * its members' DMZ battle power. Everything is guarded by {@link #available()} and a broad catch so
 * Shurui's Utilities still loads and runs on servers without DragonMineZ installed.
 */
public final class DmzBridge
{
    private DmzBridge() {}

    private static final boolean LOADED = ModList.get().isLoaded("dragonminez");

    /** @return true if DragonMineZ is installed on this server. */
    public static boolean available()
    {
        return LOADED;
    }

    /**
     * Reads a player's DragonMineZ battle power. Returns 0 if DMZ is absent, the player has no character
     * yet, or anything goes wrong resolving the capability.
     */
    public static double battlePower(Player player)
    {
        if (!LOADED || player == null)
            return 0.0;
        try
        {
            Optional<StatsData> stats = player.getCapability(StatsCapability.INSTANCE).resolve();
            if (stats.isEmpty())
                return 0.0;
            StatsData sd = stats.get();
            // DMZ returns Float.MAX_VALUE for Gero-upgraded androids, which overflows guild power
            // totals and claim limits. Compute a real stat-based number for those players instead.
            // Everyone else (including the bioandroid race, which already computes a normal BP) keeps
            // DMZ's own value.
            if (sd.getStatus().isAndroidUpgraded())
                return sdu$computeBattlePower(sd);
            return sd.getBattlePowerExact();
        }
        catch (Throwable t)
        {
            return 0.0;
        }
    }

    /**
     * A player's BASE DragonMineZ battle power: the same stat-derived number as {@link #battlePower} but with
     * every transformation and stack-form multiplier (and the power-release toggle) forced to their untransformed
     * baseline, so a form never changes it. Sparring compares two fighters by this value so a payout tracks their
     * trained strength rather than whichever form they happen to be holding: transforming or detransforming, by
     * either fighter, at any moment, never moves the ratio, which closes the timing exploit while still keeping the
     * anti-farm floor intact (a genuinely stronger player still outguns a weak one on trained stats alone). Returns
     * 0 when DMZ is absent, the player has no character, or anything goes wrong.
     */
    public static double baseBattlePower(Player player)
    {
        if (!LOADED || player == null)
            return 0.0;
        try
        {
            Optional<StatsData> stats = player.getCapability(StatsCapability.INSTANCE).resolve();
            if (stats.isEmpty())
                return 0.0;
            return sdu$computeBattlePower(stats.get(), false);
        }
        catch (Throwable t)
        {
            return 0.0;
        }
    }

    /**
     * Reproduces DragonMineZ's own battle-power math (StatsData#getBattlePowerExact, DMZ 2.1.3) verbatim,
     * minus the androidUpgraded early-return. An upgraded android gets the exact number a non-android with
     * identical stats would get, so guild power totals and claim limits stay sane. Guild use only; DMZ's
     * display value is untouched.
     */
    private static double sdu$computeBattlePower(StatsData sd)
    {
        return sdu$computeBattlePower(sd, true);
    }

    /**
     * As {@link #sdu$computeBattlePower(StatsData)} but, when {@code withTransformMultipliers} is false, every
     * per-stat total multiplier and the power-release factor are pinned to their untransformed baseline (1.0 and
     * 100%). Because getFormMultiplier already returns 1.0 for the base form, the {@code false} path yields exactly
     * the number the player would have with no form active, and the {@code true} path is byte-for-byte the original.
     * Both fighters in a spar go through the same {@code false} path, so the ratio between them is self-consistent
     * regardless of any later drift between this reconstruction and DMZ's own display value.
     */
    private static double sdu$computeBattlePower(StatsData sd, boolean withTransformMultipliers)
    {
        double str = sd.getStats().getStrength();
        double skp = sd.getStats().getStrikePower();
        double res = sd.getStats().getResistance();
        double vit = sd.getStats().getVitality();
        double pwr = sd.getStats().getKiPower();
        double ene = sd.getStats().getEnergy();

        double multBonusStr = sd.getBonusStats().calculateBonus("STR", (int) Math.round(str), true);
        double flatBonusStr = sd.getBonusStats().calculateBonus("STR", (int) Math.round(str), false);
        double multBonusSkp = sd.getBonusStats().calculateBonus("SKP", (int) Math.round(skp), true);
        double flatBonusSkp = sd.getBonusStats().calculateBonus("SKP", (int) Math.round(skp), false);
        double multBonusDef = sd.getBonusStats().calculateBonus("DEF", (int) Math.round(res), true);
        double flatBonusDef = sd.getBonusStats().calculateBonus("DEF", (int) Math.round(res), false);
        double multBonusVit = sd.getBonusStats().calculateBonus("VIT", (int) Math.round(vit), true);
        double flatBonusVit = sd.getBonusStats().calculateBonus("VIT", (int) Math.round(vit), false);
        double multBonusPwr = sd.getBonusStats().calculateBonus("PWR", (int) Math.round(pwr), true);
        double flatBonusPwr = sd.getBonusStats().calculateBonus("PWR", (int) Math.round(pwr), false);
        double multBonusEne = sd.getBonusStats().calculateBonus("ENE", (int) Math.round(ene), true);
        double flatBonusEne = sd.getBonusStats().calculateBonus("ENE", (int) Math.round(ene), false);

        // Transformation contribution: DMZ's getTotalMultiplier folds in form, stack-form and effect multipliers.
        // Pinning each to 1.0 collapses the raw power to its untransformed baseline for the base-power path.
        double mulStr = withTransformMultipliers ? sd.getTotalMultiplier("STR") : 1.0;
        double mulSkp = withTransformMultipliers ? sd.getTotalMultiplier("SKP") : 1.0;
        double mulRes = withTransformMultipliers ? sd.getTotalMultiplier("RES") : 1.0;
        double mulPwr = withTransformMultipliers ? sd.getTotalMultiplier("PWR") : 1.0;
        double mulVit = withTransformMultipliers ? sd.getTotalMultiplier("VIT") : 1.0;
        double mulEne = withTransformMultipliers ? sd.getTotalMultiplier("ENE") : 1.0;

        // Note: the DEF term scales by getStatScaling("DEF") but multiplies by getTotalMultiplier("RES") and
        // uses the RES stat value. That mismatch is DMZ's, kept intentionally so the number matches.
        double rawPower = (str + multBonusStr) * sd.getStatScaling("STR") * mulStr + flatBonusStr * sd.getStatScaling("STR")
                + (skp + multBonusSkp) * sd.getStatScaling("SKP") * mulSkp + flatBonusSkp * sd.getStatScaling("SKP")
                + (res + multBonusDef) * sd.getStatScaling("DEF") * mulRes + flatBonusDef * sd.getStatScaling("DEF")
                + (pwr + multBonusPwr) * sd.getStatScaling("PWR") * mulPwr + flatBonusPwr * sd.getStatScaling("PWR");

        rawPower += 0.5 * ((vit + multBonusVit) * sd.getStatScaling("VIT") * mulVit + flatBonusVit * sd.getStatScaling("VIT")
                + (ene + multBonusEne) * sd.getStatScaling("ENE") * mulEne + flatBonusEne * sd.getStatScaling("ENE"));

        if (Double.isNaN(rawPower) || rawPower <= 0.0)
            return 0.0;

        double releaseMultiplier = withTransformMultipliers ? (double) sd.getResources().getPowerRelease() / 100.0 : 1.0;
        double bp = 1200.0 * Math.pow(rawPower / 100.0, 1.2) * releaseMultiplier;
        if (Double.isNaN(bp) || bp <= 0.0)
            return 0.0;
        return bp;
    }

    /**
     * Reads a player's DragonMineZ character level. Returns 0 if DMZ is absent, the player has no character
     * yet, or anything goes wrong resolving the capability.
     */
    public static int level(Player player)
    {
        if (!LOADED || player == null)
            return 0;
        try
        {
            Optional<StatsData> stats = player.getCapability(StatsCapability.INSTANCE).resolve();
            return stats.map(StatsData::getLevel).orElse(0);
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /**
     * Grant {@code amount} DragonMineZ Training Points to a player (used by NPC-region kill rewards). Returns
     * true on success; a guarded no-op when DMZ is absent, the player has no character, or the API shifts.
     */
    public static boolean awardTp(Player player, float amount)
    {
        if (!LOADED || player == null || amount <= 0)
            return false;
        try
        {
            Optional<StatsData> stats = player.getCapability(StatsCapability.INSTANCE).resolve();
            if (stats.isEmpty())
                return false;
            stats.get().getResources().addTrainingPoints(amount);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Grant {@code amount} DragonMineZ Training Points, choosing whether the grant is SHARED. DMZ's
     * {@code addTrainingPoints(float, boolean)} fires its cancelable TPGainEvent when {@code share} is true, which
     * is what lets every multiplier apply (the DMZ STORY multiplier, SU's TpBoostState, prestige, TokenBuff, and
     * the kill-TP anti-farm scaler): the configured amount is the number BEFORE those. The Z orb rewards pass
     * {@code share = true} so an orb's TP behaves exactly like earned TP. Returns true on success; a guarded no-op
     * when DMZ is absent, the player has no character, or the API shifts.
     */
    public static boolean awardTp(Player player, float amount, boolean share)
    {
        if (!LOADED || player == null || amount <= 0)
            return false;
        try
        {
            Optional<StatsData> stats = player.getCapability(StatsCapability.INSTANCE).resolve();
            if (stats.isEmpty())
                return false;
            stats.get().getResources().addTrainingPoints(amount, share);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
