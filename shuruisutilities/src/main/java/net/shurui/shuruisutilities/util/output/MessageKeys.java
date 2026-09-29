package net.shurui.shuruisutilities.util.output;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Single source of truth for turning an English chat template into a deterministic
 * lang key. Both the runtime path in {@link ChatOutputHandler} and the offline lang
 * generator ({@code MessageKeyGenerator}) call {@link #keyFor(String)}, so the two can
 * never drift: the same English string always yields the same key.
 *
 * <p>Keys look like {@code message.shuruisutilities.<slug>_<hash>}. The slug is a
 * human-readable, lowercase, [a-z0-9_] rendering of the English text with format
 * placeholders removed; the hash is a stable CRC32 of the full original English string,
 * which both keeps the mapping 1:1 (two different strings that slug identically still get
 * distinct keys) and makes the key reproducible across runs and machines.
 */
public final class MessageKeys
{
    /** Prefix for every generated chat key. */
    public static final String PREFIX = "message.dmz_ragnarok.core.";

    /** Maximum length of the human-readable slug portion (before the hash suffix). */
    private static final int MAX_SLUG_LENGTH = 48;

    // Matches Java/Minecraft format placeholders so they can be stripped from the slug:
    // %s, %d, %.1f, %1$s, %2$s, %% ... anything of the form % [argindex$] [flags/width/prec] conv.
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");

    // Any run of characters that is not an allowed slug char collapses to a single underscore.
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private MessageKeys()
    {
    }

    /**
     * Deterministically derive the full lang key for an English chat template.
     * Stable across runs; different English strings never collide (hash disambiguation).
     */
    public static String keyFor(String english)
    {
        String slug = slug(english);
        String hash = hash8(english);
        if (slug.isEmpty())
        {
            return PREFIX + "msg_" + hash;
        }
        return PREFIX + slug + "_" + hash;
    }

    /** The human-readable slug portion only (no prefix, no hash). Placeholders removed. */
    static String slug(String english)
    {
        String noPlaceholders = PLACEHOLDER.matcher(english).replaceAll(" ");
        String lower = noPlaceholders.toLowerCase(java.util.Locale.ROOT);
        String collapsed = NON_SLUG.matcher(lower).replaceAll("_");
        // trim leading/trailing underscores
        int start = 0;
        int end = collapsed.length();
        while (start < end && collapsed.charAt(start) == '_')
            start++;
        while (end > start && collapsed.charAt(end - 1) == '_')
            end--;
        String trimmed = collapsed.substring(start, end);
        if (trimmed.length() > MAX_SLUG_LENGTH)
        {
            trimmed = trimmed.substring(0, MAX_SLUG_LENGTH);
            // re-trim in case the cut landed on an underscore
            int e = trimmed.length();
            while (e > 0 && trimmed.charAt(e - 1) == '_')
                e--;
            trimmed = trimmed.substring(0, e);
        }
        return trimmed;
    }

    /** Stable 8-hex-digit CRC32 of the full original English string. */
    static String hash8(String english)
    {
        CRC32 crc = new CRC32();
        crc.update(english.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    /**
     * Make a string safe to hand to Minecraft's {@code TranslatableContents} as a template or
     * fallback. That formatter only accepts {@code %s}, {@code %n$s} and {@code %%}; ANY other
     * {@code %} (a stray display percent like "50% of memory", or a leftover {@code %d}) makes it
     * throw at render time. So every {@code %} that is not the start of a valid {@code %s} /
     * {@code %n$s} token is doubled to {@code %%}, which renders as a literal {@code %}.
     *
     * <p>Applied identically to the runtime fallback and to the generated lang values, so what the
     * generator writes and what the runtime emits stay byte-identical.
     */
    public static String escapeFormat(String s)
    {
        if (s == null || s.indexOf('%') < 0)
            return s;
        StringBuilder sb = new StringBuilder(s.length() + 4);
        int i = 0;
        int len = s.length();
        while (i < len)
        {
            char c = s.charAt(i);
            if (c != '%')
            {
                sb.append(c);
                i++;
                continue;
            }
            // Look for a valid %s or %n$s token starting here.
            int j = i + 1;
            // optional positional index n$
            int digitsStart = j;
            while (j < len && Character.isDigit(s.charAt(j)))
                j++;
            boolean positional = j > digitsStart && j < len && s.charAt(j) == '$';
            if (positional)
                j++;
            if (j < len && s.charAt(j) == 's' && (positional || j == i + 1))
            {
                // valid %s or %n$s: copy verbatim
                sb.append(s, i, j + 1);
                i = j + 1;
            }
            else if (i + 1 < len && s.charAt(i + 1) == '%')
            {
                // already an escaped %%: keep as-is
                sb.append("%%");
                i += 2;
            }
            else
            {
                // stray % (display char or unsupported specifier): escape it
                sb.append("%%");
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * True if this string carries manual colour codes ({@code &} legacy codes or a literal
     * {@code §}). Such strings stay on the old server-side {@code formatColors} literal
     * path instead of the translatable path, because the client-side lang resolution bypasses
     * the {@code &}-to-{@code §} promotion and would render the raw {@code &} code.
     */
    public static boolean hasColorCodes(String english)
    {
        if (english == null)
            return false;
        if (english.indexOf('§') >= 0)
            return true;
        int len = english.length();
        for (int i = 0; i + 1 < len; i++)
        {
            if (english.charAt(i) == '&')
            {
                char next = english.charAt(i + 1);
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(next) >= 0)
                    return true;
            }
        }
        return false;
    }
}
