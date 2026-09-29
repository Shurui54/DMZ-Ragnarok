package net.shurui.dev.sdu.util;

/**
 * String formatting helpers. The suite deliberately keeps a local copy of shared string
 * utilities per addon rather than sharing code at runtime (same pattern as {@link ColorCodes}).
 */
public final class StringUtil {

    private StringUtil() {}

    /**
     * Format a whole-second duration as {@code H:MM:SS}, omitting the hours block when it is zero
     * ({@code M:SS}). Examples: {@code 3661 -> "1:01:01"}, {@code 125 -> "2:05"}, {@code 0 -> "0:00"}.
     * Negative inputs are clamped to zero.
     */
    public static String formatDuration(long seconds)
    {
        if (seconds < 0)
            seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
