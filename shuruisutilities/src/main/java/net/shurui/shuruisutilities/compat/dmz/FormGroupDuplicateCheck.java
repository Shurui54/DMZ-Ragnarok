package net.shurui.shuruisutilities.compat.dmz;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Startup report of duplicate DragonMineZ form groups. Read only: it never changes a file, it only names the ones
 * staff should look at.
 *
 * <p>Two shapes, both seen on the live shards (a {@code godforms_copy.json} under saiyan, a
 * {@code supersaiyan_copy.json} under half_saiyan):
 * <ul>
 *   <li><b>Two files declaring the SAME {@code groupName}.</b> DMZ keys a race's groups by lower cased groupName while
 *       listing the folder, so only one of the two survives, whichever the file system happens to list last. An edit
 *       to the other file then never takes effect, which looks exactly like a config change reverting.</li>
 *   <li><b>Every form of one group repeated in another group of the same race</b> (an editor copy of a group,
 *       renamed {@code <group>_copy}).
 *       Both groups load, so the race shows every form twice.</li>
 * </ul>
 * Same file filter as DMZ's own loader: {@code *.json}, skipping the {@code old_*} backups it writes. Plain JSON
 * reading, no DMZ types, so it is safe with or without DMZ and the key. Never throws.
 */
public final class FormGroupDuplicateCheck
{
    private FormGroupDuplicateCheck() {}

    public static void warnOnServerStart()
    {
        try
        {
            Path dmz = FMLPaths.CONFIGDIR.get().resolve("dragonminez");
            Path races = dmz.resolve("races");
            int findings = 0;
            if (Files.isDirectory(races))
            {
                try (Stream<Path> list = Files.list(races))
                {
                    for (Path race : (Iterable<Path>) list.sorted()::iterator)
                    {
                        if (Files.isDirectory(race))
                        {
                            findings += check("races/" + race.getFileName() + "/forms", race.resolve("forms"));
                        }
                    }
                }
            }
            findings += check("forms", dmz.resolve("forms"));
            if (findings > 0)
            {
                LoggingHandler.sulog.warn("[forms] {} duplicate form group finding(s) above. Keep one file per group:"
                        + " move the unwanted file out of config/dragonminez on EVERY shard (the shard config sync copies"
                        + " files but never deletes them), then restart.", findings);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[forms] Duplicate form group check skipped: {}", t.toString());
        }
    }

    /** One parsed group file: its name, lower cased groupName and lower cased form ids. */
    private record GroupFile(String file, String group, java.util.Set<String> forms) {}

    /** Check one forms folder. Returns the number of findings logged. */
    private static int check(String label, Path formsDir) throws Exception
    {
        if (!Files.isDirectory(formsDir))
        {
            return 0;
        }
        List<GroupFile> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(formsDir))
        {
            for (Path file : (Iterable<Path>) list.sorted()::iterator)
            {
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!Files.isRegularFile(file) || !lower.endsWith(".json") || lower.startsWith("old_"))
                {
                    continue;
                }
                JsonObject root = read(file);
                if (root == null || !root.has("groupName") || !root.get("groupName").isJsonPrimitive())
                {
                    continue;
                }
                java.util.Set<String> forms = new java.util.TreeSet<>();
                JsonElement f = root.get("forms");
                if (f != null && f.isJsonObject())
                {
                    for (String form : f.getAsJsonObject().keySet())
                    {
                        forms.add(form.toLowerCase(Locale.ROOT));
                    }
                }
                files.add(new GroupFile(name, root.get("groupName").getAsString().toLowerCase(Locale.ROOT), forms));
            }
        }
        int findings = 0;
        Map<String, List<String>> byGroup = new TreeMap<>();
        for (GroupFile g : files)
        {
            byGroup.computeIfAbsent(g.group(), k -> new ArrayList<>()).add(g.file());
        }
        for (Map.Entry<String, List<String>> e : byGroup.entrySet())
        {
            if (e.getValue().size() > 1)
            {
                findings++;
                LoggingHandler.sulog.warn("[forms] config/dragonminez/{}: form group '{}' is declared by {} files {}."
                        + " DMZ loads only one of them, so edits to the others do not take.",
                        label, e.getKey(), e.getValue().size(), e.getValue());
            }
        }
        // A copied group: every form of one group repeated in another group of the same race. A single shared form is
        // NOT reported, DMZ's own saiyan ladders share supersaiyan4 between oozaru and supersaiyan.
        for (int i = 0; i < files.size(); i++)
        {
            for (int j = 0; j < files.size(); j++)
            {
                GroupFile a = files.get(i);
                GroupFile b = files.get(j);
                if (i == j || a.group().equals(b.group()) || a.forms().isEmpty() || !b.forms().containsAll(a.forms()))
                {
                    continue;
                }
                if (a.forms().size() == b.forms().size() && a.file().compareTo(b.file()) < 0)
                {
                    continue;   // identical sets: report the pair once, naming the longer name (the _copy) as the copy
                }
                findings++;
                LoggingHandler.sulog.warn("[forms] config/dragonminez/{}: group '{}' ({}) repeats every form of group"
                        + " '{}' ({}): {}. Both groups load, so players see those forms twice.",
                        label, a.group(), a.file(), b.group(), b.file(), a.forms());
            }
        }
        return findings;
    }

    private static JsonObject read(Path file)
    {
        try
        {
            JsonElement el = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        }
        catch (Throwable t)
        {
            return null;   // a malformed file is DMZ's to report; this check only reads what parses
        }
    }
}
