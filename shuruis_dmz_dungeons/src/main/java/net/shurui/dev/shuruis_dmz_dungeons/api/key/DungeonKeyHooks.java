package net.shurui.dev.shuruis_dmz_dungeons.api.key;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorManager;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloors;

/**
 * Module-side hooks for the PRIVATE instanced dungeon floors (procedural generation, floor builds, floor guardians and
 * the floor admin commands), whose logic lives in the Ragnarok Key ({@code dmz_ragnarok_key}). The Dungeons module
 * keeps every registry, packet, screen, config, SavedData store and {@code dungeons:*} sync entry; this hook is how
 * the module reaches the moved logic, and only when the key installed it.
 *
 * <p>It lives in the module's own {@code api.key} package because a module never references the key (or another
 * module). The key installs the implementation only after {@code ModulePresence.dungeons()} says this module is
 * present, so a core-only server plus the key never classloads a dungeon type.
 *
 * <p>The {@link Impl} DEFAULTS are today's keyless behaviour: {@link #available()} is false (procedural floors do not
 * exist), a floor entry answers {@link DungeonFloorManager.Result#NO_KEY} (after the same floor-range check as before,
 * so an invalid floor still answers {@link DungeonFloorManager.Result#NO_SUCH_FLOOR}), and nobody is ever frozen by a
 * floor build. The legacy dungeon dim, warps, time limit, cooldown, PvP and ki-grief rules stay public in the module.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class DungeonKeyHooks {

    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "dungeonfloors";

    private DungeonKeyHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {

        /** Whether procedural floors are live right now. Keyless: false. */
        default boolean available() {
            return false;
        }

        /**
         * Ensure floor {@code floorNumber}'s build exists and land the player on it (1-based). Keyless: the floor-range
         * check, then {@link DungeonFloorManager.Result#NO_KEY}; nothing is generated and no one is teleported.
         */
        default DungeonFloorManager.Result startFloor(ServerPlayer player, int floorNumber) {
            if (player == null || player.getServer() == null) {
                return DungeonFloorManager.Result.NO_DUNGEON_DIM;
            }
            DungeonFloors floors = DungeonFloors.get(player.getServer());
            if (floors == null || !floors.isValidFloor(floorNumber)) {
                return DungeonFloorManager.Result.NO_SUCH_FLOOR;
            }
            return DungeonFloorManager.Result.NO_KEY;
        }

        /** Whether a floor build currently has this player pinned in place. Keyless: never. */
        default boolean isFrozen(UUID playerId) {
            return false;
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {
    };

    /** Install the key's implementation and mark the feature. Called once by the key's dungeon floor feature. */
    public static void install(Impl i) {
        if (i == null) {
            return;
        }
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get() {
        return impl;
    }

    /** Whether procedural floors are live on this server. */
    public static boolean available() {
        return impl.available();
    }
}
