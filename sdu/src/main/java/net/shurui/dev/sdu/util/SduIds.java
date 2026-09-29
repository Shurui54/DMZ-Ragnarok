package net.shurui.dev.sdu.util;

import java.util.Locale;

/** Shared id/name sanitiser used across sagas, forms, races and wishes (lowercase, {@code [a-z0-9_]}). */
public final class SduIds {

    private SduIds() {
    }

    public static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_|_$", "");
        return s.isEmpty() ? "npc" : s;
    }
}
