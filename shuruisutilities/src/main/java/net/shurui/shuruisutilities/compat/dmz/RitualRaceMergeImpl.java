package net.shurui.shuruisutilities.compat.dmz;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.dragonminez.common.config.ConfigManager;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * DMZ-facing seed-merge for the dragon-ball ritual forms, reached only through {@link RitualRaceMerge}'s guard.
 *
 * <p>Adds the ritual form groups to DMZ's own race folders and patches the price arrays that decide reachability:
 * <ul>
 *   <li><b>saiyan / ssj5.json</b> ({@code superforms} rung 9, {@code supersaiyan5}). The {@code superforms} price array
 *       is padded to nine entries with rung 9 set to {@code -1} (granted only, client refuses purchase).</li>
 *   <li>SU's former Super Saiyan God group ({@code saiyan/godforms.json}) is retired and removed where SU seeded it;
 *       the god ritual grants DragonMineZ's own god form instead (half saiyans get DMZ's file from their bundle).</li>
 *   <li><b>namekian / primalnamekian.json</b> ({@code superforms} rung 4). The {@code superforms} price rung 4 is set to
 *       {@code -1} (granted only).</li>
 * </ul>
 * Idempotent: a form file is only written when absent or when our own form is missing a field we ship, a price is only
 * changed when it differs from the target, and DMZ is reloaded once only if something actually changed. Never throws.
 */
final class RitualRaceMergeImpl
{
    private RitualRaceMergeImpl() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** TP price of Super Saiyan God, applied only when base DMZ left godforms unpriced. Above SSJ4's stock 104000. */
    private static final int SSG_PRICE = 150_000;
    /** Saiyan superforms rung that holds SSJ5 (1-based), i.e. array index 8. */
    private static final int SSJ5_RUNG = 9;
    /** Namekian superforms rung that holds Primal Namekian (1-based), i.e. array index 3. */
    private static final int PRIMAL_NAMEKIAN_RUNG = 4;

    static void seedAndReload()
    {
        try
        {
            boolean changed = false;
            // SSJ5 goes INTO DragonMineZ's existing oozaru ladder rather than into a file of its own. SSJ4 is
            // rung 8 of that group with goldenoozaru as its requisite, so a separate group would leave SSJ5
            // hanging outside the progression it is meant to continue.
            // Both SSJ5 rungs, into both saiyan ladders. The oozaru ladder ends at SSJ4 (ssj4gt) and the
            // supersaiyan ladder ends at SSJ4 Daima (ssj4d), so each gets the rung that continues IT.
            //
            // SAIYAN ONLY, deliberately. half_saiyan used to be seeded here as well, back when it shipped the same
            // two ladders. It no longer does: a half saiyan has no tail, so no oozaru, no golden oozaru and no
            // SSJ4, and with SSJ4 gone there is nothing for either SSJ5 rung to continue. Their ladder ends at
            // Super Saiyan 3. (It briefly carried a fourth legendary rung, Beast; that was removed at bundle v8
            // and none of the reasoning here depended on it.) Seeding SSJ5 here would put it back
            // on a race whose oozaru.json no longer exists (insertOrRepairForm would no-op) and whose
            // supersaiyan.json deliberately stops at SSJ3 (it would NOT no-op, it would hand them SSJ5 Daima).
            changed |= insertOrRepairForm("saiyan", "oozaru.json", "supersaiyan5",
                    "/ritual_forms/saiyan/ssj5.json");
            changed |= insertOrRepairForm("saiyan", "supersaiyan.json", "supersaiyan5daima",
                    "/ritual_forms/saiyan/ssj5daima.json");

            // The god ritual grants DragonMineZ's OWN base god form (the godritual group). Our former Super Saiyan
            // God group (godforms / supersaiyangod) is retired: removed where SU seeded it, never written again.
            // Saiyan only: half_saiyan is a bundled race, and its bundle (v9) both drops godforms.json and ships
            // DMZ's own godritual.json, so the extractor's own upgrade does that race (deleting the file here would
            // look like an admin edit and stop the upgrade).
            changed |= retireOurGodForms("saiyan");
            changed |= copyFormIfAbsent("namekian", "primalnamekian.json", "/ritual_forms/namekian/primalnamekian.json");

            changed |= patchSaiyanPrices();
            changed |= patchNamekianPrices();

            if (changed)
            {
                ConfigManager.reload();
                LoggingHandler.sulog.info("[ritual] Seeded ritual forms into saiyan/namekian races and reloaded DragonMineZ.");
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[ritual] Failed to seed ritual forms; continuing without them.", t);
        }
    }

    /**
     * Insert one form into an EXISTING DragonMineZ form group file, or fill in the blanks of one already there.
     *
     * <p>Used where our form is a continuation of a ladder DMZ already ships rather than a new ladder.
     *
     * <p>Pure insertion was not enough. A form seeded by an EARLIER build of this mod keeps whatever that build wrote
     * and never picks up anything added to the bundled template since, because the name is present so the file is left
     * alone. That is how Super Saiyan 5 on the oozaru ladder ended up with an empty {@code forcedHairCode} and rendered
     * with the player's ordinary hair while its Daima twin, seeded later, looked right.
     *
     * <p>So an existing form is repaired rather than replaced: a key we ship is written only where the file has no
     * value for it or has a BLANK one. Anything an operator has actually set to something, including a deliberate
     * retune of our numbers, is left exactly as they set it. Only the holes get filled.
     */
    private static boolean insertOrRepairForm(String race, String groupFile, String formName, String resource)
    {
        try
        {
            Path dest = racesDir().resolve(race).resolve("forms").resolve(groupFile);
            if (!Files.exists(dest))
                return false; // DMZ has not written this group yet; nothing to extend
            JsonObject root = read(dest);
            if (root == null)
                return false;
            JsonObject forms = child(root, "forms");
            if (forms == null)
                return false;
            JsonObject template = loadForm(resource);
            if (template == null)
                return false;

            if (!forms.has(formName))
            {
                forms.add(formName, template);
                write(dest, root);
                LoggingHandler.sulog.info("[ritual] Inserted form '{}' into {} for race '{}'.", formName, groupFile, race);
                return true;
            }

            JsonObject existing = child(forms, formName);
            if (existing == null)
                return false;
            int filled = fillBlanks(existing, template);
            if (filled == 0)
                return false;
            write(dest, root);
            LoggingHandler.sulog.info("[ritual] Repaired {} blank field(s) on form '{}' in {} for race '{}'.",
                    filled, formName, groupFile, race);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ritual] Could not insert form '{}' into {} for '{}'.", formName, groupFile, race, t);
            return false;
        }
    }

