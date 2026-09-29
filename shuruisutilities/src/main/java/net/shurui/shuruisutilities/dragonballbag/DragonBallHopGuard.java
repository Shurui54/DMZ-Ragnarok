package net.shurui.shuruisutilities.dragonballbag;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.shard.ShardSync;

/**
 * Answers one question for the dragon ball logout path: is this player leaving because the shard network is
 * handing them off to another server, rather than genuinely quitting?
 *
 * <h2>Why this exists</h2>
 *
 * <p>A shard hop is a logout on the origin server and an immediate login on the destination. The player's inventory
 * rides the cross-server vault, so a dragon ball in it ALREADY travels with them. If the origin also entombs that
 * ball in a grave totem on the way out (the ordinary logout behaviour, see {@link DragonBallInventoryHandler}), the
 * set exists twice: once in the totem left behind and once in the vault that lands on the destination. That is a free
 * duplicate for every hop. So the entomb must be skipped for a hop and run only for a real quit.
 *
 * <h2>The signal</h2>
 *
 * <p>The origin marks a player as handing off BEFORE it asks the proxy to move them
 * ({@link ShardSync#handOff} adds the id to its {@code TRANSFERRING} set, and the seamless handover path does the
 * same), and clears the mark inside its own {@code PlayerLoggedOutEvent} handler. So the mark is present exactly for
 * the window of a hop. The caller must read it at a priority ahead of {@code ShardSync.onLogout} so the mark is still
 * set when we look; {@link DragonBallInventoryHandler#onLogout} runs at {@code HIGH} for that reason.
 *
 * <p>{@link ShardSync#active()} is checked first, so a server with no shard network (the common case, and every
 * single player or LAN world) answers "not a transfer" and entombs normally.
 */
public final class DragonBallHopGuard
{
    private DragonBallHopGuard()
    {
    }

    /**
     * True when this player is mid shard transfer and their dragon balls must NOT be entombed on the way out.
     *
     * <p>False for a real quit, for a single server with no shard network, and for a null player.
     */
    public static boolean isMidTransfer(ServerPlayer player)
    {
        if (player == null)
        {
            return false;
        }
        // No shard network means there are no hops: a logout is a genuine quit and the balls entomb as normal.
        if (!ShardSync.active())
        {
            return false;
        }
        return ShardSync.isHandingOff(player.getUUID());
    }
}
