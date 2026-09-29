package net.shurui.shuruisutilities.cosmetics.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;

/**
 * The legal values for the id-string appearance channels (hair state, hair code, model, extra layer, aura), built by
 * reading what DragonMineZ itself has loaded.
 *
 * <p>This is the thing that was missing before these channels could be offered at all. They are not colours: they
 * name an asset, and a name DMZ cannot resolve does not look wrong, it breaks the model. Deriving the option lists
 * from DMZ's own form configs means every value a player can pick is by construction a value DMZ ships, so a typo,
 * a stale id from an old pack, or a spoofed packet cannot put an unresolvable string on a model. It also picks up an
 * operator's custom forms for free, because those are in the same config the lists are read from.
 *
 * <p>Values are collected across EVERY race and the stack forms, not just the player's own race. Mixing another
 * race's hair or aura onto your form is the point of the perk ("customise any form you want visually"), and it is
 * safe precisely because the value still came from DMZ.
 *
 * <p>Rebuilt on demand and cached against DMZ's loaded-race list, so a config reload that adds forms is picked up
 * without a restart. Server side: the client never decides what is valid, it only renders the list it is sent, and
 * {@link FormCosmeticManager} re-checks every value on save.
 */
public final class FormAppearanceOptions
{
    private FormAppearanceOptions() {}

    /**
     * Separator used when the option lists are flattened into the editor's meta strings. ASCII unit separator: it
     * cannot occur in a DMZ asset id, so no value can smuggle a list boundary into itself.
     */
    public static final String SEP = "\u001F";

    /** Which id-string channel a value belongs to. */
    public enum Kind
    {
        HAIR_TYPE(FormConfig.FormData::getHairType),
        HAIR_CODE(FormConfig.FormData::getForcedHairCode),
        MODEL(FormConfig.FormData::getCustomModel),
        EXTRA_LAYER(FormConfig.FormData::getExtraFormLayer),
        AURA_TYPE(FormConfig.FormData::getAuraType);

        private final Function<FormConfig.FormData, String> reader;

        Kind(Function<FormConfig.FormData, String> reader)
        {
            this.reader = reader;
        }

        String read(FormConfig.FormData fd)
        {
            try
            {
                return reader.apply(fd);
            }
            catch (Throwable t)
            {
                return null; // a DMZ change that drops a getter degrades to "no options", never to a crash
            }
        }
    }

    // channel -> sorted distinct values. Rebuilt wholesale; a plain volatile swap is enough (built then assigned).
    private static volatile Map<Kind, List<String>> options = Map.of();
    // what the cache was built from, so a config reload that loads more races invalidates it
    private static volatile int builtFromRaces = -1;

    /** The legal values for a channel, sorted, never null. Empty when DMZ has loaded nothing (or is absent). */
    public static List<String> values(Kind kind)
    {
        ensureBuilt();
        List<String> v = options.get(kind);
        return v == null ? List.of() : v;
    }

    /**
     * True when this value may be stored for this channel: either empty (meaning "keep the form's own value") or a
     * value DMZ actually ships. Compared case-insensitively, since the stored form configs are not consistent about
     * case and a player picking from the list should not fail on it.
     */
    public static boolean isValid(Kind kind, String value)
    {
        if (value == null || value.isBlank())
            return true;
        String needle = value.trim();
        for (String v : values(kind))
            if (v.equalsIgnoreCase(needle))
                return true;
        return false;
    }

    /** The canonical stored spelling of a value, or "" when it is not a legal value for the channel. */
    public static String canonical(Kind kind, String value)
    {
        if (value == null || value.isBlank())
            return "";
        String needle = value.trim();
        for (String v : values(kind))
            if (v.equalsIgnoreCase(needle))
                return v;
        return "";
    }

    private static void ensureBuilt()
    {
        int races;
        try
        {
            List<String> loaded = ConfigManager.getLoadedRaces();
            races = loaded == null ? 0 : loaded.size();
        }
        catch (Throwable t)
        {
            races = 0;
        }
        if (!options.isEmpty() && races == builtFromRaces)
            return;
        rebuild(races);
    }

