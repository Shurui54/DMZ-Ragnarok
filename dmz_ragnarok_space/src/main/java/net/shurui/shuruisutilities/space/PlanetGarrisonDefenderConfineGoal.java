package net.shurui.shuruisutilities.space;

import java.util.EnumSet;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps ONE garrison fighter on its planet's surface disc. Each fighter confines ITSELF through its own goal, so there
 * is no global per-tick entity scan. Chassis-agnostic: drives any {@link Mob} that also implements
 * {@link PlanetGarrisonHome}, so both {@link PlanetGarrisonDefenderEntity} and {@link PlanetSaiyanGarrisonEntity} share
 * it unchanged.
 *
 * <p>Flagless always-on rather than a stroll restriction: DMZ saga AI reaches its target with combat movement that can
 * TELEPORT with no bounds check ({@code DBSagasEntity.performProactiveTeleport} / {@code applyApproach}), so a
 * pathfinding restriction would not hold it. Requesting NO AI flags ({@link Goal#setFlags} empty) lets the selector run
 * this every tick alongside the combat goals without blocking them. The tick clamps X/Z back inside the disc (same as
 * {@code GuildRaidClones.confine}); Y is left alone for jumps and falls.
 */
final class PlanetGarrisonDefenderConfineGoal extends Goal
{
    // pull the fighter this far INSIDE the edge so it does not sit on the boundary and re-cross. Matches the
    // guild-raid clone inset.
    private static final double EDGE_INSET = 1.0;

    // held separately so any Mob that also implements PlanetGarrisonHome can be confined, not just one class.
    private final Mob defender;
    private final PlanetGarrisonHome home;

    <E extends Mob & PlanetGarrisonHome> PlanetGarrisonDefenderConfineGoal(E defender)
    {
        this.defender = defender;
        this.home = defender;
        // no MOVE/LOOK/JUMP/TARGET flags: must coexist with every combat goal, never interrupt one.
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    // active only with a home planet assigned AND on the shared surface dimension. The clamp geometry is defined only
    // for generated surface cells; on an inhabited planet dimension (Planet Vegeta's town, whose passive saiyans share
    // this goal) the id folds to a far-off hashed cell, so the goal must stand down and let the townsfolk live.
    // Garrison defenders only ever exist on the surface dimension, so this guard never changes their behaviour.
    @Override
    public boolean canUse()
    {
        if (!SurfaceDimension.isSurface(this.defender.level()))
        {
            return false;
        }
        String planetId = this.home.getHomePlanetId();
        return planetId != null && !planetId.isEmpty();
    }

    // a defender's home never changes, so keep running for its whole life.
    @Override
    public boolean canContinueToUse()
    {
        return canUse();
    }

    // per-tick so the clamp keeps up with DMZ's teleporting combat movement, not the coarse default cadence.
    @Override
    public boolean requiresUpdateEveryTick()
    {
        return true;
    }

    @Override
    public void tick()
    {
        // clamp geometry is only valid on the surface dimension (see canUse).
        if (!SurfaceDimension.isSurface(this.defender.level()))
        {
            return;
        }
        String planetId = this.home.getHomePlanetId();
        if (planetId == null || planetId.isEmpty())
        {
            return;
        }
        Vec3 centre = SurfaceDimension.cellCentre(planetId);
        double half = GeneratedPlanetClaims.stampedSizeForId(this.defender.level().getServer(), planetId) / 2.0;
        double dx = this.defender.getX() - centre.x;
        double dz = this.defender.getZ() - centre.z;
        boolean outX = Math.abs(dx) > half;
        boolean outZ = Math.abs(dz) > half;
        if (!outX && !outZ)
        {
            return;
        }
        double nx = outX ? centre.x + Math.copySign(half - EDGE_INSET, dx) : this.defender.getX();
        double nz = outZ ? centre.z + Math.copySign(half - EDGE_INSET, dz) : this.defender.getZ();
        this.defender.teleportTo(nx, this.defender.getY(), nz);
        // kill horizontal velocity so it does not slide back over the edge; keep vertical for gravity / a jump.
        Vec3 v = this.defender.getDeltaMovement();
        this.defender.setDeltaMovement(0.0, v.y, 0.0);
    }
}
