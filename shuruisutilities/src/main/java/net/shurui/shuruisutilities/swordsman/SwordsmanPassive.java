package net.shurui.shuruisutilities.swordsman;

import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.common.passives.IClassPassive;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import net.shurui.dev.sdu.passive.SduClassPassive;

/**
 * DMZ class passive for {@link Swordsman#CLASS_ID}.
 *
 * <p>Damage goes through {@link #outgoingDamageMultiplier}, which DMZ applies to its OWN melee damage (the STR part,
 * in {@code CombatEvent} via {@code DamageModifyEvent}) before the held weapon's attack damage is added. The weapon's
 * share and the attack speed are vanilla attributes, so {@link SwordsmanEvents} handles those.
 *
 * <p>Every other hook forwards to SDU's generic custom-class handler, so the generic effects an admin can add to any
 * class in the race editor still work for this one; it replaced that handler for this id and must not lose them.
 */
final class SwordsmanPassive implements IClassPassive
{
    private final SduClassPassive generic = new SduClassPassive(Swordsman.CLASS_ID);

    @Override
    public String classKey()
    {
        return Swordsman.CLASS_ID;
    }

    @Override
    public double outgoingDamageMultiplier(ServerPlayer attacker, StatsData data, LivingEntity target,
            DMZEvent.DamageSourceType type)
    {
        double mult = generic.outgoingDamageMultiplier(attacker, data, target, type);
        if (type == DMZEvent.DamageSourceType.MELEE && Swordsman.wieldingSword(attacker))
            mult *= 1.0 + Swordsman.bonus(data, Swordsman.DAMAGE_KEY);
        return mult;
    }

    // ---- forwarded generic hooks

    @Override
    public void onPlayerTick(ServerPlayer player, StatsData data)
    {
        generic.onPlayerTick(player, data);
    }

    @Override
    public void onPlayerSecond(ServerPlayer player, StatsData data)
    {
        generic.onPlayerSecond(player, data);
    }

    @Override
    public void onMeleeHit(ServerPlayer attacker, StatsData data, LivingEntity target, boolean blocked)
    {
        generic.onMeleeHit(attacker, data, target, blocked);
    }

    @Override
    public double staminaRegenMultiplier(StatsData data)
    {
        return generic.staminaRegenMultiplier(data);
    }

    @Override
    public double healthRegenMultiplier(StatsData data)
    {
        return generic.healthRegenMultiplier(data);
    }

    @Override
    public double bonusHpRegenFromStamina(StatsData data)
    {
        return generic.bonusHpRegenFromStamina(data);
    }

    @Override
    public double armorPenBonus(StatsData data)
    {
        return generic.armorPenBonus(data);
    }

    @Override
    public double critChanceBonus(StatsData data)
    {
        return generic.critChanceBonus(data);
    }

    @Override
    public double strikeDamageMultiplier(StatsData data, LivingEntity target)
    {
        return generic.strikeDamageMultiplier(data, target);
    }

    @Override
    public double healingReceivedMultiplier(StatsData data)
    {
        return generic.healingReceivedMultiplier(data);
    }

    @Override
    public double kiCooldownMultiplier(StatsData data, KiAttackData ki)
    {
        return generic.kiCooldownMultiplier(data, ki);
    }

    @Override
    public double secondaryDurationMultiplier(StatsData data, KiAttackData ki)
    {
        return generic.secondaryDurationMultiplier(data, ki);
    }

    @Override
    public double kiDamageMultiplier(StatsData data, KiAttackData ki)
    {
        return generic.kiDamageMultiplier(data, ki);
    }

    @Override
    public void onKiAttackFired(ServerPlayer player, StatsData data, KiAttackData ki)
    {
        generic.onKiAttackFired(player, data, ki);
    }

    @Override
    public void onStrikeCast(ServerPlayer player, StatsData data)
    {
        generic.onStrikeCast(player, data);
    }
}
