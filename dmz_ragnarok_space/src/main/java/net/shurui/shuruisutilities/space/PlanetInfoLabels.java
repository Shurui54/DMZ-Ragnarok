package net.shurui.shuruisutilities.space;

import java.util.Locale;
import java.util.Set;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Maps a {@link PlanetInfoView}'s raw enum names (surface theme, garrison family) to a LANG KEY. Side-agnostic so both
 * the client overlay ({@code PlanetInfoOverlay}) and the server {@code /planet look} readout ({@link PlanetInfoServer})
 * resolve the same phrases from the same known-sets.
 *
 * <p>Returns a key STRING only, never resolves it, so it is safe on either side (each side wraps it in a
 * {@code Component} the receiver translates in ITS language). An enum outside the known set degrades to the shared
 * "unknown"/"none" phrase, so a partly-resolved planet never leaks a raw id like {@code STONY} or {@code SAIBAMEN}.
 */
public final class PlanetInfoLabels
{
    private PlanetInfoLabels()
    {
    }

    // Key roots, unchanged from the old screen so existing lang entries keep working.
    private static final String ROOT = "gui.dmz_ragnarok.core.planetinfo.";
    private static final String ENV_ROOT = ROOT + "env.";
    private static final String FAMILY_ROOT = ROOT + "family.";

    public static final String UNKNOWN_KEY = ROOT + "unknown";
    private static final String FAMILY_NONE_KEY = FAMILY_ROOT + "none";

    // Enum names this build has a phrase for; anything else degrades to the unknown/none key.
    private static final Set<String> KNOWN_THEMES =
            Set.of("stony", "overworld", "namek", "nether", "end", "kaio");
    private static final Set<String> KNOWN_FAMILIES =
            Set.of("saiyan", "overworld", "namekian", "saibamen", "frost_demon", "robot");

    /** Lang key for a surface-theme name, or {@link #UNKNOWN_KEY} for an empty or unrecognised value. */
    public static String envKey(String theme)
    {
        String key = theme == null ? "" : theme.toLowerCase(Locale.ROOT);
        if (KNOWN_THEMES.contains(key))
        {
            return ENV_ROOT + key;
        }
        return UNKNOWN_KEY;
    }

    /** Lang key for a garrison-family name, or the "none" family key for an empty or unrecognised value. */
    public static String familyKey(String family)
    {
        String key = family == null ? "" : family.toLowerCase(Locale.ROOT);
        if (KNOWN_FAMILIES.contains(key))
        {
            return FAMILY_ROOT + key;
        }
        return FAMILY_NONE_KEY;
    }
}
