package net.shurui.dev.shuruis_raid_bosses.client;

/**
 * Client-side state of the raid progress bar, fed by {@code RaidHudPacket} and drawn by
 * {@code RaidHudOverlay}. The server refreshes it a few times a second while a raid is active; if updates
 * stop arriving (disconnect, dropped clear packet) the bar auto-hides after a short grace period.
 */
public final class RaidHudState {

    private static volatile boolean active;
    private static volatile int mode;
    private static volatile String label = "";
    private static volatile float current;
    private static volatile float max;
    private static volatile long lastUpdateMillis;

    private RaidHudState() {}

    public static void set(boolean isActive, int newMode, String newLabel, float cur, float newMax) {
        active = isActive;
        mode = newMode;
        label = newLabel == null ? "" : newLabel;
        current = cur;
        max = newMax;
        lastUpdateMillis = System.currentTimeMillis();
    }

    /** Whether the bar should draw (active and refreshed within the last 3 seconds). */
    public static boolean visible() {
        return active && System.currentTimeMillis() - lastUpdateMillis < 3000;
    }

    public static int mode() {
        return mode;
    }

    public static String label() {
        return label;
    }

    public static float current() {
        return current;
    }

    public static float max() {
        return max;
    }
}
