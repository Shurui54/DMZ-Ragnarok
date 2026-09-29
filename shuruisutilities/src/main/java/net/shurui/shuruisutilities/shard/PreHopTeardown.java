package net.shurui.shuruisutilities.shard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Feature teardown that has to happen BEFORE a player is handed to another shard.
 *
 * <h2>Why this exists at all</h2>
 * A seamless hop is not a logout followed by a login: {@link ShardSync#handOff} calls
 * {@code ShardPayload.capture(player)} while the player is still standing here and still ticking, and the proxy only
 * disconnects them afterwards. The captured compound IS the copy the destination loads, so the moment the capture
 * returns the player's persistent state is sealed. Everything a {@code PlayerLoggedOutEvent} handler does after that
 * (hand an item back into the inventory, write a DragonMineZ stat, restore a game mode, clear a tag) lands on an
 * entity that is about to be thrown away, and the destination loads the copy taken BEFORE any of it happened. The
 * handler runs, reports success and changes nothing that survives.
 *
 * <p>So anything a feature must undo when its owner leaves has to be undone HERE, synchronously, on the server
 * thread, before the capture. The ordering is the entire point of the class: move a registered consumer to after
 * {@code handOff} and it becomes a no-op again, silently, with no compile error and no log line.
 *
 * <h2>Shape</h2>
 * Keyed registration, so a feature that registers twice (a module restarted by the key gate, a handler re-created on
 * a server restart) replaces its own entry instead of stacking a second copy. Insertion ordered, so the run order is
 * the registration order and stays reproducible. Each consumer is run inside its own try/catch: a feature that throws
 * must not stop the player being handed over, because refusing the hop at this point leaves them here holding a
 * released lock, which is a far worse failure than one feature not tearing down.
 *
 * <h2>What a consumer may assume</h2>
 * The player is alive, connected, on the server thread, and has NOT yet been marked TRANSFERRING. Writing to their
 * inventory, their persistent NBT or their DMZ stats is therefore effective and will travel. What a consumer may NOT
 * assume is that the hop then happens: the proxy can refuse it and {@code ShardSync}'s watchdog reclaims the player
 * here. Every consumer is written so that outcome is merely a feature ending early (a cancelled trade, an ended spar,
 * a recalled vehicle), never a loss.
 */
public final class PreHopTeardown
{
    private PreHopTeardown() {}

    /** Registration order is run order. Guarded by its own monitor; both registration and running are rare. */
    private static final Map<String, Consumer<ServerPlayer>> HANDLERS = new LinkedHashMap<>();

    /**
     * Register (or replace) the teardown for one feature.
     *
     * @param key   stable id for the feature, e.g. "trade". Registering the same key twice replaces the first.
     * @param handler run on the server thread, before the vault capture, for the player about to be handed over.
     */
    public static void register(String key, Consumer<ServerPlayer> handler)
    {
        if (key == null || handler == null)
            return;
        synchronized (HANDLERS)
        {
            HANDLERS.put(key, handler);
        }
    }

    /** Drop a feature's teardown again. Used by nothing yet; here so a torn-down module can stop being called. */
    public static void unregister(String key)
    {
        if (key == null)
            return;
        synchronized (HANDLERS)
        {
            HANDLERS.remove(key);
        }
    }

    /**
     * Run every registered teardown for this player, in registration order, on the calling (server) thread.
     *
     * <p>Package private on purpose: {@link ShardTransfer#connect} is the one caller, and it is the one place that
     * knows a hop is about to start. Anything else calling this would tear a player's features down for a hop that
     * is not happening.
     */
    public static void run(ServerPlayer player)
    {
        if (player == null)
            return;
        Collection<Map.Entry<String, Consumer<ServerPlayer>>> snapshot;
        synchronized (HANDLERS)
        {
            if (HANDLERS.isEmpty())
                return;
            snapshot = new ArrayList<>(HANDLERS.entrySet());
        }
        List<String> failed = null;
        for (Map.Entry<String, Consumer<ServerPlayer>> entry : snapshot)
        {
            try
            {
                entry.getValue().accept(player);
            }
            catch (Throwable t)
            {
                if (failed == null)
                    failed = new ArrayList<>(2);
                failed.add(entry.getKey());
                LoggingHandler.sulog.error("[shard] Pre-hop teardown '{}' failed for {}; handing them over anyway.",
                        entry.getKey(), player.getGameProfile().getName(), t);
            }
        }
        if (failed != null)
        {
            LoggingHandler.sulog.warn("[shard] {} was handed over with {} teardown(s) incomplete: {}.",
                    player.getGameProfile().getName(), failed.size(), String.join(", ", failed));
        }
    }
}
