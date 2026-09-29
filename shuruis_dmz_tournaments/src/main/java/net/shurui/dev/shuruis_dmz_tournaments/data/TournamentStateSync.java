package net.shurui.dev.shuruis_dmz_tournaments.data;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.shuruisutilities.shard.ShardStateSync;

/**
 * Hands the module's admin-editable DEFINITIONS to the cross-server state engine, so a tournament (with its arena /
 * stands / waiting grounds, which are part of the definition) authored on one server is live on all without a
 * restart. Definitions travel through {@link TournamentData#saveDefsOnly} / {@link TournamentData#loadDefsFrom},
 * title holders through {@link TournamentData#saveTitlesOnly} / {@link TournamentData#loadTitlesFrom} (the built-in
 * titles grant an energy bar and abilities, so a champion is recognised network-wide, belt moving only by admin
 * grant, last-write-wins). The live bracket ({@code TournamentManager}, in memory) and the per-server last-fired
 * clock stay local: carrying the clock would let one server suppress or duplicate another's auto-runs.
 */
public final class TournamentStateSync {

    private TournamentStateSync() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void register() {
        try {
            ShardStateSync.register("tournaments:defs",
                    () -> read(server -> TournamentData.get(server).saveDefsOnly(new CompoundTag())),
                    tag -> write(server -> TournamentData.get(server).loadDefsFrom(tag)));

            // Title holders travel as their own last-write-wins-per-id entry: the built-in titles grant an energy
            // bar and abilities, so a champion on one server is the champion on all four.
            ShardStateSync.register("tournaments:titles",
                    () -> read(server -> TournamentData.get(server).saveTitlesOnly(new CompoundTag())),
                    tag -> write(server -> TournamentData.get(server).loadTitlesFrom(server, tag)));

            // Stat budget and forced-form tuning are read LIVE off this server's toml (in
            // TournamentCharacter.grantFighterKit and the forced-transform path) and synced nowhere, so retuning
            // one shard used to leave the others under different rules. Carry them last-write-wins. The write
            // reuses ConfigValue.set(): it takes effect on the next .get() and is written to the local toml on
            // the next config save, so both immediate and restart-persistent without a raw file copy.
            ShardStateSync.register("tournaments:tuning",
                    TournamentStateSync::readTuning,
                    TournamentStateSync::writeTuning);
        } catch (Throwable t) {
            // Never fail server start over a sync registration; the module simply does not travel.
            LOGGER.warn("Could not register tournament state sync: {}", t.toString());
        }
    }

    // Tuning lives in Config's ConfigValues, not a SavedData, so read/write work on those directly. Returns null
    // before the config loads so nothing publishes from a half-started server; only keys present in the tag are
    // applied, so a sibling on an older build can never zero a value here.
    private static CompoundTag readTuning() {
        if (!Config.loaded) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("statAllocations", Config.TOURNAMENT_STAT_ALLOCATIONS.get());
        tag.putBoolean("forceTransformAtHalf", Config.FORCE_TRANSFORM_AT_HALF.get());
        tag.putDouble("forcedFormStatMult", Config.FORCED_FORM_STAT_MULT.get());
        tag.putInt("forcedTransformIframeTicks", Config.FORCED_TRANSFORM_IFRAME_TICKS.get());
        return tag;
    }

    private static void writeTuning(CompoundTag tag) {
        if (!Config.loaded) {
            // Throw so ShardStateSync holds the row for retry rather than dropping the edit.
            throw new IllegalStateException("tournament config not loaded, deferring tuning write for retry");
        }
        if (tag.contains("statAllocations")) {
            Config.TOURNAMENT_STAT_ALLOCATIONS.set(tag.getInt("statAllocations"));
        }
        if (tag.contains("forceTransformAtHalf")) {
            Config.FORCE_TRANSFORM_AT_HALF.set(tag.getBoolean("forceTransformAtHalf"));
        }
        if (tag.contains("forcedFormStatMult")) {
            Config.FORCED_FORM_STAT_MULT.set(tag.getDouble("forcedFormStatMult"));
        }
        if (tag.contains("forcedTransformIframeTicks")) {
            Config.FORCED_TRANSFORM_IFRAME_TICKS.set(tag.getInt("forcedTransformIframeTicks"));
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
            // No server, so the write cannot land. Throw so ShardStateSync holds the row for retry; a silent return
            // would look like success and drop the edit forever.
            throw new IllegalStateException("no running server, deferring tournament state write for retry");
        }
        body.apply(server);
    }
}