    private static JsonObject loadForm(String resource)
    {
        try (InputStream in = RitualRaceMergeImpl.class.getResourceAsStream(resource))
        {
            if (in == null)
            {
                LoggingHandler.sulog.warn("[ritual] Missing bundled form resource {}", resource);
                return null;
            }
            return GSON.fromJson(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ritual] Could not read bundled form resource {}", resource, t);
            return null;
        }
    }

    // Copy across every key the template defines that the target is missing or holds blank. Returns how many were
    // written. "Blank" is json null or the empty string only: a zero, a false and an empty array are all real answers
    // somebody may have chosen, and overwriting them would be editing the operator's config rather than repairing ours.
    private static int fillBlanks(JsonObject target, JsonObject template)
    {
        int filled = 0;
        for (String key : template.keySet())
        {
            if (key.startsWith("_"))
                continue; // comment keys are ours to document with, not values to enforce
            JsonElement ours = template.get(key);
            if (isBlank(ours))
                continue;
            if (!isBlank(target.get(key)))
                continue;
            target.add(key, ours);
            filled++;
        }
        return filled;
    }

    private static boolean isBlank(JsonElement el)
    {
        if (el == null || el.isJsonNull())
            return true;
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() && el.getAsString().isEmpty();
    }

    /** The god form SU used to seed (godforms.json holding only supersaiyangod). */
    private static final String OUR_GOD_FILE = "godforms.json";
    private static final String OUR_GOD_GROUP = "godforms";
    private static final String OUR_GOD_FORM = "supersaiyangod";

