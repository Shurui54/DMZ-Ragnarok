package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players who are inside a dungeon theme dimension for a reason that is NOT a dungeon run, and so must be
 * exempt from the dungeon time limit and the re-entry cooldown eject.
 *
 * <p>The raid addon's dimensional tear arenas are CELLS in the shared dungeon theme dimensions (they reuse
 * the terrain rather than adding new dimensions), so a player fighting a tear stands in a dungeon dimension
 * without being on a dungeon run. Without this exemption {@code DungeonTimeEvents.onChangedDimension} sees
 * that arrival as a fresh dungeon entry and, if the player happens to be on a dungeon re-entry cooldown,
 * ejects them to spawn one tick later, while the rift's own {@code confineParticipants} drags them straight
 * back into the arena. The two fight each other about twice a second, a full cross dimension transfer each
 * way, until the client runs out of memory loading and discarding chunks it never settles on. Seen live on
 * 2026-09-15 (Luxifear, ~123 overworld to dmz_ragnarok:stony round trips in about a minute).
 *
 * <p>This lives in the dungeon tree and is driven FROM the raid tree (which already reads dungeon types),
 * so no raid type is ever classloaded here. Membership is in memory and keyed by UUID: a rift run adds the
 * fighter for the life of the run and removes them once the arena is released, so nothing persists a restart
 * (an interrupted run is a failed one, the same rule the rift manager follows).
 */
public final class DungeonArenaGuests {

    private DungeonArenaGuests() {
    }

    private static final Set<UUID> GUESTS = ConcurrentHashMap.newKeySet();

    /** Mark a player as an arena guest, exempt from the dungeon timer and cooldown eject. */
    public static void add(UUID id) {
        if (id != null) {
            GUESTS.add(id);
        }
    }

    /** Drop a player's guest status once their run's arena is released. */
    public static void remove(UUID id) {
        if (id != null) {
            GUESTS.remove(id);
        }
    }

    /** True while this player is standing in a dungeon dimension as a raid arena guest, not a dungeon runner. */
    public static boolean contains(UUID id) {
        return id != null && GUESTS.contains(id);
    }
}
