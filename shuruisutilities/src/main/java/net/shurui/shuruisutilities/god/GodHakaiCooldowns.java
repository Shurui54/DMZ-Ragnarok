package net.shurui.shuruisutilities.god;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * The eight hour hakai cooldown table, held in CORE so {@link GodHakaiStorage} (SavedData
 * {@code shuruisutilities_god_hakai}) reads and writes the same rows with or without the Ragnarok Key. The hakai itself
 * is the key's (feature {@code roles}); keeping the table here is what stops a keyless save from writing an empty
 * table over a god's running cooldown.
 *
 * <p>Each row is the wall-clock millisecond a player's cooldown expires. Server thread only, like the cast.
 */
public final class GodHakaiCooldowns
{
    private GodHakaiCooldowns() {}

    // player uuid -> the wall-clock millisecond their cooldown expires. Persisted through GodHakaiStorage.
    private static final Map<UUID, Long> readyAt = new HashMap<>();

    /** Milliseconds remaining on this player's cooldown, or 0 when it is ready. */
    public static long cooldownRemaining(ServerPlayer player, long now)
    {
        Long ready = readyAt.get(player.getUUID());
        return ready == null || now >= ready ? 0L : ready - now;
    }

    /** Start (or restart) a player's cooldown so it expires at {@code readyAtMillis}. */
    public static void start(UUID player, long readyAtMillis)
    {
        readyAt.put(player, readyAtMillis);
    }

    /** Restore a persisted cooldown row at load. */
    public static void restore(UUID player, long readyAtMillis)
    {
        readyAt.put(player, readyAtMillis);
    }

    /**
     * Adopt a cooldown from a sibling server only when it expires LATER than the one held, for the cross-server merge.
     * Keeping the later expiry is the only safe direction: shortening a cooldown on a hop would hand a god a free
     * reset, which is the whole exploit this exists to close.
     *
     * @return true when the held value actually moved, so the store can mark itself dirty
     */
    public static boolean mergeCooldown(UUID player, long readyAtMillis)
    {
        Long current = readyAt.get(player);
        if (current != null && readyAtMillis <= current)
            return false;
        readyAt.put(player, readyAtMillis);
        return true;
    }

    /** Every live cooldown row, for saving. */
    public static Map<UUID, Long> rows()
    {
        return new HashMap<>(readyAt);
    }
}
