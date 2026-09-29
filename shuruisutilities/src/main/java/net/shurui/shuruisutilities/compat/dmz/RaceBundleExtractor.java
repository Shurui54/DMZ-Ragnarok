package net.shurui.shuruisutilities.compat.dmz;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.dragonminez.common.config.ConfigManager;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Copies SU's bundled shadow dragon race definitions out of the jar into DMZ's config tree, then reloads DMZ so the
 * races are scanned. Only reached through {@link RaceBundleCompat}'s {@code isLoaded("dragonminez")} guard, so DMZ types
 * ({@link ConfigManager}) are safe to reference here.
 *
 * <p>DMZ 2.1.3 reads playable races from {@code config/dragonminez/races/<race_id>/}, scanning that directory in
 * {@code ConfigManager.reload()} via {@code loadAllRaces()}, which does {@code Files.list(races)} and treats every
 * SUBDIRECTORY that is not one of the six default races as a playable race. Two consequences drive this class:
 * <ul>
 *   <li>A dotfile ({@code .su_bundle}) written INSIDE a race folder is ignored by DMZ: a race folder is only read by
 *       name ({@code character.json}, {@code stats.json}, {@code forms/*.json}); the folder root is never enumerated
 *       for rejection, and the only listing inside it ({@code forms/}) is filtered to {@code .json}. Verified against
 *       the decompiled {@code createOrLoadRace} / {@code loadAllRaces}.</li>
 *   <li>A {@code <race_id>.bak-*} folder placed inside {@code races/} WOULD be picked up as a broken duplicate race,
 *       because {@code loadAllRaces} loads every non-default subdirectory. So upgrade backups are written OUTSIDE the
 *       DMZ config tree entirely, under {@code config/shuruisutilities/race_backups/}, which DMZ never scans (it is
 *       neither under {@code races/} nor under {@code config/dragonminez/}).</li>
 * </ul>
 *
 * <p>Extraction is version-aware, per race folder. SU stamps each folder it writes with a {@code .su_bundle} marker
 * recording the {@link #BUNDLE_VERSION} it wrote and a content hash of exactly the files it wrote. On later startups it
 * upgrades a stale folder ONLY when the on-disk files still hash to what SU recorded (proving the admin did not edit
 * them); an edited folder, or a pre-versioning folder with no marker, is left untouched and surfaced through a log line
 * plus the {@code /rgrace refresh} command. Server-side only; wrapped so any failure logs and continues.
 */
public final class RaceBundleExtractor
{
    private RaceBundleExtractor() {}

    /**
     * SU-owned content version of the bundled races. This is OURS: bump it whenever any bundled race file changes so
     * existing installs can be offered the update. It is intentionally NOT the {@code configVersion} inside
     * character.json (that tracks DMZ's schema, currently 2.1.3, and is not ours to repurpose).
     *
     * <p>History: v1 was the original shared-model shadow dragon bundle. v2 gave each shadow dragon sub-race its own
     * customModel and single non-layered texture. v3 carries no file changes of its own; it exists to drive the upgrade
     * path on every existing install so stale hand-typed generated_lang overrides for these races are purged (see
     * {@link #purgeGeneratedLang}), letting the shipped bilingual lang show through. v4 reworks the shadow dragon
     * content: canonical per-class DEF scaling and a full per-class stat spread, superforms priced TP-purchasable on the
     * six sub-races and grant-only on the base, the base model moved to omega2 with the form using omega, isLayered set
     * false, and the previously missing forms/superforms.json for 2star and 3star. Folders that predate this versioning
     * carry no marker and are treated as {@link #UNKNOWN_VERSION}.
     *
     * <p>v5 puts the BASE race back to {@code isLayered: true}. Turning it off in v4 is what stopped the spikes and
     * the blue markings tinting: DMZ's unlayered branch loads ONE texture and multiplies the whole thing by body
     * colour 1, so colours 2 and 3 were being read from the config and then never used. The layered branch loads
     * {@code <model>_<bodyType>_layer1/2/3.png} and tints each separately, which is the only way those parts are
     * editable. {@code omega2.png} turned out to be exactly the three omegashenron layers composited (verified pixel
     * for pixel: 1770 body, 118 blue, 341 spike), so the same three layers are now shipped under the omega2 and omega
     * names and the model looks unchanged by default while becoming recolourable again.
     *
     * <p>v6 gives the shadow dragons their own racial. All seven carried {@code racialSkill: "human"}, so the
     * character screen offered them DMZ's Blood-Fueled Ki, which is not theirs and was never meant to be. They now
     * declare {@code malice}, which is the bar they already run on: the malice energy and the techniques that
     * spend it were built long ago, the racial slot simply never pointed at them. DMZ does not implement a racial
     * called malice, and that is correct here, because the racial IS the bar. half_saiyan is untouched and keeps
     * the saiyan racial (Zenkai).
     *
     * <p>v7 separates half_saiyan from saiyan rather than tracking it. It keeps the saiyan racial, the saiyan
     * customization set and the god ritual, and loses the tail: {@code hasSaiyanTail} is false, {@code
     * forms/oozaru.json} is gone entirely (oozaru, golden oozaru and the SSJ4 rung that hangs off it), SSJ4 is out
     * of {@code forms/supersaiyan.json}, and the superforms price list is truncated to six rungs so the ladder ends
     * at Super Saiyan 3. Neither SSJ5 rung is seeded for them any more (see {@code RitualRaceMergeImpl}). In their
     * place the legendary ladder is the ordinary three rungs, and {@code godforms.json} gives them the god ritual.
     *
     * <p>The ladder briefly carried a fourth rung, {@code beast}, seeded only for this race. It was removed at
     * v8 because it was not wanted, and removing it from the bundle is only half the job: the extractor SKIPS an
     * update whenever the on-disk files carry local edits, so a bundle that no longer ships a form does not take
     * that form off a server which already has it. The live copies had to be edited too. Do not re-add it here
     * expecting a bundle bump to distribute the change on its own.
     */
    static final int BUNDLE_VERSION = 8;

    /**
     * The model slot the ORIGINAL hand-dropped shadow dragon bundle used, before any of this versioning existed. SU has
     * never shipped this value in a versioned bundle, so finding it on disk identifies a pre-versioning folder exactly.
     * See {@link #repairLegacyShadowDragon()}.
     */
    private static final String LEGACY_SHADOW_DRAGON_MODEL = "oshenron";

    /** Sentinel recorded for a folder that already existed with no marker (cannot be told edited from pristine). */
    static final int UNKNOWN_VERSION = -1;

    /** Marker file written inside each race folder. A dotfile so DMZ's race scan ignores it (verified). */
    static final String MARKER_NAME = ".su_bundle";

    /** The operator command that force-applies a pending update. Shown in the "update available" log lines. */
    static final String REFRESH_COMMAND = "/rgrace refresh";

    // Bundled race folder staged under /races/<id>/ in the jar, with the exact files it carries. Fixed list because a
    // jar's directory entries are not reliably enumerable through getResourceAsStream. shadow_dragon carries its own
    // superforms group in forms/superforms.json; each shadow dragon race defines its own form at level 1, so the base
    // folder's form (omega_shenron) is only one of several the unlock resolves to (see ShadowDragonFormSkill).
    private static final String[] SHADOW_DRAGON_FILES = {
            "character.json", "stats.json", "forms/superforms.json" };

    // The six shadow dragon SUB-races. Each folder is a full copy of the base shadow_dragon race (same three files), so
    // DMZ's ConfigManager loads it and isRaceLoaded returns true. They differ from the base only in raceName. They are
    // hidden from DMZ's own carousel and surfaced by SU's sub-race screen (a later batch).
    //
    // NOTE: each sub-race now carries its OWN customModel and its own single non-layered texture: shadow_dragon_2star
    // uses "2stars", shadow_dragon_3star and shadow_dragon_4star both use "3or4stars", 5/6/7star use "5stars",
    // "6stars", "7stars". The base shadow_dragon uses "omega2" (and "omega" for its form), the only one of them that
    // is LAYERED. Do not re-collapse these to one shared model. See races/shadow_dragon_<n>star/character.json.
    private static final String[] SHADOW_DRAGON_SUBRACES = {
            "shadow_dragon_2star", "shadow_dragon_3star", "shadow_dragon_4star",
            "shadow_dragon_5star", "shadow_dragon_6star", "shadow_dragon_7star" };
    private static final String[] SHADOW_DRAGON_SUBRACE_FILES = SHADOW_DRAGON_FILES;

    // The half_saiyan sub-race. It keeps the saiyan racial (Zenkai), the saiyan stats and the saiyan APPEARANCE (no
    // customModel, so it draws on the vanilla player model from the humansaiyan texture set, same as a saiyan), and
    // it is freely selectable rather than unlock-gated like the shadow dragon rungs.
    //
    // Its FORMS are deliberately not a saiyan's, and the file list is what enforces that. There is no oozaru.json:
    // a half saiyan has no tail (hasSaiyanTail is false), so oozaru, golden oozaru and the SSJ4 rung that hangs off
    // that ladder are all gone, and with them SSJ5, which RitualRaceMergeImpl no longer seeds for this race.
    // supersaiyan.json stops at SSJ3 and the superforms price list is six rungs long to match. godforms.json gives
    // them the god ritual. The legendary ladder is the ordinary three rungs: the fourth, beast, was removed at v8.
    // Dropping oozaru.json from this array is load-bearing: removeUnshippedForms deletes any forms/*.json
    // not listed here, which is what takes the group off servers that already have the race.
    private static final String HALF_SAIYAN = "half_saiyan";
    private static final String[] HALF_SAIYAN_FILES = {
            "character.json", "stats.json",
            "forms/godforms.json", "forms/legendaryforms.json", "forms/ssgrades.json", "forms/supersaiyan.json" };

    // Every bundled race and its file list, in the order they should be processed. One source of truth for both the
    // startup pass and the /rgrace refresh command.
    private static final Map<String, String[]> BUNDLED_RACES = new LinkedHashMap<>();
    static
    {
        BUNDLED_RACES.put("shadow_dragon", SHADOW_DRAGON_FILES);
        for (String subRace : SHADOW_DRAGON_SUBRACES)
            BUNDLED_RACES.put(subRace, SHADOW_DRAGON_SUBRACE_FILES);
        BUNDLED_RACES.put(HALF_SAIYAN, HALF_SAIYAN_FILES);
    }

    /** Keep at most this many upgrade backups per race so they do not grow without bound. Oldest are pruned. */
    private static final int MAX_BACKUPS_PER_RACE = 5;

    static java.util.Set<String> bundledRaceIds()
    {
        return BUNDLED_RACES.keySet();
    }

    static boolean isBundledRace(String raceId)
    {
        return raceId != null && BUNDLED_RACES.containsKey(raceId.toLowerCase(Locale.ROOT));
    }

    private static Path racesDir()
    {
        return FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve("races");
    }

    // Backups live under SU's own config namespace, NOT under config/dragonminez, so DMZ's loadAllRaces (races/ only)
    // and getAvailableConfigFiles (config/dragonminez walk) never see them. A <race_id>.bak-* folder here can never be
    // mistaken for a playable race.
    private static Path backupsDir()
    {
        return FMLPaths.CONFIGDIR.get().resolve("shuruisutilities").resolve("race_backups");
    }

    // The result of processing one race folder, used for both startup logging and the command report.
    enum Outcome
    {
        WROTE_FRESH,        // folder was absent, wrote it plus marker
        ALREADY_CURRENT,    // marker version >= bundle version, nothing to do
        UPGRADED,           // marker older, files matched recorded hash, backed up and overwrote
        SKIPPED_EDITED,     // marker older but on-disk hash differs: admin edited, left untouched
        SKIPPED_UNMARKED,   // folder existed with no marker: wrote a marker, left files untouched
        SKIPPED_DELETED,    // folder absent because an admin deleted it: tombstoned, deliberately not rewritten
        FAILED              // something threw for this race; logged, continued
    }

    /* Startup pass                                                 */

    /** Extract or version-check every bundled race folder, then reload DMZ if anything changed on disk. Never throws. */
    public static void extractAndReload()
    {
        try
        {
            // Before the per-race pass, because a successful repair leaves the folder stamped at the current version,
            // which the loop below then reports as ALREADY_CURRENT instead of doing the work twice.
            boolean changed = repairLegacyShadowDragon();

            for (Map.Entry<String, String[]> e : BUNDLED_RACES.entrySet())
            {
                Outcome outcome = processRaceOnStartup(e.getKey(), e.getValue());
                if (outcome == Outcome.WROTE_FRESH || outcome == Outcome.UPGRADED)
                    changed = true;
            }

            if (repairShadowDragonLayering())
                changed = true;

            if (changed)
            {
                // reload() re-scans the races directory (loadAllRaces), so freshly-written folders are picked up.
                ConfigManager.reload();
                LoggingHandler.sulog.info("[RaceBundle] Applied bundled race changes and reloaded DragonMineZ configs.");
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[RaceBundle] Failed to process bundled races; continuing without them.", t);
        }
    }

    /**
     * Replace a shadow dragon folder that is still the ORIGINAL hand-dropped definition, the one that predates this
     * versioning entirely.
     *
     * <h2>Why the ordinary skip is wrong here</h2>
     * {@link Outcome#SKIPPED_UNMARKED} is the right default: a folder with no marker cannot be told apart from one an
     * admin wrote, so SU stamps it and leaves the files alone. But EVERY install in the wild predates versioning, so
     * every one of them sits on that branch forever and the shipped bundle has never actually reached anybody. The
     * folder they are stuck on points {@code customModel} at {@code oshenron}, and two things follow from that:
     * <ul>
     *   <li>{@code oshenron.geo.json} only exists in an old resource pack, and that copy still carries the nine
     *       {@code armor*} anchor bones. DMZ's armour layer finds armour purely by bone name, so armour renders on the
     *       dragon (clipping and floating) even though the models SU ships had those bones removed for exactly that
     *       reason.</li>
     *   <li>The pack's {@code oshenron_0_layer2/3.png} are blank placeholders, so two of the three tinted body layers
     *       draw nothing and the body reads as the wrong art entirely.</li>
     * </ul>
     * Neither is a preference an admin could have chosen: it is a pointer at art the suite no longer ships. So this is
     * a repair, not an upgrade, and it runs without waiting for {@code /rgrace refresh}.
     *
     * <p>The detection is exact rather than heuristic. {@code oshenron} is a value no VERSIONED bundle has ever
     * written, so seeing it means the folder is the pre-versioning drop. Anything else, including a hand-picked model,
     * is left alone. A backup of the whole folder is taken first, exactly as the forced refresh does.
     *
     * <p>The legacy {@code forms/shadowforms.json} is removed rather than left beside the new {@code superforms.json}:
     * DMZ loads every {@code .json} under {@code forms/}, so leaving it would keep a second form group alive whose
     * transformation still points at {@code oshenron_transform}, and the dragon would carry the old model (and its
     * armour bones) into its transformed state.
     *
     * @return true when the folder was replaced and DMZ needs reloading
     */
    private static boolean repairLegacyShadowDragon()
    {
        String raceId = "shadow_dragon";
        Path folder = racesDir().resolve(raceId);
        try
        {
            Path character = folder.resolve("character.json");
            if (!Files.exists(character))
                return false;
            if (!LEGACY_SHADOW_DRAGON_MODEL.equals(customModelOf(character)))
                return false;

            Path backup = backupFolder(folder, raceId, UNKNOWN_VERSION);
            String[] files = BUNDLED_RACES.get(raceId);
            writeRaceFiles(raceId, files);
            removeUnshippedForms(folder, files);
            writeMarker(folder, BUNDLE_VERSION, hashWrittenFiles(folder, files), files);
            purgeGeneratedLang(raceId);
            LoggingHandler.sulog.info(
                    "[RaceBundle] Replaced the pre-versioning shadow dragon race (it still pointed at the '{}' model, "
                    + "which is why armour rendered on the dragons and two of their three body layers were blank) with "
                    + "the bundled v{} definition. Backup: {}",
                    LEGACY_SHADOW_DRAGON_MODEL, BUNDLE_VERSION, backup);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[RaceBundle] Could not replace the legacy shadow dragon race; leaving it as it"
                    + " is. Run " + REFRESH_COMMAND + " " + raceId + " to apply it by hand.", t);
            return false;
        }
    }

    /**
     * The {@code customModel} recorded in a race's character.json, or null when the file has none or cannot be read.
     * Read with a regex rather than parsed, for the same reason {@link #repairShadowDragonLayering()} rewrites with
     * one: this runs before anything has validated the file, and a malformed config must degrade to "no opinion"
     * instead of throwing during startup.
     */
    private static String customModelOf(Path characterJson)
    {
        try
        {
            String json = Files.readString(characterJson, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"customModel\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[RaceBundle] Could not read {}: {}", characterJson, t.toString());
            return null;
        }
    }

    /**
     * Delete every {@code forms/*.json} in this folder that the current bundle does not ship. DMZ loads the whole
     * directory, so a form group left over from an older bundle stays live and keeps its old models in play.
     */
    private static void removeUnshippedForms(Path folder, String[] shippedFiles) throws Exception
    {
        Path forms = folder.resolve("forms");
        if (!Files.isDirectory(forms))
            return;
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String rel : shippedFiles)
            if (rel.startsWith("forms/"))
                keep.add(rel.substring("forms/".length()));
        try (Stream<Path> list = Files.list(forms))
        {
            for (Path p : (Iterable<Path>) list::iterator)
            {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p) || !name.toLowerCase(Locale.ROOT).endsWith(".json") || keep.contains(name))
                    continue;
                Files.delete(p);
                LoggingHandler.sulog.info("[RaceBundle] Removed the superseded form group forms/{}.", name);
            }
        }
    }

    /**
     * Turn the base shadow dragon's layering back on, in place, without touching anything else in its config.
     *
     * <h2>Why this is not left to the normal upgrade</h2>
     * The version upgrade refuses to overwrite a race folder whose files no longer hash to what SU recorded writing,
     * which is correct: an admin's edits are theirs. But {@code isLayered: false} on a model that HAS layer textures
     * is not a preference, it is broken - DMZ's unlayered branch loads one texture, multiplies the lot by body colour
     * 1 and never reads colours 2 and 3, so the spikes and the blue markings simply cannot be coloured. Every install
     * that took bundle v4 is in that state, and most of them have edited the folder since, so the upgrade would skip
     * them forever.
     *
     * <p>So this rewrites exactly ONE field, only when it is false, and leaves every other edit in place. It is
     * idempotent: once the flag is true this does nothing at all.
     *
     * @return true when the file was rewritten and DMZ needs reloading
     */
    private static boolean repairShadowDragonLayering()
    {
        Path file = racesDir().resolve("shadow_dragon").resolve("character.json");
        try
        {
            if (!Files.exists(file))
                return false;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            String repaired = json.replaceAll("\"isLayered\"\\s*:\\s*false", "\"isLayered\": true");
            if (repaired.equals(json))
                return false;
            Files.writeString(file, repaired, StandardCharsets.UTF_8);
            LoggingHandler.sulog.info("[RaceBundle] Re-enabled layered textures on the shadow dragon race, so its"
                    + " spikes and markings can be coloured again.");
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[RaceBundle] Could not re-enable shadow dragon layering: {}", t.toString());
            return false;
        }
    }

    // One race folder on startup. Never throws: any failure is logged and reported as FAILED so the pass continues.
    private static Outcome processRaceOnStartup(String raceId, String[] files)
    {
        try
        {
            Path folder = racesDir().resolve(raceId);

            // Case 1: folder absent. Write it, UNLESS an admin deleted it.
            //
            // "Absent" used to mean "fresh install" and nothing else, so removing a race guaranteed it came back on
            // the next restart: the only record that SU had ever written it was the marker INSIDE the folder that
            // was just deleted. BundleTombstones keeps that record outside, so a folder that was installed once and
            // is now gone reads as deliberate and is left alone.
            if (!Files.exists(folder))
            {
                if (!BundleTombstones.shouldWriteMissing(BundleTombstones.raceId(raceId), "Race '" + raceId + "'"))
                {
                    return Outcome.SKIPPED_DELETED;
                }
                writeRaceFiles(raceId, files);
                writeMarker(folder, BUNDLE_VERSION, hashWrittenFiles(folder, files), files);
                BundleTombstones.markPresent(BundleTombstones.raceId(raceId));
                LoggingHandler.sulog.info("[RaceBundle] Wrote bundled race '{}' (v{}) into DMZ config.", raceId, BUNDLE_VERSION);
                return Outcome.WROTE_FRESH;
            }
            // Present on disk: record it as installed, and drop any stale tombstone (the admin put it back).
            BundleTombstones.markPresent(BundleTombstones.raceId(raceId));

            Marker marker = readMarker(folder);

            // Case 5: folder present, NO marker. Every install that predates versioning lands here (including dev).
            // SU cannot tell an edited folder from a pristine one, so it does NOT overwrite. Stamp a marker recording
            // the CURRENT on-disk hash and the pre-versioning sentinel, and log an actionable "update available" line.
            if (marker == null)
            {
                writeMarker(folder, UNKNOWN_VERSION, hashWrittenFiles(folder, files), files);
                LoggingHandler.sulog.warn(
                        "[RaceBundle] Race '{}' predates SU race versioning (no marker). An updated definition (v{}) is "
                        + "available but was NOT applied, because SU cannot tell whether this folder was edited. To apply "
                        + "it (a backup is taken first), run: {} {}. To keep your current files, do nothing.",
                        raceId, BUNDLE_VERSION, REFRESH_COMMAND, raceId);
                return Outcome.SKIPPED_UNMARKED;
            }

            // Case 2: marker version at or ahead of the shipped version. Up to date, nothing to do.
            if (marker.version >= BUNDLE_VERSION)
                return Outcome.ALREADY_CURRENT;

            // Marker is older. Decide by hashing what is currently on disk against what SU recorded writing.
            String currentHash = hashWrittenFiles(folder, marker.hashedFiles.isEmpty() ? files : marker.hashedFiles.toArray(new String[0]));

            // Case 3: files still match the recorded hash. Admin has not touched them, so it is safe to upgrade.
            if (marker.hash != null && marker.hash.equalsIgnoreCase(currentHash))
            {
                Path backup = backupFolder(folder, raceId, marker.version);
                writeRaceFiles(raceId, files);
                writeMarker(folder, BUNDLE_VERSION, hashWrittenFiles(folder, files), files);
                purgeGeneratedLang(raceId);
                LoggingHandler.sulog.info(
                        "[RaceBundle] Upgraded race '{}' from v{} to v{} (unedited on disk). Backup: {}",
                        raceId, marker.version, BUNDLE_VERSION, backup);
                return Outcome.UPGRADED;
            }

            // Case 4: files differ from the recorded hash. Admin edited them: do NOT overwrite. One clear warning.
            LoggingHandler.sulog.warn(
                    "[RaceBundle] Race '{}' has local edits (on-disk files differ from what SU wrote at v{}). An update "
                    + "(v{}) is available but was SKIPPED to protect your edits. To force it (your current folder is "
                    + "backed up first), run: {} {}.",
                    raceId, marker.version, BUNDLE_VERSION, REFRESH_COMMAND, raceId);
            return Outcome.SKIPPED_EDITED;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[RaceBundle] Failed to process race '" + raceId + "'.", t);
            return Outcome.FAILED;
        }
    }

    /* Forced refresh (command)                                     */

    // A per-race line for the /rgrace refresh report.
    static final class RefreshResult
    {
        final String raceId;
        final Outcome outcome;
        final int fromVersion;

        RefreshResult(String raceId, Outcome outcome, int fromVersion)
        {
            this.raceId = raceId;
            this.outcome = outcome;
            this.fromVersion = fromVersion;
        }
    }

    /**
     * Force-apply the bundle to one race id, or to every bundled race when {@code raceId} is null or "all". Always takes
     * a backup before overwriting an existing folder, even over local edits. Reloads DMZ once at the end if anything
     * changed. Never throws; returns one result per race processed.
     */
    static List<RefreshResult> forceRefresh(String raceId)
    {
        List<RefreshResult> results = new ArrayList<>();
        boolean changed = false;
        try
        {
            List<String> targets = new ArrayList<>();
            if (raceId == null || raceId.equalsIgnoreCase("all"))
                targets.addAll(BUNDLED_RACES.keySet());
            else
            {
                String id = raceId.toLowerCase(Locale.ROOT);
                if (!BUNDLED_RACES.containsKey(id))
                {
                    results.add(new RefreshResult(raceId, Outcome.FAILED, UNKNOWN_VERSION));
                    return results;
                }
                targets.add(id);
            }

            for (String id : targets)
            {
                RefreshResult r = forceRefreshOne(id, BUNDLED_RACES.get(id));
                results.add(r);
                if (r.outcome == Outcome.WROTE_FRESH || r.outcome == Outcome.UPGRADED)
                    changed = true;
            }

            if (changed)
            {
                ConfigManager.reload();
                LoggingHandler.sulog.info("[RaceBundle] Forced race refresh applied changes and reloaded DragonMineZ configs.");
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[RaceBundle] Forced race refresh failed.", t);
        }
        return results;
    }

    private static RefreshResult forceRefreshOne(String raceId, String[] files)
    {
        try
        {
            Path folder = racesDir().resolve(raceId);
            if (!Files.exists(folder))
            {
                // A FORCED refresh restores a deleted race on purpose: it is the "I changed my mind" command, and
                // the only way back short of telling an admin about a state file they should not have to know
                // exists. Writing it clears the tombstone, so it stays from here on.
                writeRaceFiles(raceId, files);
                writeMarker(folder, BUNDLE_VERSION, hashWrittenFiles(folder, files), files);
                BundleTombstones.markPresent(BundleTombstones.raceId(raceId));
                LoggingHandler.sulog.info("[RaceBundle] (force) Wrote missing race '{}' (v{}).", raceId, BUNDLE_VERSION);
                return new RefreshResult(raceId, Outcome.WROTE_FRESH, UNKNOWN_VERSION);
            }
            BundleTombstones.markPresent(BundleTombstones.raceId(raceId));

            Marker marker = readMarker(folder);
            int fromVersion = marker == null ? UNKNOWN_VERSION : marker.version;

            // Already at or ahead of the shipped version and provably unedited: report current, skip the rewrite.
            if (marker != null && marker.version >= BUNDLE_VERSION && marker.hash != null)
            {
                String currentHash = hashWrittenFiles(folder, marker.hashedFiles.isEmpty() ? files : marker.hashedFiles.toArray(new String[0]));
                if (marker.hash.equalsIgnoreCase(currentHash))
                    return new RefreshResult(raceId, Outcome.ALREADY_CURRENT, fromVersion);
            }

            // Everything else (older, unmarked, or edited): back up, overwrite, re-stamp. This is the explicit
            // operator override, so it restores the shipped files even over local edits.
            Path backup = backupFolder(folder, raceId, fromVersion);
            writeRaceFiles(raceId, files);
            // Same reason as the legacy repair: an older bundle's form group would otherwise stay live beside the
            // restored one, because DMZ loads every .json under forms/.
            removeUnshippedForms(folder, files);
            writeMarker(folder, BUNDLE_VERSION, hashWrittenFiles(folder, files), files);
            purgeGeneratedLang(raceId);
            LoggingHandler.sulog.info(
                    "[RaceBundle] (force) Restored bundled race '{}' to v{} (was v{}). Backup: {}",
                    raceId, BUNDLE_VERSION, fromVersion, backup);
            return new RefreshResult(raceId, Outcome.UPGRADED, fromVersion);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[RaceBundle] (force) Failed to refresh race '" + raceId + "'.", t);
            return new RefreshResult(raceId, Outcome.FAILED, UNKNOWN_VERSION);
        }
    }

    /* Generated-lang purge                                          */

    // On upgrade, drop this race's stale entries from sdu's generated_lang overlay so the shipped bilingual lang wins.
    // sdu's store is merge-only (remove is its ONLY delete path), so a past hand-typed override like a "benis" desc
    // survives forever otherwise and masks assets/shuruisutilities/lang. The scoping (only "race.dragonminez.<raceId>"
    // and keys under "race.dragonminez.<raceId>.", never a DMZ or foreign race) lives in the sdu-gated compat shim so
    // nothing here classloads an sdu type; a no-op when sdu is absent. Derived from the bundle's own race id, never a
    // hardcoded list.
    private static void purgeGeneratedLang(String raceId)
    {
        try
        {
            net.shurui.shuruisutilities.compat.sdu.SduGeneratedLangCompat.removeForRace(raceId);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[RaceBundle] Could not purge generated-lang overrides for '{}'.", raceId, t);
        }
    }

    /* File / marker / hash helpers                                 */

    // Write every bundled file for raceId from the jar into races/<raceId>/, overwriting. Does NOT touch the marker.
    private static void writeRaceFiles(String raceId, String[] files) throws Exception
    {
        Path folder = racesDir().resolve(raceId);
        Files.createDirectories(folder);
        for (String rel : files)
        {
            String resource = "/races/" + raceId + "/" + rel;
            Path dest = folder.resolve(rel);
            if (dest.getParent() != null)
                Files.createDirectories(dest.getParent());
            try (InputStream in = RaceBundleExtractor.class.getResourceAsStream(resource))
            {
                if (in == null)
                {
                    LoggingHandler.sulog.warn("[RaceBundle] Missing bundled resource {} for race '{}'.", resource, raceId);
                    continue;
                }
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // Parsed .su_bundle marker. version + hash of the recorded file set, plus the exact list of files that were hashed.
    private static final class Marker
    {
        final int version;
        final String hash;
        final List<String> hashedFiles;

        Marker(int version, String hash, List<String> hashedFiles)
        {
            this.version = version;
            this.hash = hash;
            this.hashedFiles = hashedFiles;
        }
    }

    // Read races/<race>/.su_bundle. Returns null if absent or unparseable (treated as "no marker"). Never throws.
    private static Marker readMarker(Path folder)
    {
        try
        {
            Path marker = folder.resolve(MARKER_NAME);
            if (!Files.exists(marker))
                return null;
            int version = UNKNOWN_VERSION;
            String hash = null;
            List<String> hashedFiles = new ArrayList<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8))
            {
                int eq = line.indexOf('=');
                if (eq <= 0)
                    continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key)
                {
                    case "version":
                        try { version = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                        break;
                    case "hash":
                        hash = value.isEmpty() ? null : value;
                        break;
                    case "files":
                        for (String f : value.split(","))
                            if (!f.trim().isEmpty())
                                hashedFiles.add(f.trim());
                        break;
                    default:
                        break;
                }
            }
            return new Marker(version, hash, hashedFiles);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[RaceBundle] Could not read marker in {}; treating as unmarked.", folder, t);
            return null;
        }
    }

    // Write races/<race>/.su_bundle recording the version, the hash, and the exact file list the hash covers.
    private static void writeMarker(Path folder, int version, String hash, String[] files) throws Exception
    {
        Files.createDirectories(folder);
        List<String> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder();
        sb.append("# SU bundled race marker. Do not edit by hand; delete to force a re-stamp.\n");
        sb.append("version=").append(version).append('\n');
        sb.append("hash=").append(hash == null ? "" : hash).append('\n');
        sb.append("files=").append(String.join(",", sorted)).append('\n');
        Files.write(folder.resolve(MARKER_NAME), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 over exactly the bundled files, in sorted relative-path order. For each file we feed: the UTF-8 bytes of
     * its forward-slash relative path, a single 0x00 separator, then the file's raw bytes, then a second 0x00. Binding
     * the path and a length-independent separator into the digest means a rename, a moved byte across a file boundary,
     * or a missing file all change the hash. A missing on-disk file contributes only its path marker, so a deleted file
     * still differs from a present one. Returns a lowercase hex string, or "" on any failure.
     */
    private static String hashWrittenFiles(Path folder, String[] files)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<String> sorted = new ArrayList<>(Arrays.asList(files));
            sorted.sort(Comparator.naturalOrder());
            for (String rel : sorted)
            {
                md.update(rel.replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                Path f = folder.resolve(rel);
                if (Files.exists(f) && Files.isRegularFile(f))
                    md.update(Files.readAllBytes(f));
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[RaceBundle] Could not hash race files in {}.", folder, t);
            return "";
        }
    }

    // Copy the race folder to config/shuruisutilities/race_backups/<race>.bak-v<old>-<timestamp>/ before overwriting.
    // Backups live OUTSIDE the DMZ config tree so they are never scanned as a race. Prunes old backups past the cap.
    private static Path backupFolder(Path folder, String raceId, int fromVersion) throws Exception
    {
        Path root = backupsDir();
        Files.createDirectories(root);
        String stamp = String.valueOf(System.currentTimeMillis());
        String vTag = fromVersion == UNKNOWN_VERSION ? "unknown" : String.valueOf(fromVersion);
        Path dest = root.resolve(raceId + ".bak-v" + vTag + "-" + stamp);
        copyTree(folder, dest);
        pruneBackups(root, raceId);
        return dest;
    }

    private static void copyTree(Path src, Path dest) throws Exception
    {
        try (Stream<Path> walk = Files.walk(src))
        {
            for (Path p : (Iterable<Path>) walk::iterator)
            {
                Path target = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p))
                    Files.createDirectories(target);
                else
                {
                    if (target.getParent() != null)
                        Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // Keep only the newest MAX_BACKUPS_PER_RACE backups for a race; delete the rest oldest-first. Never throws.
    private static void pruneBackups(Path root, String raceId)
    {
        pruneBackups(root, raceId, MAX_BACKUPS_PER_RACE);
    }

    private static void pruneBackups(Path root, String raceId, int keep)
    {
        try
        {
            String prefix = raceId + ".bak-";
            List<Path> mine = new ArrayList<>();
            try (Stream<Path> list = Files.list(root))
            {
                for (Path p : (Iterable<Path>) list::iterator)
                    if (Files.isDirectory(p) && p.getFileName().toString().startsWith(prefix))
                        mine.add(p);
            }
            if (mine.size() <= keep)
                return;
            // Sorted on the TRAILING TIMESTAMP, not on the whole name. The name is
            // "<race>.bak-v<version>-<millis>", so a lexical sort of the whole thing orders by version tag first:
            // "v10" sorts before "v2", and "vunknown" after both, so the prune was deleting whichever version
            // happened to sort lowest rather than the oldest backup, and could throw away the newest snapshot while
            // keeping an ancient one. The millis are fixed width, so ordering on them alone is a true age order.
            mine.sort(Comparator.comparingLong(RaceBundleExtractor::backupStamp)
                    .thenComparing(p -> p.getFileName().toString()));
            for (int i = 0; i < mine.size() - keep; i++)
                deleteTree(mine.get(i));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[RaceBundle] Could not prune old backups for '{}'.", raceId, t);
        }
    }

    /**
     * Prune EVERY race's backups to {@code keep}, not just the one currently being written.
     *
     * <p>{@link #pruneBackups} only runs when a race is backed up, which happens when that race is upgraded. Most
     * races are never upgraded again, so their backups were never revisited and simply stayed. This is the sweep
     * that reaches them, called from the boot-time maintenance pass rather than from the write path.
     *
     * <p>Groups by the race id in front of {@code .bak-}, so each race keeps its own newest {@code keep} rather than
     * the newest {@code keep} across all races, which would wipe out every backup of a race that has not changed
     * recently. Never throws; returns how many folders were removed.
     */
    public static int pruneAllBackups(int keep)
    {
        int removed = 0;
        try
        {
            Path root = backupsDir();
            if (!Files.isDirectory(root))
                return 0;
            java.util.Set<String> raceIds = new java.util.TreeSet<>();
            try (Stream<Path> list = Files.list(root))
            {
                for (Path p : (Iterable<Path>) list::iterator)
                {
                    if (!Files.isDirectory(p))
                        continue;
                    String name = p.getFileName().toString();
                    int marker = name.indexOf(".bak-");
                    if (marker > 0)
                        raceIds.add(name.substring(0, marker));
                }
            }
            for (String raceId : raceIds)
            {
                int before = countBackupsFor(root, raceId);
                pruneBackups(root, raceId, Math.max(1, keep));
                removed += Math.max(0, before - countBackupsFor(root, raceId));
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[RaceBundle] Could not sweep old race backups: {}", t.toString());
        }
        return removed;
    }

    /** How many race backup folders exist in total, for the maintenance report. */
    public static int countBackups()
    {
        try
        {
            Path root = backupsDir();
            if (!Files.isDirectory(root))
                return 0;
            int n = 0;
            try (Stream<Path> list = Files.list(root))
            {
                for (Path p : (Iterable<Path>) list::iterator)
                    if (Files.isDirectory(p) && p.getFileName().toString().contains(".bak-"))
                        n++;
            }
            return n;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    private static int countBackupsFor(Path root, String raceId)
    {
        try (Stream<Path> list = Files.list(root))
        {
            String prefix = raceId + ".bak-";
            int n = 0;
            for (Path p : (Iterable<Path>) list::iterator)
                if (Files.isDirectory(p) && p.getFileName().toString().startsWith(prefix))
                    n++;
            return n;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /**
     * The epoch millis at the end of a backup folder name, or 0 when it cannot be read.
     *
     * <p>Zero sorts a nameless or malformed folder to the front, which makes it the first thing pruned. That is the
     * right way round: a folder this code cannot date is one it did not write in the expected shape, and keeping it
     * ahead of a real backup would be keeping the least trustworthy copy.
     */
    private static long backupStamp(Path backup)
    {
        try
        {
            String name = backup.getFileName().toString();
            int dash = name.lastIndexOf('-');
            if (dash < 0 || dash + 1 >= name.length())
                return 0L;
            return Long.parseLong(name.substring(dash + 1));
        }
        catch (Throwable t)
        {
            return 0L;
        }
    }

    private static void deleteTree(Path dir) throws Exception
    {
        try (Stream<Path> walk = Files.walk(dir))
        {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (Path p : paths)
                Files.deleteIfExists(p);
        }
    }
}
