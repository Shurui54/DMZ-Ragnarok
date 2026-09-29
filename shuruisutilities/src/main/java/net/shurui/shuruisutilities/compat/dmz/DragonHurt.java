package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Applies a shadow dragon move's damage to one victim.
 *
 * <p>Goes through the victim's ordinary {@code hurt} with the caster as the attacker, deliberately, so DMZ's own
 * two-phase damage pipeline sees it: DMZ reads raw damage at {@code LivingHurtEvent} and applies the victim's
 * resistance at {@code LivingDamageEvent}. Setting health directly, or using a generic source with no attacker,
 * would bypass that and make these moves ignore defence entirely, which no other attack in the game does.
 *
 * <p>Attribution matters for more than defence: with the caster as the source, kills credit the dragon, PvP rules
 * and protection regions apply, and anything else keyed on "who hit whom" behaves normally.
 */
public final class DragonHurt
{
    private DragonHurt() {}

    /**
     * Deal {@code amount} to {@code victim}, attributed to {@code caster}. No-op for a non-positive amount.
     *
     * <p>A player caster uses the player-attack source so DMZ, PvP rules and kill credit all see a normal player
     * hit. A boss caster uses the mob-attack source for the same reason on the other side: it must read as the boss
     * hitting you, so death messages, aggro and any protection check behave as they do for its melee.
     */
    public static void hurt(LivingEntity caster, LivingEntity victim, float amount)
    {
        if (caster == null || victim == null || amount <= 0.0f || !victim.isAlive())
            return;
        var sources = victim.damageSources();
        victim.hurt(caster instanceof ServerPlayer player
                ? sources.playerAttack(player)
                : sources.mobAttack(caster), amount);
    }
}
