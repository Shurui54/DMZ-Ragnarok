package net.shurui.dev.shuruis_raid_bosses.data;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.dev.shuruis_raid_bosses.Config;
import net.shurui.dev.shuruis_raid_bosses.rift.RiftDefs;
import net.shurui.shuruisutilities.shard.ShardStateSync;

/**
 * Hands the raid module's definitions to the cross-server state engine, so a raid or rift authored on one
 * server is live on every server without a restart.
 *
 * <p>Only the DEFINITIONS travel. Per-server scheduling (RaidData's last-fired clock) and per-world arena
 * cells (RiftArenaData) stay local: one is a per-server timer, the other points at terrain in that
 * server's own dimension only. Each entry resolves the current server on demand and is gated on the same
 * module switches the raid commands honour.
 */
public final class RaidStateSync {

    private RaidStateSync() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void register() {
        try {
            ShardStateSync.register("raids:defs",
                    () -> read(server -> RaidData.get(server).saveDefs(new CompoundTag())),
                    tag -> write(server -> RaidData.get(server).loadDefsInto(tag)));
            ShardStateSync.register("raids:rifts",
                    () -> read(server -> RiftDefs.get(server).save(new CompoundTag())),
                    tag -> write(server -> RiftDefs.get(server).loadInto(tag)));
            // Z-Soul tuning (slot count + four tier percentages) is read LIVE off this server's toml and
            // synced nowhere, so retuning one shard left the others stale until a restart. Carry it as a
            // last-write-wins entry into Config's live fields, taking effect on the next stat calc. Does
            // NOT touch the toml, so a restart re-seeds from local toml (Config.onLoad); a synced value
            // survives a restart only if the toml itself is carried, which is the file sync's job.
            ShardStateSync.register("zsoul:tuning",
                    RaidStateSync::readZSoulTuning,
                    RaidStateSync::writeZSoulTuning);
        } catch (Throwable t) {
            // never fail server start over a sync registration; the module just does not travel
            LOGGER.warn("Could not register raid state sync: {}", t.toString());
        }
    }

    // Z-Soul tuning lives in Config's ConfigValues, not a SavedData. Only runtime-consumed values are carried
    // (slot count + four tier percentages); auto-convert / TP-per-point / growth-per-second are read nowhere
    // today. ConfigValue.set() updates what .get() returns and writes to the local toml on the next save.
    // Reads return null before config load so a half-started server publishes nothing; only keys present in
    // an incoming tag are applied, so a sibling on an older build can never zero a value here.
    private static CompoundTag readZSoulTuning() {
        if (!Config.loaded) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("slotCount", Config.ZSOUL_SLOT_COUNT.get());
        tag.putDouble("bronze", Config.ZSOUL_BRONZE_PERCENT.get());
        tag.putDouble("silver", Config.ZSOUL_SILVER_PERCENT.get());
        tag.putDouble("gold", Config.ZSOUL_GOLD_PERCENT.get());
        tag.putDouble("prismatic", Config.ZSOUL_PRISMATIC_PERCENT.get());
        return tag;
    }

    private static void writeZSoulTuning(CompoundTag tag) {
        if (!Config.loaded) {
            // throw so ShardStateSync holds the row for retry rather than dropping the edit
            throw new IllegalStateException("raid config not loaded, deferring zsoul tuning write for retry");
        }
        if (tag.contains("slotCount")) Config.ZSOUL_SLOT_COUNT.set(tag.getInt("slotCount"));
        if (tag.contains("bronze")) Config.ZSOUL_BRONZE_PERCENT.set(tag.getDouble("bronze"));
        if (tag.contains("silver")) Config.ZSOUL_SILVER_PERCENT.set(tag.getDouble("silver"));
        if (tag.contains("gold")) Config.ZSOUL_GOLD_PERCENT.set(tag.getDouble("gold"));
        if (tag.contains("prismatic")) Config.ZSOUL_PRISMATIC_PERCENT.set(tag.getDouble("prismatic"));
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
            // no server means the write cannot land. Throw so ShardStateSync holds the row for retry; a silent
            // return would look like success and drop the edit forever.
            throw new IllegalStateException("no running server, deferring raid state write for retry");
        }
        body.apply(server);
    }
}
