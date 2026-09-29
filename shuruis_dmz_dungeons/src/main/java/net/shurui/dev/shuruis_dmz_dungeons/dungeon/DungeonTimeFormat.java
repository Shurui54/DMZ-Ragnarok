package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

// One place to render a duration in seconds for the player-facing dungeon timers, so the boss bar, the entrance
// refusal and the portal refusal all read the same way instead of one showing "600s" and another "10m 0s". Two
// styles: clock() for the countdown boss bar (mm:ss, or h:mm:ss past an hour), human() for a refusal line
// ("10m 0s" / "45s"). Both take WHOLE seconds already resolved by the caller from the configured limit/cooldown.
public final class DungeonTimeFormat {

    private DungeonTimeFormat() {
    }

    // countdown clock: "m:ss", or "h:mm:ss" once there is an hour or more. Used for the dungeon time-left boss bar.
    public static String clock(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }

    // human phrasing for a refusal message: "10m 0s" when a minute or more, otherwise "45s".
    public static String human(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        if (seconds >= 60) {
            long m = seconds / 60;
            long s = seconds % 60;
            return m + "m " + s + "s";
        }
        return seconds + "s";
    }
}