    /** Force a rebuild, e.g. after a DMZ config reload. */
    public static synchronized void rebuild()
    {
        int races;
        try
        {
            List<String> loaded = ConfigManager.getLoadedRaces();
            races = loaded == null ? 0 : loaded.size();
        }
        catch (Throwable t)
        {
            races = 0;
        }
        rebuild(races);
    }

    private static synchronized void rebuild(int races)
    {
        Map<Kind, Set<String>> acc = new java.util.EnumMap<>(Kind.class);
        for (Kind k : Kind.values())
            acc.put(k, new TreeSet<>(String.CASE_INSENSITIVE_ORDER));

        try
        {
            Map<String, Map<String, FormConfig>> all = ConfigManager.getAllForms();
            if (all != null)
                for (Map<String, FormConfig> byGroup : all.values())
                    collectGroups(byGroup, acc);
            collectGroups(ConfigManager.getAllStackForms(), acc);
        }
        catch (Throwable t)
        {
            // DMZ absent or changed: leave whatever was collected, which may be nothing. The editor then simply
            // offers no options for these channels rather than offering something unsafe.
        }

        Map<Kind, List<String>> built = new java.util.EnumMap<>(Kind.class);
        for (Kind k : Kind.values())
            built.put(k, List.copyOf(acc.get(k)));
        options = built;
        builtFromRaces = races;
    }

    private static void collectGroups(Map<String, FormConfig> byGroup, Map<Kind, Set<String>> acc)
    {
        if (byGroup == null)
            return;
        for (FormConfig group : byGroup.values())
        {
            if (group == null || group.getForms() == null)
                continue;
            for (FormConfig.FormData fd : group.getForms().values())
            {
                if (fd == null)
                    continue;
                for (Kind k : Kind.values())
                {
                    Set<String> into = acc.get(k);
                    if (into == null)
                        continue; // caller asked for a subset of the channels
                    String v = k.read(fd);
                    if (v != null && !v.isBlank())
                        into.add(v.trim());
                }
            }
        }
    }

    /**
     * The legal values for a channel restricted to ONE race's forms.
     *
     * <p>Used for {@link Kind#MODEL}. A custom model is a whole body: wearing another race's model is not "a
     * cosmetic tweak", it makes you a different creature, so models are kept to the forms your own race actually
     * has. The other channels stay pooled across races, because a hair shape or an aura reads as styling rather
     * than as impersonating a different race.
     *
     * <p>Not cached: it is per race and only built when the editor opens, which is rare.
     */
    public static List<String> valuesForRace(Kind kind, String race)
    {
        if (race == null || race.isBlank())
            return List.of();
        Set<String> acc = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try
        {
            Map<String, FormConfig> byGroup = ConfigManager.getAllFormsForRace(race);
            if (byGroup != null)
            {
                Map<Kind, Set<String>> one = new java.util.EnumMap<>(Kind.class);
                one.put(kind, acc);
                collectGroups(byGroup, one);
            }
        }
        catch (Throwable t)
        {
            return List.of();
        }
        return List.copyOf(acc);
    }

    /** True when the value is legal for this channel within this race (empty always is). */
    public static String canonicalForRace(Kind kind, String race, String value)
    {
        if (value == null || value.isBlank())
            return "";
        String needle = value.trim();
        for (String v : valuesForRace(kind, race))
            if (v.equalsIgnoreCase(needle))
                return v;
        return "";
    }

    /** Option lists in a fixed order, for shipping to the editor. */
    public static List<List<String>> allLists(String race)
    {
        List<List<String>> out = new ArrayList<>();
        for (Kind k : Kind.values())
            out.add(k == Kind.MODEL ? valuesForRace(k, race) : values(k));
        return out;
    }

    /** Lower-cased channel name, for logs. */
    public static String label(Kind k)
    {
        return k.name().toLowerCase(Locale.ROOT);
    }
}