    /**
     * Delete SU's retired Super Saiyan God group from a race, but only when it is still ours: group {@code godforms}
     * holding nothing but {@code supersaiyangod}. A file an admin has extended with other forms is left alone.
     * Characters still standing in the removed form are healed by the unresolved-form repair on login and tick.
     */
    private static boolean retireOurGodForms(String race)
    {
        try
        {
            Path dest = racesDir().resolve(race).resolve("forms").resolve(OUR_GOD_FILE);
            if (!Files.exists(dest))
                return false;
            JsonObject root = read(dest);
            if (root == null)
                return false;
            String group = root.has("groupName") ? root.get("groupName").getAsString() : "";
            JsonObject forms = child(root, "forms");
            if (!OUR_GOD_GROUP.equalsIgnoreCase(group) || forms == null || forms.size() != 1 || !forms.has(OUR_GOD_FORM))
            {
                LoggingHandler.sulog.info("[ritual] Left {}/{} in place: it is not SU's retired god form any more.",
                        race, OUR_GOD_FILE);
                return false;
            }
            Files.delete(dest);
            LoggingHandler.sulog.info("[ritual] Removed SU's retired Super Saiyan God form ({}/{}); the god ritual now "
                    + "grants DragonMineZ's own god form.", race, OUR_GOD_FILE);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ritual] Could not retire {}/{}.", race, OUR_GOD_FILE, t);
            return false;
        }
    }

    private static boolean copyFormIfAbsent(String race, String fileName, String resource)
    {
        try
        {
            Path raceDir = racesDir().resolve(race);
            if (!Files.isDirectory(raceDir))
                return false; // DMZ has not created this race folder; nothing to merge into
            Path dest = raceDir.resolve("forms").resolve(fileName);
            if (Files.exists(dest))
            {
                // Present: record it, and drop any stale tombstone (the admin put it back by hand).
                BundleTombstones.markPresent(BundleTombstones.formId(race, fileName));
                return false;
            }
            // Absent. Same rule as the bundled races: written once on a fresh install, never written back over a
            // deliberate deletion. Without this a deleted ritual form returned on the next restart, because "the
            // file is not there" was read as "this server has never had it".
            if (!BundleTombstones.shouldWriteMissing(BundleTombstones.formId(race, fileName),
                    "Ritual form " + fileName + " for race '" + race + "'"))
            {
                return false;
            }
            Files.createDirectories(dest.getParent());
            try (InputStream in = RitualRaceMergeImpl.class.getResourceAsStream(resource))
            {
                if (in == null)
                {
                    LoggingHandler.sulog.warn("[ritual] Missing bundled form resource {}", resource);
                    return false;
                }
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            BundleTombstones.markPresent(BundleTombstones.formId(race, fileName));
            LoggingHandler.sulog.info("[ritual] Wrote ritual form {} into race '{}'.", fileName, race);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ritual] Could not write ritual form {} for '{}'.", fileName, race, t);
            return false;
        }
    }

    private static boolean patchSaiyanPrices()
    {
        Path file = racesDir().resolve("saiyan").resolve("character.json");
        JsonObject root = read(file);
        if (root == null)
            return false;
        JsonObject costs = child(root, "formSkillsCosts");
        if (costs == null)
            return false;

        boolean changed = false;
        // superforms: pad to nine entries and force rung 9 (SSJ5) to -1 (grant-only).
        changed |= setRung(group(costs, "superforms"), SSJ5_RUNG, -1);
        // godforms: give SSG a real price only when base DMZ left it empty (respect an admin-set price).
        JsonObject god = group(costs, "godforms");
        JsonArray godPrices = ensurePrices(god);
        if (godPrices.size() == 0)
        {
            godPrices.add(new JsonPrimitive(SSG_PRICE));
            changed = true;
        }

        if (changed)
            write(file, root);
        return changed;
    }

    private static boolean patchNamekianPrices()
    {
        Path file = racesDir().resolve("namekian").resolve("character.json");
        JsonObject root = read(file);
        if (root == null)
            return false;
        JsonObject costs = child(root, "formSkillsCosts");
        if (costs == null)
            return false;

        boolean changed = setRung(group(costs, "superforms"), PRIMAL_NAMEKIAN_RUNG, -1);
        if (changed)
            write(file, root);
        return changed;
    }

    // Ensure the group's prices array is at least {@code rung} long (padding with -1) and that the rung's entry equals
    // {@code value}. Returns true when anything changed. rung is 1-based.
    private static boolean setRung(JsonObject group, int rung, int value)
    {
        if (group == null || rung < 1)
            return false;
        JsonArray prices = ensurePrices(group);
        boolean changed = false;
        while (prices.size() < rung)
        {
            prices.add(new JsonPrimitive(-1));
            changed = true;
        }
        int idx = rung - 1;
        JsonElement current = prices.get(idx);
        if (!current.isJsonPrimitive() || current.getAsInt() != value)
        {
            prices.set(idx, new JsonPrimitive(value));
            changed = true;
        }
        return changed;
    }

    private static Path racesDir()
    {
        return FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve("races");
    }

    private static JsonObject read(Path file)
    {
        try
        {
            if (!Files.isRegularFile(file))
                return null;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ritual] Could not read {}", file, t);
            return null;
        }
    }

    private static void write(Path file, JsonObject root)
    {
        try
        {
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ritual] Could not write {}", file, t);
        }
    }

    private static JsonObject child(JsonObject parent, String key)
    {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    // Get (or create) a form-cost group object under formSkillsCosts, seeded with buyFromMaster=false + empty prices.
    private static JsonObject group(JsonObject costs, String name)
    {
        JsonObject g = child(costs, name);
        if (g == null)
        {
            g = new JsonObject();
            g.addProperty("buyFromMaster", false);
            g.add("prices", new JsonArray());
            costs.add(name, g);
        }
        return g;
    }

    private static JsonArray ensurePrices(JsonObject group)
    {
        JsonElement el = group.get("prices");
        if (el == null || !el.isJsonArray())
        {
            JsonArray arr = new JsonArray();
            group.add("prices", arr);
            return arr;
        }
        return el.getAsJsonArray();
    }
}
