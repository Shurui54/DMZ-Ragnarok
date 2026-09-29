package net.shurui.dev.shuruis_dmz_dungeons.compat;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.shuruisutilities.shard.ShardSync;

// optional Shurui's Utilities integration: send a dungeon-ejected player to THIS server's spawn using the SAME
// shard-aware resolution /spawn, a fresh login and spawn-target portals use (ShardSync.placeAtServerSpawn), so the
// time-out eject and a spawn portal land in the identical place instead of each rolling their own (a raw shared
// spawn, or a wandering safe-spot search, which is what put ejected players at a "random spot"). classloaded ONLY
// behind ModList.isLoaded("shuruisutilities") from DungeonTimeEvents.returnPlayer; SU absent -> never touched.
public final class UtilitiesSpawnCompat {

    private UtilitiesSpawnCompat() {
    }

    // route the player to this server's spawn through the single shard-aware resolution. Self-contained: it teleports
    // locally (SU spawn, else this server's overworld world spawn), or, only when this server has no spawn of its own
    // (the SMP), hands the player to an open world that places them on arrival. placeAtServerSpawn swallows and logs
    // its own errors, so the caller needs no fallback. Runs synchronously on the server thread.
    public static void sendToServerSpawn(ServerPlayer player) {
        try {
            ShardSync.placeAtServerSpawn(player);
        } catch (Throwable t) {
            Shuruis_dmz_dungeons.LOGGER.warn(
                    "[{}] Could not send dungeon-ejected player to spawn ({}).",
                    Shuruis_dmz_dungeons.MODID, t.toString());
        }
    }
}
