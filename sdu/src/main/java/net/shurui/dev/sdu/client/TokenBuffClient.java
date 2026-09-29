package net.shurui.dev.sdu.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.shurui.dev.sdu.network.TokenBuffSyncPacket;

/**
 * The local player's running buff tokens, as last told by the server.
 *
 * <p>Exists so the PRICE the stat screen draws matches the price the server will actually charge. See
 * {@link TokenBuffSyncPacket} for why that needed a packet at all.
 *
 * <p>Entries carry their own expiry and are summed live rather than being handed over as a total, so a discount
 * stops counting the moment its token runs out even if no further sync arrives.
 */
public final class TokenBuffClient {

    private static volatile List<TokenBuffSyncPacket.Entry> stat = List.of();
    private static volatile List<TokenBuffSyncPacket.Entry> tp = List.of();

    private TokenBuffClient() {
    }

    public static void accept(List<TokenBuffSyncPacket.Entry> statEntries, List<TokenBuffSyncPacket.Entry> tpEntries) {
        double statBefore = strongest(stat);
        stat = statEntries == null ? List.of() : List.copyOf(statEntries);
        tp = tpEntries == null ? List.of() : List.copyOf(tpEntries);
        // Rebuild DMZ's open stat screen ONLY when the discount the player sees actually changed (a gem used or a
        // gem expiring), which is the case the refresh was added for. A plain stat purchase resyncs the SAME tokens
        // (IncreaseStatC2SMixin resends after every buy so the priced screen never drifts from the server), and
        // rebuilding the screen on that path re-runs the screen's init and throws away the buy multiplier the player
        // had selected, resetting it to Stat 1. That reset on every purchase is what players reported as the menu
        // closing and reopening on each stat point (2026-09-16).
        if (strongest(stat) != statBefore) {
            refreshOpenStatScreen();
        }
    }

    /**
     * Rebuild DMZ's stat screen if it is the screen currently open, so a discount that just landed is priced in at
     * once.
     *
     * <p>DMZ's CharacterStatsScreen recomputes prices and rebuilds its buy buttons on its own 10-tick cadence, and
     * while a price still reads full it draws NO buy button for a stat the player cannot yet afford. A player who
     * used a token therefore saw the old price and no button for up to half a second plus the round trip, read that
     * as "the gem did nothing" and used another one (ticket 813, 2026-09-15). The second use is refused and the
     * token kept, but the confusion is real, so the screen is re-initialised the moment the sync arrives.
     *
     * <p>Matched by class NAME: DMZ is an optional-at-compile dependency here and nothing may classload its screen
     * outside this check.
     */
    public static void refreshOpenStatScreen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.screen == null || mc.getWindow() == null) {
                return;
            }
            if (!DMZ_STATS_SCREEN.equals(mc.screen.getClass().getName())) {
                return;
            }
            // the same path a window resize takes: re-runs the screen's init, which rebuilds the price text and the
            // buy buttons from the discount we have just stored.
            mc.screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        } catch (Throwable ignored) {
            // a refresh is a convenience: the screen still self-corrects on its own refresh tick
        }
    }

    private static final String DMZ_STATS_SCREEN = "com.dragonminez.client.gui.character.CharacterStatsScreen";

    public static void clear() {
        stat = List.of();
        tp = List.of();
    }

    /** The stat-purchase discount to show, as a fraction. */
    public static double statDiscount() {
        return strongest(stat);
    }

    /** The same, but ZERO off the client thread. What the shared cost mixin asks for; see {@link #onClientThread}. */
    public static double clientStatDiscount() {
        // Token buffs are the Ragnarok Key's (feature tokenbuffs): against a keyless server nothing is discounted,
        // whatever a stale copy says, because the server would charge the full price.
        if (!net.shurui.dev.sdu.api.ClientGate.feature(net.shurui.dev.sdu.api.key.TokenBuffHooks.FEATURE_ID)) {
            return 0.0;
        }
        return onClientThread() ? strongest(stat) : 0.0;
    }

    public static double tpBoost() {
        return strongest(tp);
    }

    /**
     * True only on the client's own thread.
     *
     * <p>The cost mixin is shared by both sides, and in single player both sides are one JVM: without this the
     * integrated server's own cost calculations would pick up the client's copy of the discount and apply it to
     * things that are not a purchase at all.
     */
    public static boolean onClientThread() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isSameThread();
        } catch (Throwable t) {
            return false;
        }
    }

    // the strongest entry still running, matching the server's rule (TokenBuffStore.strongestActive). Entries do not
    // add up: a price drawn from a sum would not be the price the server charges.
    private static double strongest(List<TokenBuffSyncPacket.Entry> entries) {
        long now = System.currentTimeMillis();
        double best = 0.0;
        for (TokenBuffSyncPacket.Entry e : entries) {
            if (e.expiresAtMillis() > now) {
                best = Math.max(best, e.fraction());
            }
        }
        return best;
    }
}
