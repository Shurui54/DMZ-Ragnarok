package net.shurui.dev.sdu.passive;

import com.dragonminez.common.passives.ClassPassives;
import com.dragonminez.common.passives.IClassPassive;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;

import net.minecraft.world.entity.LivingEntity;

/**
 * The passive for ONE custom class, driven entirely by numbers an admin typed.
 *
 * <h2>Why this is a single generic handler</h2>
 * DragonMineZ already has a complete passive framework: {@link IClassPassive} declares every hook, its own seven
 * classes are {@link ClassPassives#register registered} implementations of it, and
 * {@link ClassPassives#value(StatsData, String, double)} reads a named number out of the class's configured
 * {@code passive.values} map. Its handlers are bespoke code because each of its passives is a bespoke mechanic
 * (Fury stacks, damage redirection), but every one of them ultimately answers the same small set of questions
 * through those hooks.
 *
 * <p>So a custom class does not need bespoke code, it needs those questions answered from config. This class is
 * registered once per custom class id and answers each hook from a fixed menu of value keys. That is what makes
 * the passive editable rather than programmed: an admin picks effects and magnitudes, and nothing here changes.
 *
 * <h2>The menu</h2>
 * Every entry maps one-to-one onto a hook DMZ ALREADY calls, which is what makes them real rather than decorative.
 * There is deliberately no entry that would need a hook DMZ does not have: an effect we cannot actually apply
 * would be a lie in the editor.
 *
 * <p>Values are FRACTIONS, so 0.25 means 25 percent, and 0 means "no effect" for every entry. That matters: an
 * unset key must leave DMZ's behaviour exactly as it was, so the neutral return is a multiplier of 1.0 or a bonus
 * of 0.0 depending on which the hook expects. Getting that backwards would make every class with a blank passive
 * silently worse than one with no passive at all.
 *
 * <h2>The low-health condition</h2>
 * {@code belowHpPct} and {@code lowHpScale} reproduce the shape DMZ's own berserker uses: below a health
 * fraction, everything this passive grants is multiplied. Left at 0 the passive is unconditional, which is the
 * common case.
 */
public final class SduClassPassive implements IClassPassive {

    /** Multiplier on melee/strike damage dealt. */
    public static final String STRIKE_DAMAGE = "sduStrikeDamage";
    /** Added crit chance. */
    public static final String CRIT_CHANCE = "sduCritChance";
    /** Added armour penetration. */
    public static final String ARMOR_PEN = "sduArmorPen";
    /** Multiplier on health regeneration. */
    public static final String HEALTH_REGEN = "sduHealthRegen";
    /** Multiplier on stamina regeneration. */
    public static final String STAMINA_REGEN = "sduStaminaRegen";
    /** Share of stamina regen granted as BONUS hp regen (not a conversion), the tank's shape. */
    public static final String HP_FROM_STAMINA = "sduHpFromStamina";
    /** Multiplier on healing received. */
    public static final String HEALING_RECEIVED = "sduHealingReceived";
    /** REDUCTION in ki attack cooldowns. 0.2 means cooldowns are 20 percent shorter. */
    public static final String KI_COOLDOWN = "sduKiCooldown";
    /** Multiplier on the duration of a ki attack's secondary effect (its buff or debuff). */
    public static final String SECONDARY_DURATION = "sduSecondaryDuration";

    /** Health fraction below which {@link #LOW_HP_SCALE} applies. 0 disables the condition entirely. */
    public static final String BELOW_HP_PCT = "sduBelowHpPct";
    /** What every effect is multiplied by while below that health. */
    public static final String LOW_HP_SCALE = "sduLowHpScale";

    /** Every menu key, in the order the editor shows them. */
    public static final String[] MENU = {
            STRIKE_DAMAGE, CRIT_CHANCE, ARMOR_PEN, HEALTH_REGEN, STAMINA_REGEN,
            HP_FROM_STAMINA, HEALING_RECEIVED, KI_COOLDOWN, SECONDARY_DURATION,
            BELOW_HP_PCT, LOW_HP_SCALE,
    };

