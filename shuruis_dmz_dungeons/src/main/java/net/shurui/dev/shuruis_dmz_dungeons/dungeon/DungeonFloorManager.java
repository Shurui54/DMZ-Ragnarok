package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonKeyHooks;

// the SINGLE gated entry point to procedural dungeon floors. Every path that could bring a floor into existence (the
// /rg dungeon floor command, an SU /portal routed by UtilitiesPortalCompat, an event floor) goes through here, so an
// SU /portal that drops a player into a floor dim can never bypass the gate.
//
// S22a: procedural floors are PRIVATE and their logic (generation, builds, floor guardians) lives in the Ragnarok Key.
// This class is the facade that keeps the name, the Result enum and the static signatures every caller uses; each body
// is DungeonKeyHooks, whose keyless default generates nothing and teleports no one.
//
// FALLBACK CONTRACT: when the key is absent (Result.NO_KEY) this generates nothing and teleports no one. The dungeon
// then behaves EXACTLY as before: legacy flat dungeon dim, /rg dungeon tp, warps, time limit, cooldown, PvP toggle
// and ki-grief suppression all untouched. Procedural floors simply do not exist.
public final class DungeonFloorManager {

    public enum Result {
        SUCCESS,          // floor build ensured/queued, player teleported onto the floor
        NO_KEY,           // Ragnarok Key absent on a dedicated server: feature off, dungeon unchanged
        NO_DUNGEON_DIM,   // the floor's themed dimension could not be resolved or created as a ServerLevel
        NO_SUCH_FLOOR,    // floor number out of the configured range
        FAILED            // generation could not start (logged)
    }

    // int on a floor guardian's persistent data: the 1-based floor number it guards. Present means "this is a floor
    // boss", which the public spawner reward path reads to skip it (a guardian pays out by damage share instead).
    // Pinned literal: it is stored on live entities.
    public static final String TAG_FLOOR_BOSS = "sdd_floor_boss";

    private DungeonFloorManager() {
    }

    // procedural floors enabled right now (the key installed them and the key gate answers yes).
    public static boolean floorsEnabled() {
        return DungeonKeyHooks.available();
    }

    // ensure floor N's build exists (rolling and queueing on first visit) and teleport the player onto it. floorNumber is 1-based.
    public static Result teleportToFloor(ServerPlayer player, int floorNumber) {
        return DungeonKeyHooks.get().startFloor(player, floorNumber);
    }
}
