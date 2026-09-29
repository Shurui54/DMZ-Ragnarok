package net.shurui.dev.shuruis_dmz_tournaments.util;

/**
 * Converts owner-entered {@code &} colour codes to {@code §} codes at display time. Stored values keep the
 * raw {@code &} text so they stay editable in the vanilla
 * {@link net.minecraft.client.gui.components.EditBox}, which filters out {@code §}. Applied only where shown.
 *
 * <p>Supports {@code &0}-{@code &9}, {@code &a}-{@code &f}, {@code &k}-{@code &o}, {@code &r}
 * (case-insensitive), plus {@code &&} as a literal-ampersand escape.
 */
public final class ColorCodes {

    /** The colour/format codes that follow a {@code &} and get promoted to {@code §}. */
    private static final String CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private ColorCodes() {
    }

    /** Convert {@code &}-codes to {@code §}-codes; {@code &&} becomes a literal {@code &}. Null-safe. */
    public static String translate(String in) {
        if (in == null || in.indexOf('&') < 0) {
            return in;
        }
        StringBuilder sb = new StringBuilder(in.length());
        int len = in.length();
        for (int i = 0; i < len; i++) {
            char c = in.charAt(i);
            if (c == '&' && i + 1 < len) {
                char next = in.charAt(i + 1);
                if (next == '&') {
                    sb.append('&');
                    i++;
                    continue;
                }
                if (CODES.indexOf(next) >= 0) {
                    sb.append('§').append(Character.toLowerCase(next));
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Remove both {@code &}-codes and {@code §}-codes (for width/sort). {@code &&} collapses to {@code &}. Null-safe. */
    public static String strip(String in) {
        if (in == null || (in.indexOf('&') < 0 && in.indexOf('§') < 0)) {
            return in;
        }
        StringBuilder sb = new StringBuilder(in.length());
        int len = in.length();
        for (int i = 0; i < len; i++) {
            char c = in.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < len) {
                char next = in.charAt(i + 1);
                if (c == '&' && next == '&') {
                    sb.append('&');
                    i++;
                    continue;
                }
                if (CODES.indexOf(next) >= 0) {
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