    private final String classKey;

    public SduClassPassive(String classKey) {
        this.classKey = classKey;
    }

    @Override
    public String classKey() {
        return classKey;
    }

    /**
     * One configured effect, already scaled by the low-health condition.
     *
     * <p>Returns 0 when the passive is switched off, so the enabled toggle genuinely disables everything rather
     * than leaving the numbers quietly in force.
     */
    private double amount(StatsData stats, String key) {
        if (stats == null) {
            return 0.0;
        }
        var config = ClassPassives.configFor(stats);
        if (config != null && !config.isEnabled()) {
            return 0.0;
        }
        double base = ClassPassives.value(stats, key, 0.0);
        if (base == 0.0) {
            return 0.0;
        }
        double threshold = ClassPassives.value(stats, BELOW_HP_PCT, 0.0);
        if (threshold <= 0.0) {
            return base;
        }
        double scale = ClassPassives.value(stats, LOW_HP_SCALE, 1.0);
        return healthFraction(stats) <= threshold ? base * scale : base;
    }

    /**
     * Current health as a 0..1 fraction, read from the live entity rather than the stat block.
     *
     * <p>{@link StatsData} carries the configured maximum, not what the player is on right now, and a passive that
     * keys on "below 33 percent" has to mean current health or it fires constantly.
     */
    private static double healthFraction(StatsData stats) {
        LivingEntity owner = ownerOf(stats);
        if (owner == null) {
            return 1.0;
        }
        float max = owner.getMaxHealth();
        return max <= 0.0F ? 1.0 : owner.getHealth() / max;
    }

    /**
     * The entity a stat block belongs to.
     *
     * <p>Reflective on purpose. StatsData's accessor for its owner is not part of the surface this addon compiles
     * against, and a hard reference to a field DMZ may rename would turn a version bump into a crash inside a
     * damage hook. Failing to resolve it means the low-health condition simply never triggers, which leaves the
     * passive working unconditionally rather than breaking the fight.
     */
    private static LivingEntity ownerOf(StatsData stats) {
        for (String name : new String[] { "getPlayer", "getOwner", "getEntity", "getLivingEntity" }) {
            try {
                Object o = stats.getClass().getMethod(name).invoke(stats);
                if (o instanceof LivingEntity living) {
                    return living;
                }
            } catch (Throwable ignored) {
                // try the next one
            }
        }
        return null;
    }

    @Override
    public double strikeDamageMultiplier(StatsData stats, LivingEntity target) {
        return 1.0 + amount(stats, STRIKE_DAMAGE);
    }

    @Override
    public double critChanceBonus(StatsData stats) {
        return amount(stats, CRIT_CHANCE);
    }

    @Override
    public double armorPenBonus(StatsData stats) {
        return amount(stats, ARMOR_PEN);
    }

    @Override
    public double healthRegenMultiplier(StatsData stats) {
        return 1.0 + amount(stats, HEALTH_REGEN);
    }

    @Override
    public double staminaRegenMultiplier(StatsData stats) {
        return 1.0 + amount(stats, STAMINA_REGEN);
    }

    @Override
    public double bonusHpRegenFromStamina(StatsData stats) {
        return amount(stats, HP_FROM_STAMINA);
    }

    @Override
    public double healingReceivedMultiplier(StatsData stats) {
        return 1.0 + amount(stats, HEALING_RECEIVED);
    }

    @Override
    public double kiCooldownMultiplier(StatsData stats, KiAttackData attack) {
        // A REDUCTION, so the multiplier goes DOWN as the configured number goes up. Floored at a tenth of the
        // original cooldown so a mistyped 5 cannot produce a zero or negative cooldown.
        return Math.max(0.1, 1.0 - amount(stats, KI_COOLDOWN));
    }

    @Override
    public double secondaryDurationMultiplier(StatsData stats, KiAttackData attack) {
        return Math.max(0.0, 1.0 + amount(stats, SECONDARY_DURATION));
    }
}
