package net.shurui.shuruisutilities.gravitychamber;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;

/**
 * Shared constants and access rules for the guild gravity chamber. The tuning here is deliberately NOT configurable:
 * the TP multiplier and share ratio are fixed by the user's specification, while the gravity and radius the block
 * itself stores are bounded to these maxima.
 */
public final class GuildGravityChamber
{
    private GuildGravityChamber()
    {
    }

    /** Maximum gravity multiplier the chamber can be set to (1x..10x). */
    public static final double MAX_GRAVITY = 10.0;
    /** Minimum / maximum effect radius in blocks (a cube half-extent). */
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 25;

    /** FIXED, non-editable TP multiplier applied to sparring TP earned inside the chamber. */
    public static final double FIXED_TP_MULTIPLIER = 1.5;
    /** FIXED share ratio: this fraction of a fighter's chamber-spar TP is also granted to guildmates inside the area. */
    public static final double SHARE_RATIO = 0.10;

    /**
     * The total sparring-TP multiplier a chamber applies: the fixed 1.5x times the chamber's gravity setting. Higher
     * gravity means a harder, more rewarding session, so a 10x-gravity chamber pays 15x. This multiplies the base spar
     * payout ONLY (see {@code ChamberSparManager}); it never touches DragonMineZ's own training minigames or organic
     * TP gain.
     */
    public static double tpMultiplier(GuildGravityChamberBlockEntity chamber)
    {
        double gravity = chamber == null ? 1.0 : Math.max(1.0, chamber.getGravity());
        return FIXED_TP_MULTIPLIER * gravity;
    }

    /**
     * Whether this player is allowed to configure the chamber and start spars at it: a member of the owning guild, or
     * the placer themselves when the chamber was placed by a guildless player. An unowned chamber (no guild and no
     * recorded placer) is treated as usable by anyone, so a chamber placed before ownership existed is never bricked.
     */
    public static boolean canUse(ServerPlayer player, GuildGravityChamberBlockEntity chamber)
    {
        if (player == null || chamber == null)
        {
            return false;
        }
        String guildId = chamber.getOwnerGuildId();
        UUID owner = chamber.getOwnerUuid();
        if ((guildId == null || guildId.isEmpty()) && owner == null)
        {
            return true; // legacy / unowned chamber
        }
        if (guildId != null && !guildId.isEmpty())
        {
            Guild owning = GuildManager.byId(guildId);
            if (owning != null && owning.isMember(player.getUUID()))
            {
                return true;
            }
        }
        return owner != null && owner.equals(player.getUUID());
    }
}
