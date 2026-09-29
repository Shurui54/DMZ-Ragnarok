package net.shurui.dev.sdu.lang;

import net.shurui.dev.sdu.form.FormData;
import net.shurui.dev.sdu.form.FormGroupData;
import net.shurui.dev.sdu.race.RaceData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-safe builder for the DMZ translation keys that give custom races/forms readable names/descriptions
 * (e.g. {@code race.dragonminez.<race>}, {@code race.dragonminez.<race>.form.<group>.<key>},
 * {@code skill.dragonminez.racial_<skill>}). Pure string logic, no client classes, so the client overlay
 * ({@code client/GeneratedLang}) and the server-side regeneration produce IDENTICAL keys.
 */
public final class GeneratedNames {

    private GeneratedNames() {
    }

    /** The lang keys for a race's display name/description and its racial passive name/description. */
    public static Map<String, String> raceKeys(String raceId, String racialSkill, String name, String desc,
                                               String racialName, String racialDesc) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raceId == null || raceId.isBlank()) {
            return m;
        }
        m.put("race.dragonminez." + raceId, blank(name) ? prettify(raceId) : name);
        if (!blank(desc)) {
            m.put("race.dragonminez." + raceId + ".desc", desc);
        }
        String base = blank(racialSkill) ? raceId : racialSkill;
        if (!blank(racialName)) {
            m.put("skill.dragonminez.racial_" + base, racialName);
            m.put("skill.dragonminez.racial_" + raceId, racialName);
            m.put("gui.action.dragonminez.racial." + base, racialName);
            m.put("gui.action.dragonminez.racial." + raceId, racialName);
        }
        if (!blank(racialDesc)) {
            m.put("skill.dragonminez.racial_" + base + ".desc", racialDesc);
            m.put("skill.dragonminez.racial_" + raceId + ".desc", racialDesc);
        }
        return m;
    }

    /**
     * The lang keys for a race's CLASSES. DMZ builds a class name as {@code class.dragonminez.<classId>} and
     * renders whatever comes back, so a class id it never heard of shows the raw key. Its own seven classes are
     * in its lang file; a class an admin adds through the editor is not, so it read as "class.dragonminez.whatever".
     * Emitting for all ids (not just custom ones) is harmless: these are overlay entries and DMZ's own lang is the
     * fallback. Prettified from the id.
     */
    public static Map<String, String> classKeys(java.util.Collection<String> classIds) {
        Map<String, String> m = new LinkedHashMap<>();
        if (classIds == null) {
            return m;
        }
        for (String id : classIds) {
            if (blank(id)) {
                continue;
            }
            m.put("class.dragonminez." + id, prettify(id));
        }
        return m;
    }

    /**
     * The keys an admin explicitly SET for a race's classes: display name and description. Separate from
     * {@link #classKeys} because they are written differently: the prettified id is a put-if-absent fallback,
     * while these are a deliberate choice and overwrite. A blank field emits nothing.
     *
     * <p>The description key is {@code .passive.desc} because that is the one DMZ renders under a class: it has no
     * separate class-description key, and its own seven classes put their blurb there. Writing anywhere else
     * produces a description nothing displays.
     */
    public static Map<String, String> classDisplayKeys(Map<String, RaceData.ClassStats> classes) {
        Map<String, String> m = new LinkedHashMap<>();
        if (classes == null) {
            return m;
        }
        for (Map.Entry<String, RaceData.ClassStats> e : classes.entrySet()) {
            String id = e.getKey();
            RaceData.ClassStats cs = e.getValue();
            if (blank(id) || cs == null) {
                continue;
            }
            if (!blank(cs.displayName)) {
                m.put("class.dragonminez." + id, cs.displayName);
            }
            if (!blank(cs.description)) {
                m.put("class.dragonminez." + id + ".passive.desc", cs.description);
            }
        }
        return m;
    }

    /**
     * The lang keys for a form group + each of its forms, for every race it's written to. DMZ builds the display
     * key from either the group name or the form-type, and the active form is tracked by {@link FormData#name}
     * (not the map key), so we emit under every combination so browse list and selected form both resolve.
     */
    public static Map<String, String> formKeys(FormGroupData group) {
        Map<String, String> m = new LinkedHashMap<>();
        if (group == null) {
            return m;
        }
        String groupDisplay = blank(group.displayName) ? prettify(group.groupName) : group.displayName;
        List<String> groupIds = distinct(group.groupName, group.formType);
        List<String> races = group.ownerRaces.isEmpty() ? List.of("") : group.ownerRaces;
        for (String race : races) {
            String raceKey = blank(race) ? "stack" : race;
            String base = "race.dragonminez." + raceKey + ".";
            for (String gid : groupIds) {
                m.put(base + "group." + gid, groupDisplay);
            }
            for (Map.Entry<String, FormData> e : group.forms.entrySet()) {
                FormData f = e.getValue();
                String display = !blank(f.displayName) ? f.displayName
                        : prettify(blank(f.name) ? e.getKey() : f.name);
                for (String gid : groupIds) {
                    for (String fid : distinct(e.getKey(), f.name)) {
                        m.put(base + "form." + gid + "." + fid, display);
                        if (!blank(f.description)) {
                            m.put(base + "form." + gid + "." + fid + ".desc", f.description);
                        }
                        if (!blank(f.unlockDescription)) {
                            m.put(base + "form." + gid + "." + fid + ".unlockdesc", f.unlockDescription);
                        }
                    }
                }
            }
        }
        return m;
    }

    /**
     * Given ONLY a lang key, reconstruct the value the generator would emit when the source string is blank (the
     * auto-derived name). Returns {@code null} for key shapes we never auto-derive (the {@code .desc}/
     * {@code .unlockdesc} tails and {@code skill.*}/{@code gui.action.*} keys hold only authored strings).
     * Callers treat {@code null} as "not auto-derived" and keep the override winning.
     */
    public static String autoDerived(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        // Descriptions/unlock text are never a prettified id, so nothing to reconstruct.
        if (key.endsWith(".desc") || key.endsWith(".unlockdesc")) {
            return null;
        }
        // race.dragonminez.<race>.group.<gid> -> prettified group id
        int group = key.indexOf(".group.");
        if (group >= 0) {
            return prettify(key.substring(group + ".group.".length()));
        }
        // race.dragonminez.<race>.form.<gid>.<fid> -> prettified form id (last segment)
        int form = key.indexOf(".form.");
        if (form >= 0) {
            return prettify(key.substring(key.lastIndexOf('.') + 1));
        }
        // race.dragonminez.<race> -> prettified race id (exactly one segment after the prefix)
        String racePrefix = "race.dragonminez.";
        if (key.startsWith(racePrefix)) {
            String rest = key.substring(racePrefix.length());
            if (!rest.isBlank() && rest.indexOf('.') < 0) {
                return prettify(rest);
            }
        }
        return null;
    }

    private static List<String> distinct(String a, String b) {
        List<String> out = new ArrayList<>();
        if (!blank(a)) {
            out.add(a);
        }
        if (!blank(b) && !out.contains(b)) {
            out.add(b);
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** "supersaiyan_mastered" / "superSaiyanMastered" -> "Super Saiyan Mastered". */
    public static String prettify(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        String spaced = raw.replace('_', ' ').replace('-', ' ').replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        StringBuilder sb = new StringBuilder();
        for (String word : spaced.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT)).append(' ');
        }
        return sb.toString().trim();
    }
}
