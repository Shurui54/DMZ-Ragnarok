package net.shurui.dev.sdu.form;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a race's form configuration back the way the GAME reads it, and says what is wrong with it.
 *
 * <h2>Why this exists</h2>
 * Every one of these checks is a real failure somebody spent an evening on, and they share a shape: the config is
 * ACCEPTED, nothing is logged, and the only symptom is a form that cannot be entered. DragonMineZ loads these files
 * with {@code JsonParser.parseString}, which is Gson's LENIENT mode, so the class of mistake that ought to be a parse
 * error is silently absorbed and turns into wrong data instead. A trailing comma is the worst of them: strict JSON
 * rejects it outright, and lenient Gson turns it into a {@code null} ELEMENT, so the price list quietly grows by one
 * and the skill gains a level that has no form behind it and no price to buy it with.
 *
 * <h2>What it does not do</h2>
 * It reports; it does not rewrite. These are hand-authored files that somebody has reasons for, and a validator that
 * silently "corrects" them is a worse problem than the one it solves. Everything here names the file, the form and
 * the consequence, so the fix is obvious and stays the author's decision.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class FormConfigAudit {

    private FormConfigAudit() {
    }

    /**
     * Audited once at server start, when DMZ's configs are loaded and before anybody has tried to buy anything.
     *
     * <p>The point is that the operator learns about a broken price list from their own log at boot, rather than
     * from a player reporting weeks later that a form will not buy.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            logAudit();
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] form config audit failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** How much a finding matters. WARN means something is broken; NOTE means it is probably not what was meant. */
    public enum Level {
        WARN,
        NOTE
    }

    public record Finding(Level level, String race, String message) {
        @Override
        public String toString() {
            return "[" + level + "] " + race + ": " + message;
        }
    }

    private static Path dmzConfig() {
        return FormFileManager.dmzConfigDir();
    }

    /**
     * Audit every race on disk.
     *
     * <p>Reads the FILES rather than DMZ's parsed config objects, deliberately: by the time DMZ has parsed them a
     * null price has already been flattened into a number and the evidence of the original mistake is gone.
     */
    public static List<Finding> run() {
        List<Finding> findings = new ArrayList<>();
        Path racesDir = dmzConfig().resolve("races");
        if (!Files.isDirectory(racesDir)) {
            return findings;
        }
        Set<String> registeredFormSkills = readRegisteredFormSkills();
        try (Stream<Path> races = Files.list(racesDir)) {
            races.filter(Files::isDirectory).forEach(raceDir -> {
                try {
                    auditRace(raceDir, registeredFormSkills, findings);
                } catch (Throwable t) {
                    findings.add(new Finding(Level.WARN, raceDir.getFileName().toString(),
                            "could not be audited: " + t));
                }
            });
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] form audit could not list races: {}", DmzNpc.MODID, t.toString());
        }
        return findings;
    }

    /** The form types DMZ will actually treat as levelable skills. A type absent here does not exist at all. */
    private static Set<String> readRegisteredFormSkills() {
        Set<String> out = new LinkedHashSet<>();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(dmzConfig().resolve("skills.json")));
            if (root != null && root.isJsonObject() && root.getAsJsonObject().has("formSkills")) {
                for (JsonElement e : root.getAsJsonObject().getAsJsonArray("formSkills")) {
                    if (!e.isJsonNull()) {
                        out.add(e.getAsString().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Throwable t) {
            // No skills.json to compare against: skip that one check rather than claiming every type is missing.
            DmzNpc.LOGGER.debug("[{}] form audit could not read skills.json: {}", DmzNpc.MODID, t.toString());
        }
        return out;
    }

    private static void auditRace(Path raceDir, Set<String> registeredFormSkills, List<Finding> findings)
            throws Exception {
        String race = raceDir.getFileName().toString();
        Path charFile = raceDir.resolve("character.json");
        if (!Files.isRegularFile(charFile)) {
            return;
        }

        // Prices per form type, exactly as the game will see them, nulls and all.
        Map<String, JsonArray> pricesByType = new LinkedHashMap<>();
        JsonElement charRoot = JsonParser.parseString(Files.readString(charFile));
        if (charRoot != null && charRoot.isJsonObject() && charRoot.getAsJsonObject().has("formSkillsCosts")) {
            JsonObject costs = charRoot.getAsJsonObject().getAsJsonObject("formSkillsCosts");
            for (String type : costs.keySet()) {
                JsonElement block = costs.get(type);
                JsonArray prices = null;
                if (block.isJsonArray()) {
                    prices = block.getAsJsonArray();                       // legacy bare array
                } else if (block.isJsonObject() && block.getAsJsonObject().has("prices")
                        && block.getAsJsonObject().get("prices").isJsonArray()) {
                    prices = block.getAsJsonObject().getAsJsonArray("prices");
                }
                if (prices != null) {
                    pricesByType.put(type.toLowerCase(Locale.ROOT), prices);
                }
            }
        }

        // Highest skill level any form of a type is gated on, and which form sits at each level.
        Map<String, Integer> highestLevelByType = new LinkedHashMap<>();
        Map<String, List<String>> formsAboveByType = new LinkedHashMap<>();
        Map<String, Set<String>> formsByGroup = new LinkedHashMap<>();
        List<Object[]> requisites = new ArrayList<>();   // {group, form, requisite, unlockOnMastery}

        Path formsDir = raceDir.resolve("forms");
        if (Files.isDirectory(formsDir)) {
            try (Stream<Path> files = Files.list(formsDir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    JsonElement root = JsonParser.parseString(Files.readString(file));
                    if (root == null || !root.isJsonObject()) {
                        continue;
                    }
                    JsonObject group = root.getAsJsonObject();
                    String groupName = group.has("groupName") ? group.get("groupName").getAsString()
                            : file.getFileName().toString().replace(".json", "");
                    String type = group.has("formType") ? group.get("formType").getAsString()
                            .toLowerCase(Locale.ROOT) : "";
                    if (!group.has("forms") || !group.get("forms").isJsonObject()) {
                        continue;
                    }
                    JsonObject forms = group.getAsJsonObject("forms");
                    formsByGroup.computeIfAbsent(groupName.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                            .addAll(forms.keySet());
                    for (String formName : forms.keySet()) {
                        JsonObject form = forms.getAsJsonObject(formName);
                        int level = form.has("unlockOnSkillLevel") && !form.get("unlockOnSkillLevel").isJsonNull()
                                ? form.get("unlockOnSkillLevel").getAsInt() : 0;
                        highestLevelByType.merge(type, level, Math::max);
                        formsAboveByType.computeIfAbsent(type, k -> new ArrayList<>())
                                .add(formName + "@" + level);
                        double mastery = form.has("unlockOnMastery") && !form.get("unlockOnMastery").isJsonNull()
                                ? form.get("unlockOnMastery").getAsDouble() : 0.0;
                        String requisite = form.has("formRequisite") && !form.get("formRequisite").isJsonNull()
                                ? form.get("formRequisite").getAsString() : "";
                        requisites.add(new Object[]{groupName, formName, requisite, mastery});
                    }

                    // A type nothing has registered is not a skill, so none of its forms can ever be reached.
                    if (!type.isEmpty() && !registeredFormSkills.isEmpty()
                            && !registeredFormSkills.contains(type)) {
                        findings.add(new Finding(Level.WARN, race, "form group '" + groupName
                                + "' uses form type '" + type + "', which is not listed in skills.json formSkills. "
                                + "DMZ only treats a name in that list as a levelable form skill, so nothing in "
                                + "this group can be unlocked."));
                    }
                }
            }
        }

        auditPrices(race, pricesByType, highestLevelByType, formsAboveByType, findings);
        auditRequisites(race, requisites, formsByGroup, findings);
    }

    private static void auditPrices(String race, Map<String, JsonArray> pricesByType,
                                    Map<String, Integer> highestLevelByType,
                                    Map<String, List<String>> formsAboveByType,
                                    List<Finding> findings) {
        for (Map.Entry<String, JsonArray> entry : pricesByType.entrySet()) {
            String type = entry.getKey();
            JsonArray prices = entry.getValue();
            int length = prices.size();

            // THE TRAILING COMMA. Strict JSON rejects it; the lenient parser DMZ uses turns it into a null
            // element instead, so the list silently grows and the skill gains a level nothing can buy.
            for (int i = 0; i < length; i++) {
                if (prices.get(i).isJsonNull()) {
                    findings.add(new Finding(Level.WARN, race, "form type '" + type + "' has an empty price at "
                            + "position " + (i + 1) + " of " + length + ". This is almost always a TRAILING COMMA "
                            + "in the prices list: the game's JSON reader does not reject it, it turns it into an "
                            + "empty slot, so the skill gains a level that has no price and can never be bought."));
                }
            }

            for (int i = 0; i < length; i++) {
                JsonElement e = prices.get(i);
                if (!e.isJsonNull() && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                        && e.getAsInt() < 0) {
                    findings.add(new Finding(Level.NOTE, race, "form type '" + type + "' has a negative price at "
                            + "position " + (i + 1) + ". A negative price does NOT lock a level: DMZ clamps the "
                            + "cost with Math.max(0, price), so that level costs 0 TP and is free to buy."));
                }
            }

            Integer highest = highestLevelByType.get(type);
            if (highest == null) {
                findings.add(new Finding(Level.NOTE, race, "form type '" + type + "' has prices but no form group "
                        + "on this race uses that type, so the entry does nothing."));
                continue;
            }
            if (length < highest) {
                List<String> unreachable = new ArrayList<>();
                for (String s : formsAboveByType.getOrDefault(type, List.of())) {
                    int at = Integer.parseInt(s.substring(s.lastIndexOf('@') + 1));
                    if (at > length) {
                        unreachable.add(s.substring(0, s.lastIndexOf('@')) + " (needs level " + at + ")");
                    }
                }
                findings.add(new Finding(Level.WARN, race, "form type '" + type + "' has " + length + " price(s) "
                        + "but a form gated on level " + highest + ". The number of prices IS the skill's maximum "
                        + "level, so these can never be reached: " + String.join(", ", unreachable) + "."));
            } else if (length > highest) {
                findings.add(new Finding(Level.NOTE, race, "form type '" + type + "' has " + length + " price(s) "
                        + "but its highest form is gated on level " + highest + ", so the skill has "
                        + (length - highest) + " level(s) at the end with no form behind them. It will never read "
                        + "as maxed."));
            }
        }
    }

    private static void auditRequisites(String race, List<Object[]> requisites,
                                        Map<String, Set<String>> formsByGroup, List<Finding> findings) {
        for (Object[] row : requisites) {
            String group = (String) row[0];
            String form = (String) row[1];
            String requisite = (String) row[2];
            double mastery = (Double) row[3];

            if (requisite.isEmpty()) {
                // DMZ returns "unlocked" the moment the requisite is blank, WITHOUT looking at the mastery
                // figure, so a mastery requirement with nothing to measure it against is simply not applied.
                if (mastery > 0.0) {
                    findings.add(new Finding(Level.NOTE, race, "form '" + group + "." + form + "' asks for "
                            + mastery + "% mastery but names no formRequisite to measure it on. DMZ treats a blank "
                            + "requisite as 'unlocked' before it ever reads the mastery, so this form has no "
                            + "mastery gate at all."));
                }
                continue;
            }
            for (String token : requisite.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                int dot = t.indexOf('.');
                if (dot <= 0 || dot >= t.length() - 1) {
                    findings.add(new Finding(Level.WARN, race, "form '" + group + "." + form + "' has requisite '"
                            + t + "', which is not in 'group.form' shape. DMZ skips a token it cannot split, so "
                            + "this requirement is silently ignored."));
                    continue;
                }
                String reqGroup = t.substring(0, dot).toLowerCase(Locale.ROOT);
                String reqForm = t.substring(dot + 1);
                Set<String> known = formsByGroup.get(reqGroup);
                if (known == null) {
                    findings.add(new Finding(Level.WARN, race, "form '" + group + "." + form + "' requires '" + t
                            + "', but no form group named '" + reqGroup + "' exists on this race. Mastery is stored "
                            + "under the GROUP NAME, not the form type, so this requirement can never be met and "
                            + "the form can never be unlocked."));
                } else if (!known.contains(reqForm)) {
                    findings.add(new Finding(Level.WARN, race, "form '" + group + "." + form + "' requires '" + t
                            + "', but group '" + reqGroup + "' has no form called '" + reqForm + "'. This "
                            + "requirement can never be met."));
                }
            }
        }
    }

    /** Run the audit and write it to the log. Called at server start; safe to call at any time. */
    public static int logAudit() {
        List<Finding> findings = run();
        if (findings.isEmpty()) {
            DmzNpc.LOGGER.info("[{}] Form config audit: no problems found.", DmzNpc.MODID);
            return 0;
        }
        DmzNpc.LOGGER.warn("[{}] Form config audit found {} problem(s):", DmzNpc.MODID, findings.size());
        for (Finding f : findings) {
            if (f.level() == Level.WARN) {
                DmzNpc.LOGGER.warn("[{}]   {}", DmzNpc.MODID, f);
            } else {
                DmzNpc.LOGGER.info("[{}]   {}", DmzNpc.MODID, f);
            }
        }
        return findings.size();
    }
}
