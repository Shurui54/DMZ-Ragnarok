package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.wish.ReleaseBoostStore;

/**
 * Adds the wished-for release bonus ({@link ReleaseBoostStore}) to the ceiling DMZ enforces while the player charges
 * their ki release, on the SERVER. This is one of three identical injection sites (charge tick here, the manual
 * release-limit packet, and the radial node's display): DMZ recomputes {@code 50 + potentialunlock * 5} at each and
 * keeps no stored ceiling, so the bonus has to be folded in at every one.
 *
 * <h2>Ordinal 0 is deliberate and load bearing</h2>
 * {@code chargePowerRelease} writes {@code maxRelease} (local slot 5) twice: first the formula
 * {@code 50 + potentialunlock * 5}, then {@code Math.min(maxRelease, releaseLimit)} which applies the throttle the
 * player chose. Ordinal 0 is the formula store; the throttle store is ordinal 1. Boosting the throttle store too would
 * let a player charge PAST the release limit they deliberately set, so this must land on ordinal 0 only. Verified in
 * the 2.1.3 bytecode: two {@code istore 5} to the "maxRelease" local, the first being the formula.
 *
 * <p>{@code remap = false} and {@code require = 0} so a DMZ reshape degrades to the vanilla ceiling rather than
 * crashing, and the handler swallows any Throwable back to the original value. The handler takes the target's full
 * parameter list (per the argument-capture house rule: all of them or none), which is also how it reaches the player.
 */
@Mixin(targets = "com.dragonminez.server.events.players.TickHandler", remap = false)
public abstract class MixinDmzReleaseChargeCap
{
    @ModifyVariable(method = "chargePowerRelease", at = @At(value = "STORE", ordinal = 0),
            name = "maxRelease", require = 0)
    private static int su$addReleaseBonus(int maxRelease, StatsData data, int power, boolean drain)
    {
        try
        {
            return maxRelease + ReleaseBoostStore.bonusFor(data.getPlayer());
        }
        catch (Throwable t)
        {
            return maxRelease;
        }
    }
}
