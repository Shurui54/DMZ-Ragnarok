package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Resources;

import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.compat.DmzBridge;

/**
 * The only class naming DMZ types for the ki/stamina drain; {@link DmzResourceDrain} is the guard in front of it.
 *
 * <p>Uses DMZ's own {@code removeEnergy} / {@code removeStamina} rather than reading the current value and setting a
 * lower one. The read-modify-write version would race with DMZ's per-tick regen, and would also skip whatever
 * clamping and side effects those methods carry.
 */
final class DmzResourceDrainImpl
{
    private DmzResourceDrainImpl() {}

    /** @return true when both costs were affordable and have been taken. */
    static boolean spend(LivingEntity payer, float energy, float stamina)
    {
        try
        {
            StatsData stats = DmzBridge.stats(payer);
            if (stats == null)
                return true; // no DMZ character: nothing to charge, so nothing to refuse either
            Resources resources = stats.getResources();
            if (resources == null)
                return true;
            if (resources.getCurrentEnergy() < energy || resources.getCurrentStamina() < stamina)
                return false;
            if (energy > 0.0f)
                resources.removeEnergy(energy);
            if (stamina > 0.0f)
                resources.removeStamina(stamina);
            return true;
        }
        catch (Throwable t)
        {
            // A DMZ internals change must not make the ability free; refusing is the safe direction here, unlike
            // the drain below where the effect matters more than the cost.
            return false;
        }
    }

    /**
     * @return true when both fractional costs were affordable and have been taken.
     *
     * <p>The maxima come from {@link StatsData}, not from {@link Resources}: the pool holds only the current values
     * and clamps against the owner's computed maximum rather than storing one of its own.
     */
    static boolean spendFraction(LivingEntity payer, double kiFraction, double staminaFraction)
    {
        try
        {
            StatsData stats = DmzBridge.stats(payer);
            if (stats == null)
                return true; // no DMZ character: nothing to charge, so nothing to refuse either
            Resources resources = stats.getResources();
            if (resources == null)
                return true;
            float energy = (float) (stats.getMaxEnergy() * kiFraction);
            float stamina = (float) (stats.getMaxStamina() * staminaFraction);
            if (resources.getCurrentEnergy() < energy || resources.getCurrentStamina() < stamina)
                return false;
            if (energy > 0.0f)
                resources.removeEnergy(energy);
            if (stamina > 0.0f)
                resources.removeStamina(stamina);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Both bars as percentages of their maximum, for a diagnostic line. Never throws; returns "?" if it cannot read.
     */
    static String bars(LivingEntity of)
    {
        try
        {
            StatsData stats = DmzBridge.stats(of);
            if (stats == null)
                return "no dmz character";
            Resources resources = stats.getResources();
            if (resources == null)
                return "no resources";
            float maxKi = stats.getMaxEnergy();
            float maxSta = stats.getMaxStamina();
            return String.format("ki %.1f%% (%.0f/%.0f), stamina %.1f%% (%.0f/%.0f)",
                    maxKi <= 0 ? 0.0f : resources.getCurrentEnergy() * 100.0f / maxKi,
                    resources.getCurrentEnergy(), maxKi,
                    maxSta <= 0 ? 0.0f : resources.getCurrentStamina() * 100.0f / maxSta,
                    resources.getCurrentStamina(), maxSta);
        }
        catch (Throwable t)
        {
            return "?";
        }
    }

    static void restorePercent(LivingEntity target, double kiPercent, double staminaPercent)
    {
        try
        {
            StatsData stats = DmzBridge.stats(target);
            if (stats == null)
                return;
            Resources resources = stats.getResources();
            if (resources == null)
                return;
            // The maxima are StatsData's, not the pool's: Resources holds the current values and clamps against
            // the owner's computed maximum rather than storing one of its own.
            if (kiPercent > 0.0)
                resources.addEnergy((float) (stats.getMaxEnergy() * kiPercent / 100.0));
            if (staminaPercent > 0.0)
                resources.addStamina((float) (stats.getMaxStamina() * staminaPercent / 100.0));
        }
        catch (Throwable ignored)
        {
        }
    }

    static void restore(LivingEntity payer, float energy, float stamina)
    {
        try
        {
            StatsData stats = DmzBridge.stats(payer);
            if (stats == null)
                return;
            Resources resources = stats.getResources();
            if (resources == null)
                return;
            // DMZ's own add methods, so both stay clamped to the player's maximum rather than being pushed past it.
            if (energy > 0.0f)
                resources.addEnergy(energy);
            if (stamina > 0.0f)
                resources.addStamina(stamina);
        }
        catch (Throwable ignored)
        {
        }
    }

    static void drain(LivingEntity victim, float energy, float stamina)
    {
        try
        {
            StatsData stats = DmzBridge.stats(victim);
            if (stats == null)
                return; // no DMZ character (an ordinary mob): nothing to drain
            Resources resources = stats.getResources();
            if (resources == null)
                return;
            if (energy > 0.0f)
                resources.removeEnergy(energy);
            if (stamina > 0.0f)
                resources.removeStamina(stamina);
        }
        catch (Throwable ignored)
        {
            // A DMZ internals change must not take the whole ability down; the drain simply does not happen.
        }
    }
}
