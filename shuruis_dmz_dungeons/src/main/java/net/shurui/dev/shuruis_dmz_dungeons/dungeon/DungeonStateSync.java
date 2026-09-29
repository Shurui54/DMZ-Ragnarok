package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.shuruisutilities.shard.ShardStateSync;

/**
 * Hands the dungeon module's admin-editable SavedData to the cross-server state engine, so a floor, rule or warp
 * edited through the manager on one server is live on every server without a restart.
 *
 * <p>Each entry resolves the CURRENT server inside its read/write rather than capturing one, so it survives a world
 * reload without re-registration, and returns null while the dungeon level does not yet exist (nothing to send).
 * Everything registers: the operator switchboard was removed in batch M, and this code only runs when the dungeon
 * module jar is installed.
 */
public final class DungeonStateSync {

    private DungeonStateSync() {}

    public static void register() {
        try {
            // Floor CONFIGS only (see DungeonFloors.saveSharedConfig): the terrain a floor was stamped into stays this
            // world's own; only the theme/size/depth/count travels.
            ShardStateSync.register("dungeons:floors",
                    () -> read(server -> {
                        DungeonFloors f = DungeonFloors.get(server);
                        return f == null ? null : f.saveSharedConfig(new CompoundTag());
                    }),
                    tag -> write(server -> {
                        // Force-create the legacy dungeon dim so the edit lands on a server that never entered a
                        // dungeon. Throwing (not a silent no-op) hands the row to ShardStateSync's retry instead
                        // of dropping it forever.
                        DungeonFloors f = DungeonFloors.getOrCreate(server);
                        if (f == null) {
                            throw new IllegalStateException(
                                    "dungeon level unavailable, deferring floors write for retry");
                        }
                        f.loadInto(tag);
                    }));

            ShardStateSync.register("dungeons:rules",
                    () -> read(server -> {
                        DungeonRules r = DungeonRules.get(server);
                        return r == null ? null : r.save(new CompoundTag());
                    }),
                    tag -> write(server -> {
                        DungeonRules r = DungeonRules.getOrCreate(server);
                        if (r == null) {
                            throw new IllegalStateException(
                                    "dungeon level unavailable, deferring rules write for retry");
                        }
                        r.loadInto(tag);
                    }));

            ShardStateSync.register("dungeons:warps",
                    () -> read(server -> DungeonWarps.get(server).save(new CompoundTag())),
                    tag -> write(server -> DungeonWarps.get(server).loadInto(tag)));

            // Per-player crate loot and ticket unlocks: keyed by stable dungeon positions and player UUIDs, so they
            // mean the same thing on every server and follow a player across a hop. Both MERGE on write (see mergeInto)
            // so a claim or unlock for one player is never dropped by an edit for another.
            ShardStateSync.register("dungeons:crates",
                    () -> read(server -> {
                        DungeonCrateData d = DungeonCrateData.get(server);
                        return d == null ? null : d.save(new CompoundTag());
                    }),
                    tag -> write(server -> {
                        DungeonCrateData d = DungeonCrateData.getOrCreate(server);
                        if (d == null) {
                            throw new IllegalStateException(
                                    "dungeon level unavailable, deferring crate merge for retry");
                        }
                        d.mergeInto(tag);
                    }));

            ShardStateSync.register("dungeons:ticket_unlocks",
                    () -> read(server -> DungeonTicketUnlocks.get(server).save(new CompoundTag())),
                    tag -> write(server -> DungeonTicketUnlocks.get(server).mergeInto(tag)));

            // Per-player re-entry cooldowns: keyed by UUID, an expiry instant meaning the same on every server. MERGES
            // on write (keeps the later expiry) so a player cannot dodge a cooldown by hopping to a server that never
            // saw them enter.
            ShardStateSync.register("dungeons:cooldowns",
                    () -> read(server -> DungeonCooldowns.get(server).save(new CompoundTag())),
                    tag -> write(server -> DungeonCooldowns.get(server).mergeInto(tag)));
        } catch (Throwable t) {
            // A registration failure must never take server start down: the module simply does not travel.
            Shuruis_dmz_dungeons.LOGGER.warn("[{}] Could not register dungeon state sync: {}",
                    Shuruis_dmz_dungeons.MODID, t.toString());
        }
    }

    private interface ServerRead {
        CompoundTag apply(MinecraftServer server);
    }

    private interface ServerWrite {
        void apply(MinecraftServer server);
    }

    private static CompoundTag read(ServerRead body) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : body.apply(server);
    }

    private static void write(ServerWrite body) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            // No running server means the write cannot apply. Throw so ShardStateSync's retry holds the row, rather
            // than a silent return that looks like success and drops the edit forever.
            throw new IllegalStateException("no running server, deferring dungeon state write for retry");
        }
        body.apply(server);
    }
}
