package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for the DMZ-specific shadow dragon boss stats (battle power, ki-blast damage, visual scale,
 * AI tier). Holds no DMZ imports: it only checks that DMZ is present before touching {@link ShadowDragonStats},
 * which is the sole class that references DMZ types. Follows the optional-dependency pattern.
 *
 * <p>The generic vanilla attributes (max health, attack damage, movement speed) and the suite-wide
 * {@code dmz_npc_defense} key are applied by the caller directly, since they need no DMZ classes; only the four
 * DMZ-saga-entity setters live behind this guard.
 */
public final class ShadowDragonStatsCompat
{
    private ShadowDragonStatsCompat() {}

    /**
     * Apply the DMZ-specific stats to {@code entity} when it is a DMZ saga entity. Each value is applied only when
     * non-zero, honouring the "0 keeps the entity default" convention. No-op (logged inside) when DMZ is absent,
     * the entity is not a saga entity, or the DMZ internals no longer match. Never throws into the spawn path.
     */
    public static void applyDmzStats(LivingEntity entity, int battlePower, double kiBlastDamage,
                                     double scale, int aiTier)
    {
        if (entity == null || !ModList.get().isLoaded("dragonminez"))
            return;
        ShadowDragonStats.applyDmzStats(entity, battlePower, kiBlastDamage, scale, aiTier);
    }
}
