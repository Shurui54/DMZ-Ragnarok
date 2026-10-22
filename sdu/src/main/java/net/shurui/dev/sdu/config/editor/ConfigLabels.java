package net.shurui.dev.sdu.config.editor;

import java.util.Locale;

/** Turns config keys into readable labels, and spots keys that must never reach the editor. */
public final class ConfigLabels {

    private ConfigLabels() {
    }

    /**
     * Key fragments that mark a credential. Any option whose path matches is dropped before it is described or saved,
     * whatever a source's own rules say, so a secret added to some file tomorrow still never crosses the wire.
     */
    private static final String[] SECRET_MARKS = {"password", "passwd", "secret", "token", "apikey", "api_key",
            "webhook", "jdbc", "credential", "username"};

    public static boolean looksSecret(String path) {
        String p = path.toLowerCase(Locale.ROOT).replace(".", "").replace("-", "");
        for (String m : SECRET_MARKS) {
            if (p.contains(m.replace("_", "")) || p.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** "zsoul.slotCount" to "Slot Count", "speed_1" to "Speed 1", "PrestigeMaxLevel" to "Prestige Max Level". */
    public static String humanize(String path) {
        String last = path;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) {
            last = last.substring(dot + 1);
        }
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < last.length(); i++) {
            char c = last.charAt(i);
            if (c == '_' || c == '-' || c == ' ') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                prev = ' ';
                continue;
            }
            boolean boundary = i > 0 && prev != ' ' && ((Character.isUpperCase(c) && !Character.isUpperCase(prev))
                    || (Character.isDigit(c) && !Character.isDigit(prev))
                    || (Character.isUpperCase(c) && i + 1 < last.length() && Character.isLowerCase(last.charAt(i + 1))
                    && Character.isUpperCase(prev)));
            if (boundary && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            sb.append(sb.length() == 0 || sb.charAt(sb.length() - 1) == ' ' ? Character.toUpperCase(c) : c);
            prev = c;
        }
        return sb.toString().trim();
    }
}
