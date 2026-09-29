package net.shurui.dev.shuruis_dmz_dungeons.util;

// convert owner-entered & codes to Minecraft's section codes at DISPLAY time. stored values keep the raw &
// so they stay editable in the vanilla EditBox (which filters out the section char); translate() runs only
// where the string is shown. supports &0-9 &a-f &k-o &r (case-insensitive) plus && as a literal-& escape.
public final class ColorCodes {

    private static final String CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private ColorCodes() {
    }

    // & -> section codes; && -> literal &. null-safe.
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

    // strip both & and section codes (for width/sort); && collapses to &. null-safe.
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
