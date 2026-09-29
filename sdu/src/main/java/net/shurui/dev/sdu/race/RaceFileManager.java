package net.shurui.dev.sdu.race;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzCompat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads and writes DragonMine Z races in DMZ's config tree: each race is a folder
 * {@code config/dragonminez/races/<race>/} with {@code character.json} and {@code stats.json}. After a
 * write DMZ's {@code ConfigManager.reload()} is invoked (via {@link DmzCompat}) so it re-scans races;
 * connected clients pick the change up on rejoin. Existing {@code forms/} subfolders are left intact.
 */
public final class RaceFileManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private RaceFileManager() {
    }

    private static Path racesDir() {
        return FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve("races");
    }

    public static List<RaceData> loadAll() {
        List<RaceData> result = new ArrayList<>();
        Path dir = racesDir();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> races = Files.list(dir)) {
            for (Path raceDir : races.filter(Files::isDirectory).sorted().toList()) {
                String id = raceDir.getFileName().toString();
                try {
                    JsonObject character = readJson(raceDir.resolve("character.json"));
                    JsonObject stats = readJson(raceDir.resolve("stats.json"));
                    if (character != null || stats != null) {
                        result.add(RaceData.fromJson(id, character, stats));
                    }
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read race '{}': {}", DmzNpc.MODID, id, e.toString());
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to list races: {}", DmzNpc.MODID, e.toString());
        }
        result.sort(Comparator.comparing(r -> r.raceId));
        // Attach our separate racial-skill behaviour by racial id.
        for (RaceData race : result) {
            race.racial = RacialSkillConfig.get(race.racialSkill);
        }
        return result;
    }

    private static JsonObject readJson(Path p) throws Exception {
        return Files.exists(p) ? GSON.fromJson(Files.readString(p), JsonObject.class) : null;
    }

    /**
     * The class ids a race actually defines on disk ({@code config/dragonminez/races/<race>/stats.json}), lowercased.
     *
     * <p>This is the AUTHORITATIVE, un-polluted list: it reads the same file DMZ loads, so a class an admin deleted is
     * absent here even after DMZ's {@code RaceStatsConfig.getClassStats} has resurrected a default-scaling stand-in
     * into its in-memory map (that mutating getter is why {@code getAllClasses()} cannot be trusted for this question).
     *
     * <p>Returns an EMPTY set when the race, its {@code stats.json} or its {@code classes} block is missing or
     * unreadable, so a caller can distinguish "no data, do nothing" from "this class is genuinely gone" and never
     * mass-migrate players off valid classes on a transient read failure.
     */
    public static Set<String> definedClassIds(String raceId) {
        if (raceId == null || raceId.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonObject stats = readJson(racesDir().resolve(raceId).resolve("stats.json"));
            if (stats == null || !stats.has("classes") || !stats.get("classes").isJsonObject()) {
                return Collections.emptySet();
            }
            Set<String> out = new LinkedHashSet<>();
            for (String id : stats.getAsJsonObject("classes").keySet()) {
                if (id != null && !id.isBlank()) {
                    out.add(id.toLowerCase(Locale.ROOT));
                }
            }
            return out;
        } catch (Exception e) {
            DmzNpc.LOGGER.debug("[{}] Could not read defined classes for race '{}': {}", DmzNpc.MODID, raceId, e.toString());
            return Collections.emptySet();
        }
    }

    /** Write a race's character.json + stats.json and reload DMZ. Null on success, else an error. */
    public static String save(RaceData race) {
        try {
            String id = net.shurui.dev.sdu.util.SduIds.sanitize(race.raceId);
            Path folder = racesDir().resolve(id);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("character.json"), GSON.toJson(race.toCharacterJson()));
            Files.writeString(folder.resolve("stats.json"), GSON.toJson(race.toStatsJson()));
            // Persist the addon-only racial behaviour to our own config (never into DMZ's JSON).
            RacialSkillConfig.put(race.racialSkill, race.racial);
            RacialSkillConfig.save();
            DmzCompat.reloadConfigs();
            DmzNpc.LOGGER.info("[{}] Saved race '{}' ({} classes)", DmzNpc.MODID, id, race.classes.size());
            return null;
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save race '{}': {}", DmzNpc.MODID, race.raceId, e.toString());
            return e.getMessage();
        }
    }

    /** Delete a custom race folder entirely (character/stats/forms). Reloads DMZ afterwards. */
    public static void delete(String raceId) {
        try {
            Path folder = racesDir().resolve(net.shurui.dev.sdu.util.SduIds.sanitize(raceId));
            if (Files.isDirectory(folder)) {
                try (Stream<Path> walk = Files.walk(folder)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            DmzCompat.reloadConfigs();
            DmzNpc.LOGGER.info("[{}] Deleted race '{}'", DmzNpc.MODID, raceId);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to delete race '{}': {}", DmzNpc.MODID, raceId, e.toString());
        }
    }
}
