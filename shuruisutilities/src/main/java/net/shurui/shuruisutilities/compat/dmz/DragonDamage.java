package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.fml.ModList;

/**
 * The one damage figure every shadow dragon and role move in this batch deals.
 *
 * <h2>Players: (melee + strike + ki) / 2</h2>
 * Read live off the caster's DMZ stats. Combining all three means a move scales with a character's whole build
 * rather than with one stat, so a ki-heavy and a melee-heavy shadow dragon both get something out of it; the halving
 * is what stops the sum of three stats reading as three times a normal hit.
 *
 * <h2>Bosses: the same shape, from the stats a boss actually has</h2>
 * A boss is an arbitrary configured entity, not a DMZ character, so it has no melee/strike/ki stat trio to read. Its
 * analogue is {@code (meleeDamage + kiBlastDamage) / 2} from its slot definition, falling back to its attack-damage
 * attribute when the admin has left those at 0 ("keep the entity default"). This is a DELIBERATE deviation, not an
 * oversight: applying the player formula to a boss is impossible, and driving boss damage off its own configured
 * numbers is what lets an admin tune a fight from the boss editor as they already do for its melee.
 *
 * <h2>Ticking moves</h2>
 * The figure returned here is the TOTAL a move deals over its whole duration, NOT a per-tick amount. A move that
 * ticks divides it by its tick count ({@link #perTick}). Getting this backwards would multiply a damage-over-time
 * move by its tick count, so every ticking move must go through {@link #perTick}.
 */
public final class DragonDamage
{
    private DragonDamage() {}

    /**
     * Total damage for one cast.
     *
     * @return 0 when the figure cannot be resolved, which callers must treat as "deal no damage".
     */
    public static float total(LivingEntity caster)
    {
        if (caster == null)
            return 0.0f;
        if (caster instanceof ServerPlayer player)
        {
            if (!ModList.get().isLoaded("dragonminez"))
                return 0.0f;
            return DragonDamageStats.total(player);
        }
        return bossTotal(caster);
    }

    /**
     * A boss's figure: its configured melee and ki blast damage halved, or its attack-damage attribute when the slot
     * leaves those at DMZ's "keep the entity default" zero.
     */
    private static float bossTotal(LivingEntity boss)
    {
        double melee = 0.0;
        double kiBlast = 0.0;
        net.shurui.shuruisutilities.corrupted.ShadowDragonDef def = DragonBossLookup.defFor(boss);
        if (def != null)
        {
            melee = def.meleeDamage;
            kiBlast = def.kiBlastDamage;
        }
        if (melee > 0.0 || kiBlast > 0.0)
            return (float) ((melee + kiBlast) / 2.0);

        var attribute = boss.getAttribute(Attributes.ATTACK_DAMAGE);
        return attribute == null ? 0.0f : (float) attribute.getValue();
    }

    /**
     * Per-tick damage for a move that ticks {@code tickCount} times, so the whole effect adds up to {@link #total}.
     *
     * @param tickCount how many damage ticks the effect will apply; values below 1 are treated as a single tick.
     */
    public static float perTick(LivingEntity caster, int tickCount)
    {
        float whole = total(caster);
        return tickCount <= 1 ? whole : whole / tickCount;
    }
}
