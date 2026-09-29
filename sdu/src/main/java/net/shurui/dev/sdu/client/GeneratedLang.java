package net.shurui.dev.sdu.client;

import net.minecraft.locale.Language;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.form.FormGroupData;
import net.shurui.dev.sdu.lang.GeneratedLangStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// Readable names/descriptions for custom races/forms in-game. DMZ shows them via translation keys
// (race.dragonminez.<race>, .desc, race.dragonminez.<race>.form.<group>.<key>, racial passive
// skill.dragonminez.racial_<race>[.desc]); a custom entry has no value so the raw key shows.
//
// Rather than ship a resource pack (needs a reload) we keep a live map of those keys and overlay it on the
// active Language via OverlayLanguage, so names show instantly and stay forced. Persists to
// config/sdu/generated_lang.json. Re-applied after each resource/language reload from ClientModBusEvents.
public final class GeneratedLang {

    private static final Map<String, String> OVERRIDES = new LinkedHashMap<>();
    // form descriptions by form key/name (lowercase); read by the skills-GUI mixin
    private static final Map<String, String> FORM_DESC = new java.util.HashMap<>();
    // form unlock-requirement text by form key/name (lowercase); read by the skills-GUI mixin
    private static final Map<String, String> FORM_UNLOCK_DESC = new java.util.HashMap<>();
    private static boolean loaded;

    // custom description for a form (by map key or name), or "" if none
    public static String formDesc(String formNameOrKey) {
        if (formNameOrKey == null) {
            return "";
        }
        return FORM_DESC.getOrDefault(formNameOrKey.toLowerCase(java.util.Locale.ROOT), "");
    }

    // custom unlock-requirement text for a form (by map key or name), or "" if none
    public static String formUnlockDesc(String formNameOrKey) {
        if (formNameOrKey == null) {
            return "";
        }
        return FORM_UNLOCK_DESC.getOrDefault(formNameOrKey.toLowerCase(java.util.Locale.ROOT), "");
    }

    private GeneratedLang() {
    }

    // load persisted entries + apply the overlay; call once on client setup
    public static void init() {
        if (!loaded) {
            load();
            loaded = true;
        }
        reinject();
    }

    // apply server-pushed entries (login sync / after an edit) onto the overlay so every client on a
    // dedicated server sees the custom names, without persisting to this client's config (server owns the file)
    public static void applySynced(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        OVERRIDES.putAll(entries);
        rebuildFormDesc();
        reinject();
    }

