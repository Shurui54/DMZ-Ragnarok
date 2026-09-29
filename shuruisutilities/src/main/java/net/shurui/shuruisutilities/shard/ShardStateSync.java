package net.shurui.shuruisutilities.shard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;

import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Carries the suite's own saved state between servers, so a dungeon, rift, raid or anything else edited through a
 * Ragnarok menu takes effect everywhere without a restart. Core keeps the REGISTRY here, because core, the modules,
 * sdu and the key all register their stores by this name; the sync engine that reads it (publish, poll, apply, the
 * shared database clock) is the key's (Sh1: {@code ShardStateSyncEngine}), reached through {@link ShardHooks}.
 *
 * <p>Anything that wants to travel registers a name, a way to read its state and a way to write it. Keyless nothing
 * travels: the registrations are held and never read, {@link #isApplying()} is false and the clock offset is 0.
 */
public final class ShardStateSync
{
    private ShardStateSync() {}

    /**
     * One thing that travels: how to read its state, how to put it back, and an optional cheap change signal.
     *
     * <p>{@code changeSignal}, when present, is a monotonic counter the store bumps on every mutation (see the
     * {@code setDirty} overrides on the registered SavedData). When it has not moved since the last pass the engine
     * skips building its NBT entirely. Null keeps the old behaviour: build the tag and compare it every pass.
     */
    public record Entry(Supplier<CompoundTag> read, Consumer<CompoundTag> write,
                        java.util.function.LongSupplier changeSignal) {}

    private static final Map<String, Entry> REGISTRY = new LinkedHashMap<>();

    /**
     * Register a piece of state to travel with the network.
     *
     * @param key   a stable name, unique across the suite. It is the primary key of the row, so renaming one
     *              orphans its history exactly the way renaming a persisted class orphans its folder.
     * @param read  produces the current state, or null when there is nothing to send yet
     * @param write puts a received state back into the live object AND marks it dirty, so the change is both
     *              immediately in effect and eventually on disk
     */
    public static void register(String key, Supplier<CompoundTag> read, Consumer<CompoundTag> write)
    {
        register(key, read, write, null);
    }

    /**
     * As {@link #register(String, Supplier, Consumer)}, but with a cheap change signal.
     *
     * @param changeSignal a monotonic counter the store bumps on every mutation (never reset, unlike SavedData's own
     *                     dirty flag which the autosave clears). When it is unchanged since the last pass the store's
     *                     NBT is neither built nor compared. Pass null to always build and compare.
     */
    public static void register(String key, Supplier<CompoundTag> read, Consumer<CompoundTag> write,
                                java.util.function.LongSupplier changeSignal)
    {
        if (key == null || key.isBlank() || read == null || write == null)
        {
            return;
        }
        REGISTRY.put(key, new Entry(read, write, changeSignal));
        LoggingHandler.sulog.debug("[shard] State sync registered '{}'.", key);
    }

    /** Every registered entry, in registration order (a read-only view), for the sync engine. */
    public static Map<String, Entry> entries()
    {
        return Collections.unmodifiableMap(REGISTRY);
    }

    /** True while this server is applying state from the network. Keyless: false. */
    public static boolean isApplying()
    {
        return ShardHooks.get().stateSyncApplying();
    }

    /**
     * The gap, in millis, to add to this server's wall clock to reach the shared database clock ({@code dbNow -
     * localNow}). Zero off the network (local wall time IS the clock). Cheap: no database access. Keyless: 0.
     */
    public static long dbClockOffsetMillis()
    {
        return ShardHooks.get().dbClockOffsetMillis();
    }

    /**
     * Publish one registered entry's CURRENT state at once, bypassing the change detector and the boot seeding gate
     * (for a value edited so early in boot that the ordinary pass would swallow it). Server thread. Keyless: a no-op.
     */
    public static void forcePublish(String key)
    {
        ShardHooks.get().forcePublishState(key);
    }
}
