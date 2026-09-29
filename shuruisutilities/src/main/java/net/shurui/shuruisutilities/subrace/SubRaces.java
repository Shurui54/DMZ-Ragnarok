package net.shurui.shuruisutilities.subrace;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Ordered parent-race to sub-race registry. A sub-race is an ordinary, complete DMZ race (its own folder in
 * {@code config/dragonminez/races/<id>/}) that is hidden from DMZ's own race carousel and surfaced by SU's sub-race
 * screen (a later batch) instead. This class only holds the parent to sub-race mapping; the race folders themselves are
 * shipped and extracted by {@link net.shurui.shuruisutilities.compat.dmz.RaceBundleExtractor}, and unlock state lives in
 * {@link net.shurui.shuruisutilities.corrupted.RaceUnlocks}.
 *
 * <p><b>Ordering matters.</b> The per-parent sub-race list is kept in insertion order (a {@link LinkedHashMap} of
 * {@link java.util.LinkedHashSet}s) because a later batch cycles sub-race models in exactly this order. The shadow
 * dragon rungs are therefore listed 2star..7star.
 *
 * <p><b>Config-backed.</b> Backed by {@code config/shuruisutilities/subraces.json}, mirroring the Gson pattern SU uses
 * for {@link net.shurui.shuruisutilities.client.hud.RegionHudConfig}: on first load, if the file is absent it is written
 * with the built-in defaults so an admin can add sub-races (a new parent, or new rungs) without a code change. A present
 * file is authoritative and is never overwritten. The file shape is a single JSON object mapping each lowercase parent
 * id to an ordered array of lowercase sub-race ids.
 *
 * <p>All lookups lowercase their arguments with {@link Locale#ROOT}, matching how DMZ's {@code Character.setRace}
 * lowercases race names, so callers never have to normalise case themselves.
 */
public final class SubRaces
{
    private SubRaces() {}

    /** Config file: {@code config/shuruisutilities/subraces.json}. */
    private static final String DIR = "shuruisutilities";
    private static final String FILE = "subraces.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // parent id (lowercase) -> ordered set of sub-race ids (lowercase). Insertion order is the model-cycle order.
    private static volatile Map<String, LinkedHashSet<String>> parentToSubs;
    // reverse index: sub-race id (lowercase) -> parent id (lowercase). Rebuilt whenever the forward map is (re)loaded.
    private static volatile Map<String, String> subToParent;

    /** The built-in default mapping, written to disk when no config file exists yet. Insertion order is significant. */
    private static Map<String, List<String>> defaults()
    {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("shadow_dragon", List.of(
                "shadow_dragon_2star",
                "shadow_dragon_3star",
                "shadow_dragon_4star",
                "shadow_dragon_5star",
                "shadow_dragon_6star",
                "shadow_dragon_7star"));
        m.put("saiyan", List.of("half_saiyan"));
        return m;
    }

    private static Path file()
    {
        return FMLPaths.CONFIGDIR.get().resolve(DIR).resolve(FILE);
    }

    /**
     * Ensure the registry is loaded. First call reads {@code subraces.json}, writing defaults if absent. Idempotent and
     * cheap after the first successful load; safe to call from any lookup. Never throws: on any failure it falls back to
     * the in-memory defaults so lookups keep working.
     */
    private static synchronized void ensureLoaded()
    {
        if (parentToSubs != null)
            return;
        Map<String, List<String>> raw;
        try
        {
            Path path = file();
            if (Files.exists(path))
            {
                try (Reader in = Files.newBufferedReader(path))
                {
                    raw = GSON.fromJson(in, new TypeToken<LinkedHashMap<String, List<String>>>() {}.getType());
                }
                if (raw == null)
                    raw = defaults();
            }
            else
            {
                raw = defaults();
                writeDefaults(path, raw);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[SubRaces] Failed to load subraces.json; using built-in defaults.", t);
            raw = defaults();
        }
        index(raw);
    }

    private static void writeDefaults(Path path, Map<String, List<String>> raw)
    {
        try
        {
            if (path.getParent() != null)
                Files.createDirectories(path.getParent());
            try (Writer out = Files.newBufferedWriter(path))
            {
                GSON.toJson(raw, out);
            }
            LoggingHandler.sulog.info("[SubRaces] Wrote default subraces.json.");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[SubRaces] Failed to write default subraces.json; continuing in memory.", t);
        }
    }

    // Build the forward and reverse maps from a raw parent -> list mapping, lowercasing every id so lookups are
    // case-insensitive. Blank/null ids are dropped. The forward map preserves parent and rung insertion order.
    private static void index(Map<String, List<String>> raw)
    {
        Map<String, LinkedHashSet<String>> forward = new LinkedHashMap<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        if (raw != null)
        {
            for (Map.Entry<String, List<String>> e : raw.entrySet())
            {
                if (e.getKey() == null || e.getValue() == null)
                    continue;
                String parent = e.getKey().toLowerCase(Locale.ROOT).trim();
                if (parent.isEmpty())
                    continue;
                LinkedHashSet<String> subs = forward.computeIfAbsent(parent, k -> new LinkedHashSet<>());
                for (String sub : e.getValue())
                {
                    if (sub == null)
                        continue;
                    String id = sub.toLowerCase(Locale.ROOT).trim();
                    if (id.isEmpty())
                        continue;
                    subs.add(id);
                    reverse.put(id, parent);
                }
            }
        }
        parentToSubs = forward;
        subToParent = reverse;
    }

    /** Force a re-read from disk on next access (e.g. after an admin edits the file). Cheap; next lookup reloads. */
    public static synchronized void invalidate()
    {
        parentToSubs = null;
        subToParent = null;
    }

    /**
     * The ordered sub-race ids for a parent race id, or an empty list when the parent has none. The order is the
     * model-cycle order. Case-insensitive on {@code parentId}. Never returns null.
     */
    public static List<String> subRacesOf(String parentId)
    {
        ensureLoaded();
        if (parentId == null)
            return Collections.emptyList();
        LinkedHashSet<String> subs = parentToSubs.get(parentId.toLowerCase(Locale.ROOT));
        return subs == null ? Collections.emptyList() : new ArrayList<>(subs);
    }

    /** The parent race id for a sub-race id, or null when {@code subRaceId} is not a registered sub-race. */
    public static String parentOf(String subRaceId)
    {
        ensureLoaded();
        if (subRaceId == null)
            return null;
        return subToParent.get(subRaceId.toLowerCase(Locale.ROOT));
    }

    /** True when {@code id} is a registered sub-race. Case-insensitive. */
    public static boolean isSubRace(String id)
    {
        return parentOf(id) != null;
    }

    /** Every registered sub-race id (lowercase), across all parents. Never null. */
    public static Set<String> allSubRaceIds()
    {
        ensureLoaded();
        return new LinkedHashSet<>(subToParent.keySet());
    }
}
