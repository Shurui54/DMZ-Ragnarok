package net.shurui.dev.sdu.saga;

import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Path;

/**
 * Path-traversal defenses for the saga / side-quest file managers, which resolve file paths from
 * player-supplied strings (saga id, quest folder, side-quest relative path). These strings arrive over
 * the network in editor bundles, so they are untrusted input: without validation a crafted id like
 * {@code "../../../server"} could make a save/delete escape the world's {@code dragonminez/} data folder
 * and create, overwrite, or delete arbitrary files.
 *
 * <p>Two layers, both applied on every read/write/delete:</p>
 * <ol>
 *     <li><b>Whitelist the input.</b> Legitimate saga ids / quest folders / side-quest categories and
 *     file basenames are {@code [a-zA-Z0-9_-]} (observed live data uses lowercase {@code [a-z0-9_]};
 *     uppercase and {@code -} are allowed as a superset). Side-quest relative paths are
 *     {@code <category>/<basename>.json}: {@code /}-joined safe segments ending in {@code .json}.
 *     Anything containing {@code ..}, {@code \}, a null byte, a leading dot, an empty segment, or a
 *     drive/absolute prefix is rejected outright (never silently renamed, so an illegal id surfaces
 *     loudly in testing rather than corrupting content).</li>
 *     <li><b>Re-check the resolved Path</b> against the base directory with {@link #resolveChecked}
 *     (normalize + {@code startsWith}) before any file operation, belt-and-braces even if the
 *     whitelist ever misses something.</li>
 * </ol>
 */
public final class SafeFileNames {

    /** A single filename segment: no path separators, no dots, ASCII word chars and hyphen only. */
    private static final java.util.regex.Pattern SEGMENT = java.util.regex.Pattern.compile("[a-zA-Z0-9_-]+");

    private SafeFileNames() {
    }

    /** True if a string is a single safe filename segment ({@code [a-zA-Z0-9_-]+}, non-empty). */
    public static boolean isSafeSegment(String s) {
        return s != null && !s.isEmpty() && s.indexOf('\0') < 0 && SEGMENT.matcher(s).matches();
    }

    /**
     * Validate a saga id / quest folder / side-quest category (a single path segment). Returns the value
     * unchanged if safe; throws {@link IllegalArgumentException} (naming the offending input) otherwise.
     * Never renames; a mismatch is a hard error so it can't silently corrupt or escape.
     */
    public static String requireSafeSegment(String raw, String what) {
        if (!isSafeSegment(raw)) {
            throw new IllegalArgumentException("Illegal " + what + " '" + raw + "' (must be " + "[a-zA-Z0-9_-])");
        }
        return raw;
    }

    /** True if a string is a safe side-quest relative path (see {@link #requireSafeRelJson}). */
    public static boolean isSafeRelJson(String raw) {
        try {
            requireSafeRelJson(raw, "");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validate a side-quest relative path ({@code <segment>/.../<basename>.json}): each {@code /}-separated
     * segment must be a safe segment, and the final segment must be {@code <safe>.json}. Rejects
     * {@code ..}, {@code \}, absolute/drive prefixes, null bytes, empty segments and leading dots (the
     * safe-segment rule already forbids a leading dot). Returns the path unchanged if safe; throws otherwise.
     */
    public static String requireSafeRelJson(String raw, String what) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0
                || raw.indexOf('\\') >= 0 || raw.startsWith("/")) {
            throw new IllegalArgumentException("Illegal " + what + " '" + raw + "'");
        }
        String[] parts = raw.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            boolean last = i == parts.length - 1;
            if (last) {
                if (!p.endsWith(".json")) {
                    throw new IllegalArgumentException("Illegal " + what + " '" + raw + "' (must end in .json)");
                }
                if (!isSafeSegment(p.substring(0, p.length() - ".json".length()))) {
                    throw new IllegalArgumentException("Illegal " + what + " '" + raw + "'");
                }
            } else if (!isSafeSegment(p)) {
                throw new IllegalArgumentException("Illegal " + what + " '" + raw + "'");
            }
        }
        return raw;
    }

    /**
     * Belt-and-braces containment check: resolve {@code relative} against {@code base}, then verify the
     * normalized absolute result stays inside the normalized absolute base. Throws
     * {@link IllegalArgumentException} (logging a warning naming the input) if it would escape. Use before
     * every read, write, or delete.
     */
    public static Path resolveChecked(Path base, String relative) {
        Path baseAbs = base.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(baseAbs)) {
            DmzNpc.LOGGER.warn("[{}] Rejected path-escape attempt: input '{}' resolved outside '{}'",
                    DmzNpc.MODID, relative, baseAbs);
            throw new IllegalArgumentException("Path escapes data directory: '" + relative + "'");
        }
        return resolved;
    }
}
