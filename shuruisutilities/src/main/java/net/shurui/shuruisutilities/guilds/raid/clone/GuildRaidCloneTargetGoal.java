package net.shurui.shuruisutilities.guilds.raid.clone;

import java.util.EnumSet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.player.Player;

/**
 * The defending clone's guild-aware target SELECTION. This adds no new AI system: it is a single target goal that
 * supersedes DragonMineZ's generic nearest-player targeting, sitting at top priority in the clone's target
 * selector. The hard filter (attackers only, owners and bystanders never) lives in
 * {@link GuildRaidCloneEntity#canAttack}; this goal only decides WHICH of the valid attackers to prefer, so it is a
 * target selection preference rather than a separate system.
 *
 * <p>The preference makes the clone fight ALONGSIDE the planet's owners: among the attackers it may target, it
 * prefers whichever one is currently attacking an owning-guild member (its ally), and falls back to the nearest
 * attacker otherwise. "currently attacking an owner" is read cheaply from the attacker's own last-hurt-mob memory,
 * which vanilla already decays after a short window, so this needs no timers of its own. Kept cheap: it scans only
 * the level's players (a handful during a raid), on acquisition and on a slow rescan, never a world-wide sweep.
 */
class GuildRaidCloneTargetGoal extends TargetGoal
{
    // how often, in ticks, to re-evaluate the preference so the clone can switch to an attacker that has just
    // started attacking an owner. cheap because it only scans players; one second is responsive enough for a fight.
    private static final int RESCAN_INTERVAL = 20;

    private final GuildRaidCloneEntity clone;
    private int rescanCooldown;

    GuildRaidCloneTargetGoal(GuildRaidCloneEntity clone)
    {
        super(clone, false);
        this.clone = clone;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse()
    {
        this.targetMob = pickBest();
        return this.targetMob != null;
    }

    @Override
    public void start()
    {
        this.clone.setTarget(this.targetMob);
        this.rescanCooldown = RESCAN_INTERVAL;
        super.start();
    }

    @Override
    public boolean canContinueToUse()
    {
        LivingEntity current = this.clone.getTarget();
        if (current == null)
        {
            current = this.targetMob;
        }
        // drop the target the instant it stops being a legal attacker (dead, left the raid, orphaned clone). the
        // clone then idles rather than latching onto anyone it should not, matching the fail-safe direction.
        if (current == null || !current.isAlive() || !this.clone.canAttack(current))
        {
            return false;
        }
        double follow = this.clone.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (this.clone.distanceToSqr(current) > follow * follow)
        {
            return false;
        }
        // slow rescan: if another attacker has started attacking an owner and the current one is not, switch to
        // help the ally. re-pointing in place (rather than dropping the goal) avoids a one-tick targetless blip.
        if (--this.rescanCooldown <= 0)
        {
            this.rescanCooldown = RESCAN_INTERVAL;
            LivingEntity best = pickBest();
            if (best != null && best != current && rank(best) > rank(current))
            {
                current = best;
            }
        }
        this.targetMob = current;
        this.clone.setTarget(current);
        return true;
    }

    // choose the best attacker to target: highest preference rank first, nearest as the tie-break. returns null when
    // there is no legal attacker in range, which folds in every fail-safe case since canAttack gates each candidate.
    private LivingEntity pickBest()
    {
        double follow = this.clone.getAttributeValue(Attributes.FOLLOW_RANGE);
        double followSqr = follow * follow;
        LivingEntity best = null;
        int bestRank = -1;
        double bestDistSqr = Double.MAX_VALUE;
        for (Player p : this.clone.level().players())
        {
            if (!(p instanceof ServerPlayer player) || !this.clone.canAttack(player))
            {
                continue; // hard filter: attackers only, with the fail-safe already folded into canAttack
            }
            double distSqr = this.clone.distanceToSqr(player);
            if (distSqr > followSqr)
            {
                continue;
            }
            int rank = rank(player);
            if (rank > bestRank || (rank == bestRank && distSqr < bestDistSqr))
            {
                best = player;
                bestRank = rank;
                bestDistSqr = distSqr;
            }
        }
        return best;
    }

    // preference rank for a candidate attacker: 1 when it is currently attacking an owning-guild member (so the clone
    // fights alongside its ally), 0 otherwise. vanilla clears last-hurt-mob after its own short window, so a stale
    // hit stops scoring on its own.
    private int rank(LivingEntity candidate)
    {
        if (!(candidate instanceof ServerPlayer attacker))
        {
            return 0;
        }
        LivingEntity victim = attacker.getLastHurtMob();
        if (victim instanceof ServerPlayer hitOwner && net.shurui.shuruisutilities.api.key.GuildRaidHooks.get().cloneIsOwner(this.clone, hitOwner))
        {
            return 1;
        }
        return 0;
    }
}
