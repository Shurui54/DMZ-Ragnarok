package net.shurui.dev.sdu.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.shurui.dev.sdu.DmzNpc;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

// Valid values the form editor offers in its dropdowns, pulled live to cover DMZ built-ins, other addons and
// the shuruis_dmz_utils pack: vocabulary used by loaded forms (ConfigManager.getAllForms()), DMZ code-level
// constants not yet in a form, and pack assets under npcmodels/, hairs/, auras/ plus DMZ's aura textures.
// All guarded, so a missing DMZ/API just yields a smaller list.
public final class DmzAssets {

    // Form types registered this session via the Form Types editor. Server skills config only refreshes on
    // rejoin, so track them client-side so a just-created type is pickable right away.
    private static final Set<String> SESSION_FORM_TYPES = new TreeSet<>();

    // Per-type presentation meta (stock icon + optional tint), keyed by sanitized type id. Seeded from the
    // server's sdu_formtype_meta.json (applySyncedFormTypeMeta) and updated optimistically when the editor
    // saves, so formTypeIcon/formTypeTint (radial + skills mixins) resolve without a rejoin. Client thread only.
    private static final java.util.Map<String, net.shurui.dev.sdu.form.FormTypeMeta> FORM_TYPE_META =
            new java.util.HashMap<>();

    private DmzAssets() {
    }

    public static void registerSessionFormType(String type) {
        if (type != null && !type.isBlank()) {
            SESSION_FORM_TYPES.add(type);
            // mark custom so the radial/skills routing mixins treat it as its own skill this session
            net.shurui.dev.sdu.form.CustomFormTypes.register(type);
        }
    }

    // as above, also caching the type's icon/tint meta for the mixins
    public static void registerSessionFormType(String type, net.shurui.dev.sdu.form.FormTypeMeta meta) {
        registerSessionFormType(type);
        if (type != null && !type.isBlank() && meta != null) {
            FORM_TYPE_META.put(metaKey(type), meta);
        }
    }

