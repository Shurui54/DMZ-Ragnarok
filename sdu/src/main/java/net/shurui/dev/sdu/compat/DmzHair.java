package net.shurui.dev.sdu.compat;

import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.hair.HairManager;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Bridge to DMZ's hair-code system. DMZ encodes a custom hairstyle as a shareable string via
 * {@link HairManager#toCode}/{@link HairManager#fromCode}. Validates such a code so the {@code DMZ NPC} tab
 * can accept one and store it on the NPC (see {@link net.shurui.dev.sdu.compat.cnpc.SduHairHolder}).
 *
 * <p>DMZ is a hard dep so {@code HairManager} is always present; still try/caught so a malformed code or a
 * DMZ change degrades to "invalid".
 */
public final class DmzHair {

    private DmzHair() {
    }

    /** True if {@code code} decodes to a non-empty DMZ hairstyle. Blank/garbage returns false. */
    public static boolean isValidCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        try {
            CustomHair hair = HairManager.fromCode(code.trim());
            return hair != null && !hair.isEmpty();
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Invalid DMZ hair code '{}': {}", DmzNpc.MODID, code, t.toString());
            return false;
        }
    }

    /** Decode a hair code into a {@link CustomHair}, or {@code null} if invalid. */
    public static CustomHair decode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return HairManager.fromCode(code.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The hair's global colour as {@code float[]{r,g,b}} in 0..1 for {@code HairRenderer.render}'s fallback
     * colour (used by strands without a per-strand colour). Parses {@code CustomHair#getGlobalColor} as a hex
     * string ({@code "#RRGGBB"} or {@code "RRGGBB"}); falls back to dark brown if absent/unparseable.
     */
    public static float[] globalRgb(CustomHair hair) {
        float[] fallback = {0.15f, 0.1f, 0.08f};
        if (hair == null) {
            return fallback;
        }
        try {
            String c = hair.getGlobalColor();
            if (c == null || c.isBlank()) {
                return fallback;
            }
            c = c.trim();
            if (c.startsWith("#")) {
                c = c.substring(1);
            }
            int rgb = (int) Long.parseLong(c, 16);
            return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Parse a hex colour string ({@code "#RRGGBB"} or {@code "RRGGBB"}) to {@code float[]{r,g,b}} 0..1, or
     * {@code null} if blank/unparseable. Used by the tab's colour picker to override the hair's own colours.
     */
    public static float[] parseHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            String c = hex.trim();
            if (c.startsWith("#")) {
                c = c.substring(1);
            }
            if (c.length() != 6) {
                return null;
            }
            int rgb = (int) Long.parseLong(c, 16);
            return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        } catch (Throwable t) {
            return null;
        }
    }
}
