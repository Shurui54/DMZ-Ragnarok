package net.shurui.shuruisutilities.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.GuildPermission;
import net.shurui.shuruisutilities.guilds.GuildProtectionHandler;
import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

/**
 * One question, asked of every protection system at once: may this player break this block here?
 *
 * <h2>Why this exists</h2>
 * SU enforces block protection from Forge's {@code BlockEvent.BreakEvent}, which is correct for vanilla mining and
 * for any mod that breaks blocks the normal way. Mods that call {@code Level.destroyBlock} DIRECTLY never raise that
 * event, so none of those handlers ever see the break and the block goes regardless of who owns the land. DMZ's ki
 * grief was the first case of this (see {@code MixinDmzKiGrief}); Tinkers' staffs are another, which is how a staff
 * could break blocks inside a claim.
 *
 * <p>Chasing each offending mod one at a time does not converge - there is always another tool. So the destroy call
 * itself is guarded ({@code MixinLevelDestroyBlock}) and routed through here.
 *
 * <h2>It answers with the SAME rules the event handlers use</h2>
 * Deliberately delegates to {@link RegionEventHandler#canBuild} and {@link GuildProtectionHandler#canAffect} rather
 * than reimplementing either. A second copy of the rules would drift, and a protection check that disagrees with the
 * one players already know is worse than none. Both are consulted, and either one refusing is a refusal, because a
 * spot can be inside a region AND a guild claim.
 *
 * <h2>Cheap on the common path</h2>
 * This is asked on every direct block destruction, so it early-outs before doing any lookup: server side only, and
 * only when a PLAYER is responsible. A break with no responsible player (an explosion, a piston, worldgen) is not
 * this guard's business and costs a single instanceof check.
 */
public final class BlockBreakGuard
{
    private BlockBreakGuard() {}

    /**
     * @param breaker the entity responsible, which may be null (an explosion, a machine, worldgen).
     * @return true when the break is allowed, and when this guard has no opinion.
     */
    public static boolean mayBreak(Level level, BlockPos pos, Entity breaker)
    {
        if (level == null || level.isClientSide || pos == null)
            return true;
        if (!(breaker instanceof Player player))
            return true; // no responsible player: not this guard's call

        if (!regionAllows(player, pos))
            return false;
        return guildAllows(player, level, pos);
    }

    /** Region flags: the same {@link RegionFlag#BLOCK_BREAK} check the region break handler makes. */
    private static boolean regionAllows(Player player, BlockPos pos)
    {
        try
        {
            return RegionEventHandler.canBuild(player, RegionEventHandler.dimOf(player),
                    pos.getX(), pos.getY(), pos.getZ(), RegionFlag.BLOCK_BREAK);
        }
        catch (Throwable t)
        {
            // A protection lookup that errors must not brick block breaking for the whole server.
            return true;
        }
    }

    /** Guild claims: the same DESTROY permission check the guild break handler makes. */
    private static boolean guildAllows(Player player, Level level, BlockPos pos)
    {
        try
        {
            if (!GuildProtectionHandler.protecting())
                return true;
            Guild owner = GuildManager.claimOwner(level, pos);
            return GuildProtectionHandler.canAffect(player, owner, GuildPermission.DESTROY);
        }
        catch (Throwable t)
        {
            return true;
        }
    }
}
