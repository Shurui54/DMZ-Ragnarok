package net.shurui.shuruisutilities.compat.dmz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Remembers which bundled races and ritual forms an admin has DELETED, so they stay deleted.
 *
 * <p>SU ships race folders (shadow dragon and its six star sub-races, half saiyan) and two ritual form files, and
 * writes any that are missing from DMZ's config tree on every server start. That "write it if absent" rule is what
 * makes a fresh install work, and it is also why deleting one never stuck: the admin removed the folder, the server
 * restarted, and SU put it straight back. There was no way to tell "this was never installed" from "this was
 * installed and then removed", because the only record that it had ever been written lived INSIDE the folder that
 * was deleted.
 *
 * <p>So the record is kept outside it. Two sets, in
 * {@code config/shuruisutilities/bundle_state.json}:
 * <ul>
 *   <li><b>installed</b>: every id SU has ever written. Written the first time a piece is laid down.</li>
 *   <li><b>deleted</b>: the tombstones. An id that is in {@code installed}, and whose files are now gone, was
 *       deleted by hand; it is tombstoned once and skipped from then on.</li>
 * </ul>
 *
 * <p>Nothing here is a one-way door. Re-creating the folder clears its tombstone (SU sees it present and takes that
 * as the answer), and a forced refresh clears the tombstones outright, so an admin who changes their mind gets the
 * content back with one command rather than by editing a file they would have to be told about.
 *
 * <p>Ids are namespaced by kind ({@code race:shadow_dragon}, {@code form:saiyan/godforms.json}) so the two families
 * cannot collide.
 */
public final class BundleTombstones
{
    private static final Set<String> INSTALLED = new LinkedHashSet<>();
    private static final Set<String> DELETED = new LinkedHashSet<>();
    private static boolean loaded;

    private BundleTombstones() {}

    private static Path file()
    {
        return FMLPaths.CONFIGDIR.get().resolve("shuruisutilities").resolve("bundle_state.json");
    }

    /** Id for a bundled race folder. */
    public static String raceId(String race)
    {
        return "race:" + (race == null ? "" : race.toLowerCase(Locale.ROOT));
    }

    /** Id for a bundled ritual form file. */
    public static String formId(String race, String fileName)
    {
        return "form:" + (race == null ? "" : race.toLowerCase(Locale.ROOT)) + "/" + fileName;
    }

    private static synchronized void load()
    {
        if (loaded)
        {
            return;
        }
        loaded = true;
        Path p = file();
        if (!Files.isRegularFile(p))
        {
            return;
        }
        try
        {
            JsonElement root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            if (!root.isJsonObject())
            {
                return;
            }
            readInto(root.getAsJsonObject(), "installed", INSTALLED);
            readInto(root.getAsJsonObject(), "deleted", DELETED);
        }
        catch (Throwable t)
        {
            // A corrupt state file must not stop a server booting. Starting from empty means at worst one piece of
            // bundled content is written back once, which is the old behaviour, not a crash.
            LoggingHandler.sulog.warn("[bundle] Could not read {}: {}", p, t.toString());
        }
    }

    private static void readInto(JsonObject o, String key, Set<String> into)
    {
        if (!o.has(key) || !o.get(key).isJsonArray())
        {
            return;
        }
        JsonArray arr = o.getAsJsonArray(key);
        for (JsonElement e : arr)
        {
            if (e != null && e.isJsonPrimitive())
            {
                into.add(e.getAsString());
            }
        }
    }

    private static synchronized void save()
    {
        Path p = file();
        try
        {
            Files.createDirectories(p.getParent());
            JsonObject o = new JsonObject();
            o.add("installed", toArray(INSTALLED));
            o.add("deleted", toArray(DELETED));
            Files.writeString(p, o.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("[bundle] Could not write {}: {}", p, e.toString());
        }
    }

    private static JsonArray toArray(Set<String> set)
    {
        JsonArray a = new JsonArray();
        for (String s : set)
        {
            a.add(s);
        }
        return a;
    }

    /**
     * Decide what to do about a bundled piece that is NOT on disk.
     *
     * @return true when it should be written (never installed before), false when it was deleted on purpose
     */
    public static synchronized boolean shouldWriteMissing(String id, String humanName)
    {
        load();
        if (DELETED.contains(id))
        {
            return false;
        }
        if (INSTALLED.contains(id))
        {
            // Installed once, gone now: somebody removed it. Record that and stop putting it back.
            DELETED.add(id);
            save();
            LoggingHandler.sulog.info("[bundle] {} was deleted, so it will not be written again. "
                    + "Use the bundle refresh command to restore it.", humanName);
            return false;
        }
        return true;
    }

    /** Record that a piece is present on disk: it is installed, and any tombstone on it is stale. */
    public static synchronized void markPresent(String id)
    {
        load();
        boolean changed = INSTALLED.add(id);
        changed |= DELETED.remove(id);
        if (changed)
        {
            save();
        }
    }

    /** Forget every tombstone, so the next extraction writes back whatever is missing. Used by a forced refresh. */
    public static synchronized int clearTombstones()
    {
        load();
        int n = DELETED.size();
        if (n > 0)
        {
            DELETED.clear();
            save();
        }
        return n;
    }
}
