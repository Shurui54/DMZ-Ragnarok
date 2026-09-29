package net.shurui.shuruisutilities.grave;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.dragonballbag.DragonBallTotem;

/**
 * The one place that decides whether a right click at a block position is on a grave totem the player is allowed to
 * open, so every protection layer (guild claims, SU regions / server claims, world-zone protection) asks the same
 * question and answers it the same way.
 *
 * <p>A grave / totem is an oak fence with a player head on top; opening it is intercepted by
 * {@link GraveEventHandler#onRightClick} at NORMAL priority, which is skipped if a higher-priority protection handler
 * has already cancelled the click (a cancelled {@code RightClickBlock} does not reach a NORMAL, non-receiveCanceled
 * listener). The guild handler cancels outright, and the region / world-zone handlers deny the interaction, so without
 * this exception a totem inside protected land could not be opened by anyone but the land's own members. Two cases are
 * let through:
 * <ul>
 *   <li><b>The grave's owner.</b> Dying in someone else's protected land must not cost a player their own items, which
 *       the grave sweep discards after its timer.</li>
 *   <li><b>Anyone, when the totem holds a dragon ball.</b> Balls are exempt from claim and region rules as world
 *       events ({@code RegionEventHandler.isDragonBall}), and a ball totem is what the radar leads other players to.
 *       Without this, logging out inside your own claim would lock a set away from every hunter for good, because a
 *       ball totem never expires.</li>
 * </ul>
 * Anyone else's ordinary loot grave stays behind the protection, exactly as a chest would. Only the right-click OPEN
 * is exempted; breaking the grave blocks is a separate BreakEvent that stays protected.
 *
 * <p>The grave may be clicked on its fence (the stored position) or on the head one above it, the same two spots
 * {@code GraveEventHandler} accepts.
 */
public final class GraveAccess
{
    private GraveAccess() {}

    /** Whether a right click at {@code pos} is on a grave totem this {@code player} may open despite the protection. */
    public static boolean mayOpen(Player player, Level level, BlockPos pos)
    {
        if (player == null || !(level instanceof ServerLevel server))
            return false;
        GraveStorage graves = GraveStorage.get(server);
        GraveData grave = graves.has(pos) ? graves.get(pos)
                : (graves.has(pos.below()) ? graves.get(pos.below()) : null);
        if (grave == null)
            return false;
        return player.getUUID().equals(grave.ownerId()) || DragonBallTotem.holdsDragonBall(grave.container());
    }
}
