package net.shurui.dev.sdu.buff;

import net.minecraft.server.level.ServerPlayer;

/**
 * Bridge between the two stat-discount mixins. {@code IncreaseStatC2SMixin} publishes the player who
 * is currently buying stats into {@link #CURRENT_BUYER} for the duration of the (server-thread,
 * synchronous) stat-purchase work, and {@code StatsDataCostMixin} reads it back inside
 * {@code getSingleStatCost} to apply that player's active STAT-discount tokens.
 *
 * <p>A ThreadLocal is correct here because DMZ resolves the buyer and then does the whole
 * affordability + charge calculation inline on the server thread (inside the enqueued network work
 * runnable), so the value is only ever set/read on that one thread and is cleared on return.
 */
public final class TokenStatDiscount {

    /** The player currently buying stats, or {@code null} when no stat purchase is in flight. */
    public static final ThreadLocal<ServerPlayer> CURRENT_BUYER = new ThreadLocal<>();

    private TokenStatDiscount() {
    }
}
