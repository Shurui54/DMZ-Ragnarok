package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * DMZ-facing half of the shadow dragon boss stats, reached only through {@link ShadowDragonStatsCompat}'s guard.
 * Applies the four DMZ-saga-entity stat setters that have no vanilla equivalent, mirroring the raid-bosses addon's
 * {@code RaidInstance.applyDmzStats}. Setter names verified against dragonminez-2.1.3.jar with javap:
 * {@code setBattlePower(int)}, {@code setKiBlastDamage(float)}, {@code setScaleVal(float)}, {@code setAiTierById(int)}.
 *
 * <p>Every DMZ access is wrapped so a type mismatch or an internals change degrades to a logged no-op instead of
 * crashing the encounter spawn.
 */
final class ShadowDragonStats
{
    private ShadowDragonStats() {}

    static void applyDmzStats(LivingEntity entity, int battlePower, double kiBlastDamage, double scale, int aiTier)
    {
        // only DMZ saga entities carry these stats; a plain warden (or any other placeholder) keeps its defaults.
        if (!(entity instanceof DBSagasEntity boss))
        {
            LoggingHandler.sulog.debug("[wishtracking] shadow dragon entity {} is not a DMZ saga entity, "
                    + "skipping DMZ-specific stats", entity.getType().getDescriptionId());
            return;
        }
        try
        {
            if (kiBlastDamage > 0)
                boss.setKiBlastDamage((float) kiBlastDamage);
            if (battlePower > 0)
                boss.setBattlePower(battlePower);
            if (scale > 0)
                boss.setScaleVal((float) scale);
            // aiTier is DMZ's 1-based id (1=SIMPLE 2=TACTICAL 3=ADVANCED); 0/out-of-range keeps the entity default.
            if (aiTier >= 1 && aiTier <= 3)
                boss.setAiTierById(aiTier);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[wishtracking] could not apply DMZ shadow dragon stats: {}", t.toString());
        }
    }
}
