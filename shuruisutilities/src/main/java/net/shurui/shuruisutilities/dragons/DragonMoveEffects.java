package net.shurui.shuruisutilities.dragons;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import net.shurui.shuruisutilities.compat.dmz.DragonBossLookup;
import net.shurui.shuruisutilities.compat.dmz.ShadowDragonFormCompat;
import net.shurui.shuruisutilities.energy.EnergyKind;
import net.shurui.shuruisutilities.energy.EnergyManager;

/**
 * Runs a shadow dragon move once a cast has been triggered, and owns the rules every move shares: who pays, who can
 * be hit, and who is immune.
 *
 * <p>A caster is either a PLAYER on a shadow dragon race, who pays malice, or one of the seven BOSSES, which pays a
 * cooldown instead ({@code DragonBossController}). Everything below therefore takes a {@link LivingEntity} rather
 * than a player: the moves themselves are identical whoever casts them, and keeping one implementation is what stops
 * the boss version of a move drifting away from the player version.
 *
 * <p>No DMZ imports: the DMZ-specific parts are reached through the guarded bridges in {@code compat.dmz}.
 */
public final class DragonMoveEffects
{
    private DragonMoveEffects() {}

    /**
     * Charge a PLAYER caster for one move. Bosses do not go through this: they are gated by a cooldown, not a bar.
     *
     * @return false when the bar is short, which the caller MUST treat as "the move did not happen".
     */
    public static boolean payFor(ServerPlayer caster)
    {
        return EnergyManager.spendStandard(caster, EnergyKind.MALICE);
    }

    /** Hand back one move's cost. Only meaningful for a player caster; a no-op for a boss. */
    public static void refund(LivingEntity caster)
    {
        if (caster instanceof ServerPlayer player)
            EnergyManager.refund(player, EnergyKind.MALICE, EnergyManager.COST_STANDARD);
    }

    /**
     * Run the move.
     *
     * @return true when this move WANTS DMZ to spawn its projectile (only Omega's ball), false when DMZ's
     *         projectile should be suppressed because the move draws its own visuals.
     */
    public static boolean cast(LivingEntity caster, DragonMove move)
    {
        if (caster == null || move == null || !(caster.level() instanceof ServerLevel level))
            return false;

        return switch (move)
        {
            case ABSOLUTE_ZERO -> { DragonMoveEis.cast(caster, level); yield false; }
            case DRAGON_THUNDER -> { DragonMoveRage.cast(caster, level); yield false; }
            case SOLAR_FLARE -> { DragonMoveNuova.cast(caster, level); yield false; }
            case POLLUTION -> { DragonMoveHaze.cast(caster, level); yield false; }
            case HURRICANE_FURY -> { DragonMoveOceanus.cast(caster, level); yield false; }
            case DRAGON_QUAKE -> { DragonMoveQuake.cast(caster, level); yield false; }
            // OMEGA IS A REAL BALL. Returning false lets DMZ launch its own GIANT_BALL, wearing this move's black
            // core and red outline, and the effects land when it HITS (DragonProjectileHit) instead of detonating
            // around the caster. It used to be handled here, which is why it looked like it did nothing: the blast
            // went off at the caster's feet with no ball ever leaving their hand.
            case MINUS_ENERGY_POWER_BALL -> true;
        };
    }

    /**
     * Everything a move may legitimately hit within {@code radius} of the caster.
     *
     * <p>SELF AND KIN IMMUNITY. The caster is excluded, and so is every other shadow dragon, player or boss:
     * "shadow dragons should be immune to their own attacks and effects" is enforced once, here, rather than being
     * re-remembered in each move, because a single move forgetting it is exactly how a dragon freezes itself or a
     * boss burns the boss beside it. It covers effects as well as damage, since every move applies its effect only
     * to entities from this list.
     */
    public static List<LivingEntity> targets(LivingEntity caster, ServerLevel level, double radius)
    {
        AABB box = caster.getBoundingBox().inflate(radius);
        Predicate<LivingEntity> allowed = e -> e != caster
                && e.isAlive()
                && !isImmuneShadowDragon(e)
                && e.distanceTo(caster) <= radius;
        return level.getEntitiesOfClass(LivingEntity.class, box, allowed);
    }

    /**
     * True for anything that shrugs off shadow dragon moves: a player currently on any shadow dragon race, and any
     * of the live boss dragons.
     *
     * <p>Deliberately the whole family rather than only the caster's own dragon, so two shadow dragons fighting side
     * by side never catch each other, and the seven bosses never damage one another in a shared arena.
     */
    public static boolean isImmuneShadowDragon(LivingEntity entity)
    {
        if (entity instanceof ServerPlayer player)
            return ShadowDragonFormCompat.isShadowDragon(player);
        return DragonBossLookup.isBoss(entity);
    }
}