    /** Push the current overrides to the server so it persists them and syncs them to all clients. */
    private static void syncUp() {
        try {
            if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
                net.shurui.dev.sdu.network.DmzNet.sendLargeToServer(
                        "lang", new com.google.gson.Gson().toJson(new LinkedHashMap<>(OVERRIDES)));
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not sync generated lang to server: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** (Re)wrap the current language with our overlay. Call at startup and after every resource reload. */
    public static void reinject() {
        Language current = Language.getInstance();
        if (current instanceof OverlayLanguage overlay) {
            current = overlay.delegate; // avoid nesting overlays
        }
        Language.inject(new OverlayLanguage(current, OVERRIDES));
    }

    public static String raceName(String raceId) {
        return OVERRIDES.getOrDefault("race.dragonminez." + raceId, "");
    }

    public static String raceDesc(String raceId) {
        return OVERRIDES.getOrDefault("race.dragonminez." + raceId + ".desc", "");
    }

    public static String racialName(String raceId, String racialSkill) {
        return OVERRIDES.getOrDefault("skill.dragonminez.racial_" + racialBase(raceId, racialSkill), "");
    }

    public static String racialDesc(String raceId, String racialSkill) {
        return OVERRIDES.getOrDefault("skill.dragonminez.racial_" + racialBase(raceId, racialSkill) + ".desc", "");
    }

    /**
     * A class's display name / description as currently stored in the overlay, for pre-filling the editor.
     *
     * <p>The description lives under {@code .passive.desc}: DMZ has no separate class-description key and renders
     * that one beneath the class, which is where its own seven put their blurb.
     */
    public static String className(String classId) {
        return OVERRIDES.getOrDefault("class.dragonminez." + classId, "");
    }

    public static String classDesc(String classId) {
        return OVERRIDES.getOrDefault("class.dragonminez." + classId + ".passive.desc", "");
    }

    /** A custom form type's skill display name / description (DMZ key {@code skill.dragonminez.<type>}). */
    public static String formTypeName(String type) {
        return OVERRIDES.getOrDefault("skill.dragonminez." + type, "");
    }

    public static String formTypeDesc(String type) {
        return OVERRIDES.getOrDefault("skill.dragonminez." + type + ".desc", "");
    }

    /** DMZ keys the racial passive by the racial-skill id (which equals the race id for defaults). */
    private static String racialBase(String raceId, String racialSkill) {
        return racialSkill == null || racialSkill.isBlank() ? raceId : racialSkill;
    }

    /** Set a race's display name / description and its racial passive name+description. Blank name falls back to the id. */
    public static void putRace(String raceId, String racialSkill, String name, String desc,
                              String racialName, String racialDesc) {
        // Same key builder the server uses (GeneratedNames), so client and server keys never diverge.
        OVERRIDES.putAll(net.shurui.dev.sdu.lang.GeneratedNames.raceKeys(raceId, racialSkill, name, desc, racialName, racialDesc));
        persist();
        reinject(); // new Language instance -> cached translated components re-resolve immediately
        syncUp();
    }

    /** Set a custom form type's skill name/description shown in the skills menu. Blank name falls back to a tidied id. */
    public static void putFormType(String type, String name, String desc) {
        String display = name == null || name.isBlank() ? prettify(type) : name;
        OVERRIDES.put("skill.dragonminez." + type, display);
        if (desc != null && !desc.isBlank()) {
            OVERRIDES.put("skill.dragonminez." + type + ".desc", desc);
        } else {
            OVERRIDES.remove("skill.dragonminez." + type + ".desc");
        }
        persist();
        reinject();
        syncUp();
    }

    // set the group's shown name + each form's shown name for every race it's written to. DMZ builds the
    // display key from either group name or form-type, and the selected form is tracked by FormData.name (not
    // the map key), so we inject under every combination so both the browse list and selected form resolve.
    public static void putForms(FormGroupData group) {
        // Same key builder the server uses (GeneratedNames), so client and server keys never diverge.
        OVERRIDES.putAll(net.shurui.dev.sdu.lang.GeneratedNames.formKeys(group));
        // Index each form's description by name/key so the skills-GUI mixin can look it up per node.
        for (Map.Entry<String, net.shurui.dev.sdu.form.FormData> e : group.forms.entrySet()) {
            net.shurui.dev.sdu.form.FormData f = e.getValue();
            if (f.description != null && !f.description.isBlank()) {
                FORM_DESC.put(e.getKey().toLowerCase(java.util.Locale.ROOT), f.description);
                if (f.name != null && !f.name.isBlank()) {
                    FORM_DESC.put(f.name.toLowerCase(java.util.Locale.ROOT), f.description);
                }
            }
            if (f.unlockDescription != null && !f.unlockDescription.isBlank()) {
                FORM_UNLOCK_DESC.put(e.getKey().toLowerCase(java.util.Locale.ROOT), f.unlockDescription);
                if (f.name != null && !f.name.isBlank()) {
                    FORM_UNLOCK_DESC.put(f.name.toLowerCase(java.util.Locale.ROOT), f.unlockDescription);
                }
            }
        }
        persist();
        reinject();
        syncUp();
    }


    private static void load() {
        OVERRIDES.putAll(GeneratedLangStore.all());
        rebuildFormDesc();
    }

    private static void persist() {
        GeneratedLangStore.putAll(OVERRIDES);
    }

    /** Re-derive the per-form description + unlock-description indexes (used by the skills-GUI mixin). */
    private static void rebuildFormDesc() {
        FORM_DESC.clear();
        FORM_UNLOCK_DESC.clear();
        for (Map.Entry<String, String> e : OVERRIDES.entrySet()) {
            String k = e.getKey();
            if (!k.contains(".form.")) {
                continue;
            }
            // Check .unlockdesc first: it also ends in ".desc", so the plain-desc branch must not catch it.
            if (k.endsWith(".unlockdesc")) {
                String body = k.substring(0, k.length() - ".unlockdesc".length());
                String fid = body.substring(body.lastIndexOf('.') + 1);
                if (!fid.isBlank()) {
                    FORM_UNLOCK_DESC.put(fid.toLowerCase(java.util.Locale.ROOT), e.getValue());
                }
            } else if (k.endsWith(".desc")) {
                String body = k.substring(0, k.length() - ".desc".length());
                String fid = body.substring(body.lastIndexOf('.') + 1);
                if (!fid.isBlank()) {
                    FORM_DESC.put(fid.toLowerCase(java.util.Locale.ROOT), e.getValue());
                }
            }
        }
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