    // replace the client-side per-type meta cache from a server sync
    public static void applySyncedFormTypeMeta(java.util.Map<String, net.shurui.dev.sdu.form.FormTypeMeta> data) {
        FORM_TYPE_META.clear();
        if (data != null) {
            for (var e : data.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    FORM_TYPE_META.put(metaKey(e.getKey()), e.getValue());
                    // every server-registered custom type carries meta, so this is the authoritative set of
                    // custom types for the routing mixins (radial wheel + skills/ascension gating)
                    net.shurui.dev.sdu.form.CustomFormTypes.register(e.getKey());
                }
            }
        }
    }

    public static boolean hasFormTypeMeta(String type) {
        return type != null && FORM_TYPE_META.containsKey(metaKey(type));
    }

    // stock icon base name (one of DMZ's six), or null if no meta (caller keeps DMZ's own resolution).
    public static String formTypeIcon(String type) {
        if (type == null) {
            return null;
        }
        var m = FORM_TYPE_META.get(metaKey(type));
        return m == null ? null : m.iconBase;
    }

    // tint override as 0xRRGGBB, or -1 with no meta OR "use aura colour"; both leave DMZ's aura tint alone.
    public static int formTypeTint(String type) {
        if (type == null) {
            return -1;
        }
        var m = FORM_TYPE_META.get(metaKey(type));
        return m == null ? -1 : m.tintArgb;
    }

    private static String metaKey(String type) {
        return net.shurui.dev.sdu.util.SduIds.sanitize(type);
    }

    // every form key across loaded groups, in DMZ's group.form format (for requisite/incompatible/share-mastery
    // pickers). DMZ splits on '.' (meetsMasteryRequisite, isIncompatibleWith, mastery-sharing) and silently
    // ignores a dotless id, so the picker MUST offer the prefixed key or the requirement never applies.
    public static List<String> formKeys() {
        Set<String> s = new TreeSet<>();
        try {
            var all = com.dragonminez.common.config.ConfigManager.getAllForms();
            if (all != null) {
                for (var groups : all.values()) {
                    if (groups == null) {
                        continue;
                    }
                    for (var e : groups.entrySet()) {
                        var fc = e.getValue();
                        if (fc == null || fc.getForms() == null) {
                            continue;
                        }
                        // prefer the config's own groupName, fall back to the key: this is the group DMZ
                        // resolves masteries against.
                        String group = fc.getGroupName();
                        if (group == null || group.isEmpty()) {
                            group = e.getKey();
                        }
                        for (String formId : fc.getForms().keySet()) {
                            s.add((group != null && !group.isEmpty()) ? group + "." + formId : formId);
                        }
                    }
                }
            }
            // stack forms live in a separate STACK_FORMS map getAllForms() never touches, one level shallower:
            // Map<group, FormConfig> (key IS the group name). Iterate the same so they become pickable too.
            var stack = com.dragonminez.common.config.ConfigManager.getAllStackForms();
            if (stack != null) {
                for (var e : stack.entrySet()) {
                    var fc = e.getValue();
                    if (fc == null || fc.getForms() == null) {
                        continue;
                    }
                    String group = fc.getGroupName();
                    if (group == null || group.isEmpty()) {
                        group = e.getKey();
                    }
                    for (String formId : fc.getForms().keySet()) {
                        s.add((group != null && !group.isEmpty()) ? group + "." + formId : formId);
                    }
                }
            }
        } catch (Throwable t) {
            log(t);
        }
        return List.copyOf(s);
    }

    // formType values that resolve to a real DMZ form skill. getSkillNameForType matches
    // contains("super"/"legendary"/"god"/"android") -> the matching *forms skill, else the type verbatim
    // (works when a skill of that name exists, e.g. kaioken). Seed the known valid types so God Forms and
    // Kaioken are always selectable, then union in distinct types from loaded groups.
    public static List<String> formTypes() {
        Set<String> s = new TreeSet<>(List.of(
                "superforms", "godforms", "legendaryforms", "androidforms", "kaioken"));
        s.addAll(SESSION_FORM_TYPES);
        try {
            // form/stack skills from skills.json: a group's formType MUST be one of these (DMZ unlocks a form
            // via the skill named after its formType). stack forms only appear once their stackSkill unlocks.
            var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            if (skills != null) {
                if (skills.getFormSkills() != null) {
                    for (String t : skills.getFormSkills()) {
                        add(s, t);
                    }
                }
                if (skills.getStackSkills() != null) {
                    for (String t : skills.getStackSkills()) {
                        add(s, t);
                    }
                }
            }
        } catch (Throwable t) {
            log(t);
        }
        try {
            var all = com.dragonminez.common.config.ConfigManager.getAllForms();
            if (all != null) {
                for (var groups : all.values()) {
                    if (groups == null) {
                        continue;
                    }
                    for (var fc : groups.values()) {
                        if (fc != null) {
                            add(s, safe(fc::getFormType));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log(t);
        }
        return List.copyOf(s);
    }

    public static List<String> hairTypes() {
        Set<String> s = new TreeSet<>(List.of("base", "ssj", "ssj2", "ssj3"));
        forEachForm(d -> add(s, safe(d::getHairType)));
        scanFolderNames("hairs", s);
        return List.copyOf(s);
    }

    public static List<String> auraTypes() {
        Set<String> s = new TreeSet<>(List.of("kakarot", "speed", "ozaru", "oozaru", "supersaiyan2"));
        forEachForm(d -> add(s, safe(d::getAuraType)));
        // DMZ aura textures: assets/<ns>/textures/entity/races/aura/<name>_aura.png
        scanResources("textures/entity/races/aura", p -> p.endsWith("_aura.png"), path -> {
            String name = fileName(path);
            add(s, name.substring(0, name.length() - "_aura.png".length()));
        });
        scanFolderNames("auras", s);
        return List.copyOf(s);
    }

    public static List<String> auraLayers() {
        Set<Integer> nums = new TreeSet<>(List.of(0, 1, 2));
        forEachForm(d -> {
            try {
                Integer v = d.getAuraLayer();
                if (v != null) {
                    nums.add(v);
                }
            } catch (Throwable ignored) {
            }
        });
        return nums.stream().map(String::valueOf).toList();
    }

    // body-overlay layers used by loaded forms (DMZ + addons)
    public static List<String> formLayers() {
        Set<String> s = new TreeSet<>();
        forEachForm(d -> add(s, safe(d::getExtraFormLayer)));
        scanFolderNames("npcmodels", s);    // extra layers often ship beside NPC models
        return List.copyOf(s);
    }

    public static List<String> transformAnimations() {
        Set<String> s = new TreeSet<>();
        forEachForm(d -> add(s, safe(d::getTransformationAnimation)));
        readAnimationNames(new ResourceLocation("dragonminez", "animations/entity/races/transf.animation.json"), s);
        return List.copyOf(s);
    }

    // customModel keys DMZ's resolveCustomModel switch treats as built-in (each maps to a bundled
    // dragonminez:geo/entity/races/*.geo.json). Always valid regardless of what's on disk.
    public static final List<String> BUILTIN_RACE_MODELS = List.of(
            "human", "saiyan", "oozaru", "ssj4gt", "ssj4d", "buffed", "4arms",
            "namekian", "namekian_buffed",
            "majin", "majin_super", "majin_ultra", "majin_evil", "majin_kid", "janemba_fat", "janemba_super",
            "frostdemon", "frostdemon_second", "frostdemon_third", "frostdemon_fifth", "frostdemon_fp", "frostdemon_metalcore",
            "bioandroid", "bioandroid_semi", "bioandroid_perfect", "bioandroid_ultra", "bioandroid_xeno");

    // geos DMZ keeps in dragonminez:geo/entity/races/ that are NOT selectable player models (ki weapons, aura,
    // saibamen, accessories). A form pointing at one fails to resolve and falls back to plain human, so filter
    // them out of the picker by name (BUILTIN_RACE_MODELS covers the real player models).
    private static final Set<String> DMZ_NON_PLAYER_MODELS = Set.of(
            "saibaman", "kiaura", "kiaura2", "kiweapons", "kirayos", "accesories", "weighted_items");

    // player models a race/form can use as its customModel. GOTCHA: DMZ only loads a custom model whose geo is
    // at dragonminez:geo/entity/races/<name>.geo.json (else resolveCustomModel falls back to plain human), so
    // the pack's npcmodels/ folder (our own NPC entities) is NOT valid here and is excluded.
    public static List<String> customModels() {
        Set<String> s = new TreeSet<>(BUILTIN_RACE_MODELS);
        forEachForm(d -> add(s, safe(d::getCustomModel)));
        scanDmzRaceModels(s);
        return List.copyOf(s);
    }

    // add <name> of every dragonminez:geo/entity/races/<name>.geo.json on the resource stack. Re-scans the live
    // resource manager each call, so it reflects current pack state when the editor (re)opens. Gendered models
    // ship as <name>_male/<name>_female; the suffix is stripped. The placeholder "null" model is skipped.
    //
    // Any source is accepted. An allow-list on "file/" used to drop every mod-bundled geo, which hid the shadow
    // dragon models addons deliver under the dragonminez namespace. Instead reject only DMZ's known non-player
    // ones by name (DMZ_NON_PLAYER_MODELS), so any validly-installed player geo surfaces whoever ships it.
    private static void scanDmzRaceModels(Set<String> out) {
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            for (var e : rm.listResources("geo/entity/races",
                    loc -> loc.getNamespace().equals("dragonminez") && loc.getPath().endsWith(".geo.json")).entrySet()) {
                String name = fileName(e.getKey().getPath());
                name = name.substring(0, name.length() - ".geo.json".length());
                if (name.endsWith("_male")) {
                    name = name.substring(0, name.length() - "_male".length());
                } else if (name.endsWith("_female")) {
                    name = name.substring(0, name.length() - "_female".length());
                }
                if (!name.equals("null") && !DMZ_NON_PLAYER_MODELS.contains(name)) {
                    add(out, name);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // NPC-model folder names in the pack's npcmodels/
    public static List<String> playermodelFolders() {
        Set<String> s = new TreeSet<>();
        scanFolderNames("npcmodels", s);
        return List.copyOf(s);
    }

    // resource-pack namespace containing <folder>/<name>/ (e.g. which pack a dragons/<name> lives in). The
    // generated dragonball pack is written server-side, so the client resolves and sends this. Falls back to
    // the starter pack's namespace.
    public static String namespaceForAsset(String folder, String name) {
        String prefix = folder + "/" + name + "/";
        String[] found = {null};
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            for (ResourceLocation rl : rm.listResources(folder + "/" + name, loc -> true).keySet()) {
                if (rl.getPath().contains(prefix) || rl.getPath().startsWith(folder + "/" + name)) {
                    found[0] = rl.getNamespace();
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return found[0] != null ? found[0] : "shuruis_dmz_resources";
    }

    // DMZ face-texture folder for a race's eyes/nose/mouth, resolved from the active model's family via
    // SkinGathererProvider.modelFamily: human/saiyan share humansaiyan; namekian/frostdemon/bioandroid/majin
    // use their own; anything else is "custom" and reads the race's own folder. So picking a built-in race as
    // the customModel gives that race's model AND its face set.
    public static String appearanceFolder(String raceId, String customModel) {
        String source = customModel != null && !customModel.isBlank() ? customModel : raceId;
        String family = "human";
        try {
            family = com.dragonminez.client.util.SkinGathererProvider.modelFamily(source == null ? "" : source);
        } catch (Throwable t) {
            log(t);
        }
        return switch (family) {
            case "human" -> "humansaiyan";
            case "namekian", "frostdemon", "bioandroid", "majin" -> family;
            default -> raceId == null || raceId.isBlank() ? "humansaiyan" : raceId; // custom/oozaru
        };
    }

    // face-option indices for a folder (see appearanceFolder) and feature (eye/nose/mouth), from
    // entity/races/<folder>/faces/..._<feature>_<n>[...].png. Falls back to the union of every built-in race's
    // options when the folder has none yet.
    public static List<Integer> faceOptions(String folder, String feature) {
        java.util.TreeSet<Integer> idx = new java.util.TreeSet<>();
        if (folder != null && !folder.isBlank()) {
            scanResources("textures/entity/races/" + folder + "/faces", p -> true,
                    path -> addFaceIndex(idx, fileName(path), feature));
        }
        if (idx.isEmpty()) {
            // nothing for this race yet: offer the built-in races' options
            scanResources("textures/entity/races", p -> p.contains("/faces/"),
                    path -> addFaceIndex(idx, fileName(path), feature));
        }
        return List.copyOf(idx);
    }

    // index after _<feature>_ in a face texture name (x_eye_10_2.png -> 10)
    private static void addFaceIndex(java.util.Set<Integer> out, String name, String feature) {
        if (name == null || !name.endsWith(".png")) {
            return;
        }
        String key = "_" + feature + "_";
        int i = name.indexOf(key);
        if (i < 0) {
            return;
        }
        String rest = name.substring(i + key.length());
        int j = 0;
        while (j < rest.length() && Character.isDigit(rest.charAt(j))) {
            j++;
        }
        if (j > 0) {
            try {
                out.add(Integer.parseInt(rest.substring(0, j)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // character-class ids across all races' stats (warrior, cleric, ...)
    public static List<String> raceClasses() {
        Set<String> s = new TreeSet<>();
        try {
            var all = com.dragonminez.common.config.ConfigManager.getAllRaceStats();
            if (all != null) {
                for (var stats : all.values()) {
                    if (stats != null && stats.getAllClasses() != null) {
                        s.addAll(stats.getAllClasses());
                    }
                }
            }
        } catch (Throwable t) {
            log(t);
        }
        return List.copyOf(s);
    }

    private static void forEachForm(Consumer<com.dragonminez.common.config.FormConfig.FormData> fn) {
        try {
            var all = com.dragonminez.common.config.ConfigManager.getAllForms();
            if (all == null) {
                return;
            }
            for (var groups : all.values()) {
                if (groups == null) {
                    continue;
                }
                for (var fc : groups.values()) {
                    if (fc == null || fc.getForms() == null) {
                        continue;
                    }
                    for (var d : fc.getForms().values()) {
                        if (d != null) {
                            fn.accept(d);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log(t);
        }
    }

    private static String safe(java.util.function.Supplier<String> getter) {
        try {
            return getter.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void add(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value.trim());
        }
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    // per-asset sub-folder names under folder. Layout is <folder>/<name>/... and the editor id is that <name>
    // folder, so take the path segment right after folder/ (not the file basename). Loose files directly under
    // folder are ignored.
    private static void scanFolderNames(String folder, Set<String> out) {
        String prefix = folder + "/";
        scanResources(folder, p -> true, path -> {
            int start = path.indexOf(prefix);
            if (start < 0) {
                return;
            }
            int nameStart = start + prefix.length();
            int slash = path.indexOf('/', nameStart);
            if (slash > nameStart) {
                add(out, path.substring(nameStart, slash));
            }
        });
    }

    private static void scanResources(String folder, java.util.function.Predicate<String> pathFilter, Consumer<String> onPath) {
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            for (ResourceLocation rl : rm.listResources(folder, loc -> pathFilter.test(loc.getPath())).keySet()) {
                onPath.accept(rl.getPath());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void readAnimationNames(ResourceLocation file, Set<String> out) {
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(file);
            if (res.isEmpty()) {
                return;
            }
            try (var reader = res.get().openAsReader()) {
                JsonObject root = GsonHelper.parse(reader);
                if (root.has("animations")) {
                    out.addAll(root.getAsJsonObject("animations").keySet());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void log(Throwable t) {
        DmzNpc.LOGGER.debug("[{}] DMZ asset enumeration issue: {}", DmzNpc.MODID, t.toString());
    }
}
