package net.shurui.dev.sdu.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * Core hook for the Raid Bosses Z-Soul system, so a consumer (Tournaments' Stat Gem over-cap overflow)
 * can offer refused points to a worn Z-Soul without naming Raid Bosses directly.
 *
 * <p>This lives in core (sdu), which every module can read from. Raid Bosses registers its
 * implementation at load; Tournaments reads it. When Raid Bosses is not installed the hook is
 * unset and every call is a no-op that returns 0, so no module classloads another module. This keeps
 * the tournaments to raid edge a soft, core-mediated hook rather than a direct or reflective
 * dependency, and lets Z-Souls move to a separate jar later without touching Tournaments.
 */
public final class ZSoulHook {

    /** Grant beyond-cap points to the worn Z-Soul for a DMZ stat key; returns how many landed. */
    @FunctionalInterface
    public interface GrantFn {
        int grantBeyondCap(ServerPlayer player, String dmzStatKey, int amount);
    }

    private static volatile GrantFn impl;

    private ZSoulHook() {
    }

    /** Called once by the Raid Bosses module at load. A later call replaces the previous fn. */
    public static void register(GrantFn fn) {
        impl = fn;
    }

    /** True when a provider (Raid Bosses) is present. */
    public static boolean available() {
        return impl != null;
    }

    /** Offer {@code amount} beyond-cap points to the worn Z-Soul; 0 when no provider or nothing lands. */
    public static int grantBeyondCap(ServerPlayer player, String dmzStatKey, int amount) {
        GrantFn f = impl;
        if (f == null || amount <= 0) {
            return 0;
        }
        try {
            return f.grantBeyondCap(player, dmzStatKey, amount);
        } catch (Throwable t) {
            return 0;
        }
    }
}
