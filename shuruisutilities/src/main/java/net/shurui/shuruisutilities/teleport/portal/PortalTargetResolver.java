package net.shurui.shuruisutilities.teleport.portal;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.shuruisutilities.util.NamedWorldPoint;

// SPI implemented by the dungeons addon so SU's /portal can target dungeon FLOORS without SU classloading any
// dungeon type. SU owns the portal and its symbolic descriptor; the dungeons side owns the floor to
// dimension/column math and registers exactly ONE resolver at mod init (see UtilitiesPortalCompat). When no
// resolver is registered (dungeons absent) a floor/nextfloor portal fails clearly instead of stranding a player.
//
// Resolution is LATE: these methods run at walk-through time, so a theme change, a floor regeneration or a
// renumbered floor is reflected on the next use with no need to re-target the portal. The returned point is a
// themed dungeon-dim coordinate the dungeon portal compat then routes through its own gated floor manager.
public interface PortalTargetResolver
{

    enum Status
    {
        OK,
        NO_SUCH_FLOOR,   // the requested floor number is outside the configured floor range
        NOT_IN_DUNGEON,  // a nextfloor portal that is not sitting inside a themed dungeon floor dim
        LAST_FLOOR,      // a nextfloor portal on the final configured floor: there is no floor after it
        FAILED           // the floor's themed dimension could not be resolved or created (logged server-side)
    }

    final class Result
    {
        public final Status status;
        public final NamedWorldPoint point; // non-null only when status == OK

        private Result(Status status, NamedWorldPoint point)
        {
            this.status = status;
            this.point = point;
        }

        public static Result ok(NamedWorldPoint point)
        {
            return new Result(Status.OK, point);
        }

        public static Result fail(Status status)
        {
            return new Result(status, null);
        }
    }

    // absolute floor N to a themed dungeon-dim target (X = N * cell spacing). ensures the themed dimension exists
    // as a running level so the follow-up teleport can reach it.
    Result resolveFloor(ServerPlayer player, int floor);

    // the floor AFTER the one the player currently stands in, derived from the player's X in a themed dungeon dim.
    Result resolveNextFloor(ServerPlayer player);

    // command-time existence check so /portal ... floor N can refuse an out-of-range floor immediately.
    boolean floorExists(ServerPlayer player, int floor);
}
