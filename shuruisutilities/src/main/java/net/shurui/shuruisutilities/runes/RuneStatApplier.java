package net.shurui.shuruisutilities.runes;

import java.util.EnumMap;
import java.util.Map;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.BonusStats;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Turns the arrows on worn armour into DMZ stat bonuses.
 *
 * <p>All four pieces are summed into ONE bonus per stat under a single source. A per-piece source would look
 * tidier but is wrong here: DMZ's bonuses are multiplicative, so four separate 1.05x entries would compound to
 * 1.2155x rather than the 1.20x the tooltip promises, and the arrows would quietly under-report the armour. Summing
 * the arrows first and applying one multiplier keeps the number on the tooltip and the number in the stats identical.
 *
 * <p>Recomputed wholesale on every equipment change rather than diffed, because a piece can change tier, be
 * socketed, or be swapped for another in the same tick, and a full recompute cannot drift out of step with what is
 * actually worn.
 */
public final class RuneStatApplier
{
    private RuneStatApplier() {}

    /** Single source for the whole armour set, so re-adding overwrites cleanly and shrines are never disturbed. */
    private static final String SOURCE = "su_armor_runes";

    private static final String OP_MUL = "*";

    /**
     * Put the rune bonus in DMZ's MULTIPLIED bucket, so an arrow is a real percentage of the stat rather than a
     * flat number bolted on at the end.
     *
     * <p>This was the bug. DMZ's four-argument {@code addBonus} passes {@code applyMultipliers = false}, which is
     * what this used to call, so runes landed in the FLAT bucket. Both the defence maths and the stats screen
     * compose the two buckets as {@code (base + multBonus) * totalMultiplier + flatBonus}: a flat bonus is worked
     * out against the BASE stat and then added AFTER transformations, so a +50% rune set contributed half a base
     * stat whether you were in base form or in a form multiplying everything by five. In the multiplied bucket the
     * arrows ride the multiplier with the rest of the stat, which is what a percentage is supposed to mean, and
     * they carry past the cap because nothing clamps this path.</p>
     */
    private static final boolean APPLY_MULTIPLIERS = true;

    /** Recompute and apply this player's armour rune bonuses. Safe to call often. */
    public static void refresh(ServerPlayer player)
    {
        if (player == null)
            return;
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return;
        try
        {
            Map<RuneStat, Integer> totals = new EnumMap<>(RuneStat.class);
            for (RuneStat s : RuneStat.values())
                totals.put(s, 0);

            for (EquipmentSlot slot : EquipmentSlot.values())
            {
                if (slot.getType() != EquipmentSlot.Type.ARMOR)
                    continue;
                ItemStack worn = player.getItemBySlot(slot);
                if (!ArmorRunes.has(worn))
                    continue;
                for (RuneStat s : RuneStat.values())
                    totals.put(s, totals.get(s) + ArmorRunes.arrows(worn, s));
            }

            BonusStats bonus = stats.getBonusStats();
            for (RuneStat s : RuneStat.values())
            {
                String key = DmzBridge.bonusKey(s.name());
                double multiplier = 1.0 + totals.get(s) * ArmorRunes.ARROW_STEP;
                // A stat at exactly 1.0 carries no bonus, and a negative total is a real debuff rather than a
                // reason to skip: only remove when there is nothing to say.
                if (totals.get(s) == 0)
                    bonus.removeBonus(key, SOURCE);
                else
                    bonus.addBonus(key, SOURCE, OP_MUL, Math.max(0.05, multiplier), APPLY_MULTIPLIERS);
            }
            DmzBridge.resyncStats(player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ArmorRunes] Could not apply rune bonuses for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    /** Drop every rune bonus this player has, e.g. when the feature is switched off. */
    public static void clear(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return;
        try
        {
            stats.getBonusStats().removeAllBonuses(SOURCE);
            DmzBridge.resyncStats(player);
        }
        catch (Throwable ignored)
        {
        }
    }
}
