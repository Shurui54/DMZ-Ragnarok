package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for draining a victim's DMZ ki and stamina. Holds no DMZ imports itself; the sole class naming
 * DMZ types is {@link DmzResourceDrainImpl} (the optional-dependency pattern).
 *
 * <p>A no-op without DMZ, and a no-op for anything with no DMZ character (a mob), so a drain effect used on an
 * ordinary entity simply does the rest of its job.
 */
public final class DmzResourceDrain
{
    private DmzResourceDrain() {}

    /**
     * Both bars as percentages, for a diagnostic line only. Never throws.
     *
     * <p>Exists because "ran dry" and "ended for some other reason" look identical from in game, and the difference
     * decides whether a sustained ability is tuned wrong or broken.
     */
    public static String bars(LivingEntity of)
    {
        if (of == null || !ModList.get().isLoaded("dragonminez"))
            return "no dmz";
        return DmzResourceDrainImpl.bars(of);
    }

    /**
     * Take {@code energy} ki and {@code stamina} stamina, but only if BOTH are there to take.
     *
     * <p>All or nothing on purpose: an ability that half-charges a player who could not afford it leaves them worse
     * off than not pressing the key, and one that fires anyway is free.
     *
     * @return true when the cost was paid. Always true without DMZ, so an ability is not unusable on a server that
     *         somehow lacks it.
     */
    public static boolean spend(LivingEntity payer, float energy, float stamina)
    {
        return payer == null || !ModList.get().isLoaded("dragonminez")
                || DmzResourceDrainImpl.spend(payer, energy, stamina);
    }

    /**
     * Take a FRACTION of each maximum, but only if both are there to take.
     *
     * <p>The form every sustained ability should charge in. A flat points cost cannot be tuned: DMZ's maxima are
     * derived from a stat, so the same number is a rounding error to one character and more than the whole bar to
     * another, and an ability priced that way is free at the top and impossible at the bottom. A fraction costs
     * everyone the same SHARE, which means a full bar always buys the same duration whoever is flying it.
     *
     * @param kiFraction      share of maximum ki, 0..1
     * @param staminaFraction share of maximum stamina, 0..1
     * @return true when the cost was paid. Always true without DMZ.
     */
    public static boolean spendFraction(LivingEntity payer, double kiFraction, double staminaFraction)
    {
        return payer == null || !ModList.get().isLoaded("dragonminez")
                || DmzResourceDrainImpl.spendFraction(payer, kiFraction, staminaFraction);
    }

    /** Hand a fractional spend back, for an ability that took its cost and then could not run. */
    public static void restoreFraction(LivingEntity payer, double kiFraction, double staminaFraction)
    {
        if (payer != null && ModList.get().isLoaded("dragonminez"))
            DmzResourceDrainImpl.restorePercent(payer, kiFraction * 100.0, staminaFraction * 100.0);
    }

    /** Give back a PERCENTAGE of each maximum, for a heal that thinks in fractions rather than points. */
    public static void restorePercent(LivingEntity target, double kiPercent, double staminaPercent)
    {
        if (target != null && ModList.get().isLoaded("dragonminez"))
            DmzResourceDrainImpl.restorePercent(target, kiPercent, staminaPercent);
    }

    /** Hand a spend back, for an ability that took its cost and then could not run. */
    public static void restore(LivingEntity payer, float energy, float stamina)
    {
        if (payer != null && ModList.get().isLoaded("dragonminez"))
            DmzResourceDrainImpl.restore(payer, energy, stamina);
    }

    /** Remove up to {@code energy} ki and {@code stamina} stamina from this entity's DMZ resources. */
    public static void drain(LivingEntity victim, float energy, float stamina)
    {
        if (victim == null || !ModList.get().isLoaded("dragonminez"))
            return;
        DmzResourceDrainImpl.drain(victim, energy, stamina);
    }
}
