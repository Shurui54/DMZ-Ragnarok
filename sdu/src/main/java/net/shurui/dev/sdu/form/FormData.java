package net.shurui.dev.sdu.form;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of one DMZ form ({@code FormConfig.FormData}). Blocks we don't edit
 * ({@code outlineShader}, {@code triggerItemCosts}, {@code durationItemCosts}, {@code mobEffects})
 * round-trip verbatim. {@link #toJson()} overlays the managed fields onto a copy of {@link #raw},
 * so any DMZ key we don't model survives a save; a new form has empty {@code raw}.
 */
public class FormData {

    public String name = "";
    /** Shown name (drives the lang key). Not a DMZ field; DMZ ignores it. */
    public String displayName = "";
    /** Shown description (addon-only; lang key rendered in the skills GUI via mixin). */
    public String description = "";
    /** Custom unlock-requirement text (addon-only, via mixin). Blank = DMZ's default cost / "Priceless". */
    public String unlockDescription = "";
    public int unlockOnSkillLevel = 0;
    /**
     * TP cost to unlock. NOT a DMZ FormData field: DMZ reads it from the owning race's
     * {@code character.json -> formSkillsCosts[formType]} ({@code {buyFromMaster, prices[]}}, prices length
     * = the skill's max level). {@link FormFileManager} resolves it on load, writes it back at index
     * {@code unlockOnSkillLevel - 1} on save. {@code -1} is DMZ's real Priceless and IS written when set;
     * {@link #UNSET_COST} means never resolved (distinct from -1), so {@link FormFileManager#applyFormCosts}
     * skips it and leaves DMZ's existing cost alone.
     */
    public transient int unlockCost = UNSET_COST;

    // "not resolved" sentinel, distinct from -1 (real Priceless) so an unloaded cost is never written
    public static final int UNSET_COST = Integer.MIN_VALUE;
    /**
     * Minimum CHARACTER level to TRANSFORM INTO this form (0 = no minimum). NOT a DMZ FormData field: DMZ's
     * serializer drops unknown keys, so it lives in the {@code form_level_gates.json} sidecar
     * ({@link FormLevelGateConfig}), keyed {@code group.form}. Gates USING the form (see {@code unlockOnSkillLevel}
     * / {@code unlockCost} for buying). {@link FormFileManager} loads it from and writes it to the sidecar.
     */
    public transient int minLevel = 0;
    /**
     * DMZ ALIGNMENT bounds for this form (0..100). {@link #ALIGN_UNSET} = "no bound on that side" (blank in the
     * editor), which is distinct from a real bound of 0. {@code unlock*} gate BUYING the form; {@code use*} gate
     * transforming into / staying in it. NOT DMZ FormData fields: they live in the {@code form_alignment_gates.json}
     * sidecar ({@link FormAlignmentGateConfig}), keyed {@code group.form}. {@link FormFileManager} loads/writes them.
     */
    public static final int ALIGN_UNSET = Integer.MIN_VALUE;
    public transient int alignUnlockMin = ALIGN_UNSET;
    public transient int alignUnlockMax = ALIGN_UNSET;
    public transient int alignUseMin = ALIGN_UNSET;
    public transient int alignUseMax = ALIGN_UNSET;
    public String formCombo = "";
    public String customModel = "";
    public boolean keepBaseFormHeadBones = false;
    public String transformationAnimation = "transf.generic";

    public String bodyColor1 = "";
    public String bodyColor2 = "";
    public String bodyColor3 = "";
    public String extraFormLayer = "";
    public String extraFormColor = "";
    public String hairType = "";
    public String forcedHairCode = "";
    public String hairColor = "";
    public String eye1Color = "";
    public String eye2Color = "";
    public String auraType = "";
    public int auraLayer = 0;
    public String auraColor = "";
    public boolean hasLightnings = false;
    public String lightningColor = "";
    // DMZ-native full-body tint (Kaioken's mechanism). tintIntensity 0 = off; blends tintColor over
    // skin/hair/body when > 0. Absent in JSON = no tint.
    public String tintColor = "";
    public double tintIntensity = 0.0;
    // DMZ's FormData defaults modelScaling to 0.9375 (15/16) on all axes, not 1.0. A form left at 1.0
    // renders ~6.7% larger than every stock DMZ form.
    public static final float DEFAULT_MODEL_SCALE = 0.9375f;
    public float[] modelScaling = defaultModelScaling();

    /** Fresh copy of the default. Never hand out a shared array: callers mutate it in place. */
    public static float[] defaultModelScaling() {
        return new float[]{DEFAULT_MODEL_SCALE, DEFAULT_MODEL_SCALE, DEFAULT_MODEL_SCALE};
    }

    public double strMultiplier = 1.0;
    public double skpMultiplier = 1.0;
    public double stmMultiplier = 1.0;
    public double defMultiplier = 1.0;
    public double vitMultiplier = 1.0;
    public double pwrMultiplier = 1.0;
    public double eneMultiplier = 1.0;
    public double speedMultiplier = 1.0;
    public double staminaDrainMultiplier = 1.0;
    public double energyDrain = 0.0;
    public double staminaDrain = 0.0;
    public double healthDrain = 0.0;
    public double attackSpeed = 1.0;

    public double maxMastery = 100.0;
    public double masteryPerHitDealt = 0.025;
    public double masteryPerHitReceived = 0.025;
    public double passiveMasteryEveryFiveSeconds = 0.01;
    public double maxCostMultiplier = 1.0;
    public double maxStatsMultiplier = 1.0;

    public String formRequisite = "";
    public double unlockOnMastery = 0.0;
    public double stackOnMastery = 0.0;
    public double instantTransformOnMastery = 40.0;
    // DMZ 2.1.1 collapsed canAlwaysTransform + directTransformationIfUsed + allowAlwaysTransformOnMastery
    // + directTransformIfUsedOnMastery into this: freely selectable once mastery reaches this value
    // (TransformationsHelper.meetsFreeTransformMasteryFor). 0 = always selectable, and the default so a
    // newly-authored form actually shows up in-game.
    public double allowFreeTransformOnMastery = 0.0;

    public boolean formStackable = true;
    public double stackDrainMultiplier = 1.0;
    public List<String> incompatibleWith = new ArrayList<>(List.of("ultimate.ultimate"));
    public List<String> shareMasteryWith = new ArrayList<>();
    public double shareMasteryMultiplier = 1.0;

    // JsonObject stays the backing store so future DMZ keys still round-trip
    public JsonObject outlineShader = defaultShader();
    public JsonArray triggerItemCosts = new JsonArray();
    public JsonArray durationItemCosts = new JsonArray();
    public JsonArray mobEffects = new JsonArray();

    // exact JSON this form loaded from (empty for a new form). transient, rebuilt from fromJson after a
    // net round-trip.
    public transient JsonObject raw = new JsonObject();

    // addon-only combat tuning (auto dodge + damage-taken stat buffs). stored by FormCombatConfig,
    // travels with the editor bundle only.
    public transient FormCombatData combat = new FormCombatData();

    // addon-only extra aura layers. stored by FormAuraConfig, appended to DMZ's aura by our client mixin.
    public transient FormAuraData extraAura = new FormAuraData();

    public boolean shaderEnabled() {
        return GsonHelper.getAsBoolean(outlineShader, "enabled", false);
    }

    public void setShaderEnabled(boolean v) {
        outlineShader.addProperty("enabled", v);
    }

    public String shaderColor(boolean primary) {
        return GsonHelper.getAsString(outlineShader, primary ? "primaryColor" : "secondaryColor", "#7FFFFF");
    }

    public void setShaderColor(boolean primary, String v) {
        outlineShader.addProperty(primary ? "primaryColor" : "secondaryColor", v);
    }

    public double shaderValue(String key, double fallback) {
        return GsonHelper.getAsDouble(outlineShader, key, fallback);
    }

    public void setShaderValue(String key, double v) {
        outlineShader.addProperty(key, v);
    }

    /**
     * Per-race colour overrides for multi-race forms: {@code race -> (field -> "#RRGGBB")}, restricted to
     * {@link #PER_RACE_COLOR_FIELDS}. Inherit unless overridden. Baked into each race's DMZ form file at
     * save ({@link #resolvedFor}); addon-only, never written as its own block into DMZ's JSON.
     */
    public final Map<String, Map<String, String>> raceColors = new LinkedHashMap<>();

    /** Colour fields that may differ per race (character appearance only; aura/lightning stay shared). */
    public static final List<String> PER_RACE_COLOR_FIELDS =
            List.of("bodyColor1", "bodyColor2", "bodyColor3", "hairColor", "eye1Color", "eye2Color");

    public String baseColor(String field) {
        return switch (field) {
            case "bodyColor1" -> bodyColor1;
            case "bodyColor2" -> bodyColor2;
            case "bodyColor3" -> bodyColor3;
            case "hairColor" -> hairColor;
            case "eye1Color" -> eye1Color;
            case "eye2Color" -> eye2Color;
            default -> "";
        };
    }

    public void setBaseColor(String field, String v) {
        switch (field) {
            case "bodyColor1" -> bodyColor1 = v;
            case "bodyColor2" -> bodyColor2 = v;
            case "bodyColor3" -> bodyColor3 = v;
            case "hairColor" -> hairColor = v;
            case "eye1Color" -> eye1Color = v;
            case "eye2Color" -> eye2Color = v;
            default -> { }
        }
    }

    /** Colour a race sees for {@code field}: its override if set, else the shared base. */
    public String colorGet(String race, String field) {
        if (race != null && !race.isBlank()) {
            Map<String, String> m = raceColors.get(race);
            if (m != null && m.containsKey(field)) {
                return m.get(field);
            }
        }
        return baseColor(field);
    }

    // blank race edits the shared base. setting a race's colour equal to base clears its override.
    public void colorSet(String race, String field, String v) {
        if (race == null || race.isBlank()) {
            setBaseColor(field, v);
            return;
        }
        String base = baseColor(field);
        if (v == null ? base.isEmpty() : v.equals(base)) {
            Map<String, String> m = raceColors.get(race);
            if (m != null) {
                m.remove(field);
                if (m.isEmpty()) {
                    raceColors.remove(race);
                }
            }
            return;
        }
        raceColors.computeIfAbsent(race, k -> new LinkedHashMap<>()).put(field, v);
    }

    /** Copy with {@code race}'s colour overrides folded into the base fields (for saving). */
    public FormData resolvedFor(String race) {
        FormData f = copy();
        Map<String, String> m = raceColors.get(race);
        if (m != null) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                f.setBaseColor(e.getKey(), e.getValue());
            }
        }
        return f;
    }

    public JsonObject raceColorsToJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Map<String, String>> e : raceColors.entrySet()) {
            JsonObject fields = new JsonObject();
            for (Map.Entry<String, String> fe : e.getValue().entrySet()) {
                fields.addProperty(fe.getKey(), fe.getValue());
            }
            o.add(e.getKey(), fields);
        }
        return o;
    }

    public void loadRaceColors(JsonObject o) {
        raceColors.clear();
        if (o == null) {
            return;
        }
        for (String race : o.keySet()) {
            if (!o.get(race).isJsonObject()) {
                continue;
            }
            JsonObject fields = o.getAsJsonObject(race);
            Map<String, String> m = new LinkedHashMap<>();
            for (String field : fields.keySet()) {
                if (PER_RACE_COLOR_FIELDS.contains(field)) {
                    m.put(field, fields.get(field).getAsString());
                }
            }
            if (!m.isEmpty()) {
                raceColors.put(race, m);
            }
        }
    }

    public FormData copy() {
        FormData f = fromJson(name, toJson(), owningGroup);
        for (Map.Entry<String, Map<String, String>> e : raceColors.entrySet()) {
            f.raceColors.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return f;
    }

    /**
     * DMZ feeds {@code customModel} into {@code dragonminez:geo/entity/races/<customModel>_<gender>.geo.json}.
     * A ResourceLocation path only allows {@code [a-z0-9/._-]}, so a {@code namespace:} prefix crashes DMZ's
     * model resolver mid-render. Strip prefix + illegal chars so an unresolvable name falls back to the
     * human model instead.
     */
    private static String sanitizeModel(String model) {
        if (model == null) {
            return "";
        }
        String s = model.trim();
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9/._-]", "");
    }

    /** The editor's four alignment fields as a sidecar {@link FormAlignmentGateConfig.Bounds} (UNSET -> null). */
    public FormAlignmentGateConfig.Bounds toAlignmentBounds() {
        return new FormAlignmentGateConfig.Bounds(
                alignUnlockMin == ALIGN_UNSET ? null : alignUnlockMin,
                alignUnlockMax == ALIGN_UNSET ? null : alignUnlockMax,
                alignUseMin == ALIGN_UNSET ? null : alignUseMin,
                alignUseMax == ALIGN_UNSET ? null : alignUseMax);
    }

    /** Populate the editor's four alignment fields from a sidecar {@link FormAlignmentGateConfig.Bounds} (null -> UNSET). */
    public void setAlignmentBounds(FormAlignmentGateConfig.Bounds b) {
        if (b == null) {
            alignUnlockMin = alignUnlockMax = alignUseMin = alignUseMax = ALIGN_UNSET;
            return;
        }
        alignUnlockMin = b.unlockMin == null ? ALIGN_UNSET : b.unlockMin;
        alignUnlockMax = b.unlockMax == null ? ALIGN_UNSET : b.unlockMax;
        alignUseMin = b.useMin == null ? ALIGN_UNSET : b.useMin;
        alignUseMax = b.useMax == null ? ALIGN_UNSET : b.useMax;
    }

    public JsonObject toJson() {
        // start from loaded JSON so unmodelled DMZ keys survive; managed fields below overlay it
        JsonObject o = raw == null ? new JsonObject() : raw.deepCopy();
        o.addProperty("name", name);
        o.addProperty("displayName", displayName);
        o.addProperty("description", description);
        o.addProperty("unlockDescription", unlockDescription);
        o.addProperty("unlockOnSkillLevel", unlockOnSkillLevel);
        o.addProperty("formCombo", formCombo);
        o.addProperty("customModel", sanitizeModel(customModel));
        o.addProperty("keepBaseFormHeadBones", keepBaseFormHeadBones);
        o.addProperty("transformationAnimation", transformationAnimation);
        o.addProperty("bodyColor1", bodyColor1);
        o.addProperty("bodyColor2", bodyColor2);
        o.addProperty("bodyColor3", bodyColor3);
        o.addProperty("extraFormLayer", extraFormLayer);
        o.addProperty("extraFormColor", extraFormColor);
        o.addProperty("hairType", hairType);
        o.addProperty("forcedHairCode", forcedHairCode);
        o.addProperty("hairColor", hairColor);
        o.addProperty("eye1Color", eye1Color);
        o.addProperty("eye2Color", eye2Color);
        o.addProperty("auraType", auraType);
        o.addProperty("auraLayer", auraLayer);
        o.addProperty("auraColor", auraColor);
        o.addProperty("hasLightnings", hasLightnings);
        o.addProperty("lightningColor", lightningColor);
        o.addProperty("tintColor", tintColor);
        o.addProperty("tintIntensity", tintIntensity);
        o.add("modelScaling", floatArray(modelScaling));
        o.addProperty("strMultiplier", strMultiplier);
        o.addProperty("skpMultiplier", skpMultiplier);
        o.addProperty("stmMultiplier", stmMultiplier);
        o.addProperty("defMultiplier", defMultiplier);
        o.addProperty("vitMultiplier", vitMultiplier);
        o.addProperty("pwrMultiplier", pwrMultiplier);
        o.addProperty("eneMultiplier", eneMultiplier);
        o.addProperty("speedMultiplier", speedMultiplier);
        o.addProperty("staminaDrainMultiplier", staminaDrainMultiplier);
        o.addProperty("energyDrain", energyDrain);
        o.addProperty("staminaDrain", staminaDrain);
        o.addProperty("healthDrain", healthDrain);
        o.addProperty("attackSpeed", attackSpeed);
        o.addProperty("maxMastery", maxMastery);
        o.addProperty("masteryPerHitDealt", masteryPerHitDealt);
        o.addProperty("masteryPerHitReceived", masteryPerHitReceived);
        o.addProperty("passiveMasteryEveryFiveSeconds", passiveMasteryEveryFiveSeconds);
        o.addProperty("maxCostMultiplier", maxCostMultiplier);
        o.addProperty("maxStatsMultiplier", maxStatsMultiplier);
        o.addProperty("formRequisite", formRequisite);
        o.addProperty("unlockOnMastery", unlockOnMastery);
        o.addProperty("stackOnMastery", stackOnMastery);
        o.addProperty("instantTransformOnMastery", instantTransformOnMastery);
        o.addProperty("allowFreeTransformOnMastery", allowFreeTransformOnMastery);
        // when stackable, drop the key (and any stale copy in raw) so it falls back to DMZ's default;
        // an old sdu build wrongly stamped false onto every form. only write it when genuinely false.
        if (formStackable) {
            o.remove("formStackable");
        } else {
            o.addProperty("formStackable", false);
        }
        o.addProperty("stackDrainMultiplier", stackDrainMultiplier);
        o.add("incompatibleWith", stringArray(incompatibleWith));
        o.add("shareMasteryWith", stringArray(shareMasteryWith));
        o.addProperty("shareMasteryMultiplier", shareMasteryMultiplier);
        o.add("outlineShader", outlineShader);
        o.add("triggerItemCosts", triggerItemCosts);
        o.add("durationItemCosts", durationItemCosts);
        o.add("mobEffects", mobEffects);
        return o;
    }

    /**
     * The group this form loaded from, when the caller knew it. Decides whether a stored
     * {@code formStackable=false} is deliberate: DMZ protects the whole {@code ultimate} GROUP, so without
     * this the editor would show a differently-named form in that group as stackable while DMZ keeps it locked.
     */
    public transient String owningGroup = "";

    public static FormData fromJson(String key, JsonObject o) {
        return fromJson(key, o, "");
    }

    public static FormData fromJson(String key, JsonObject o, String groupName) {
        FormData f = new FormData();
        // keep source JSON verbatim so toJson() can preserve fields we don't model
        f.raw = o.deepCopy();
        f.name = GsonHelper.getAsString(o, "name", key);
        f.displayName = GsonHelper.getAsString(o, "displayName", "");
        f.description = GsonHelper.getAsString(o, "description", "");
        f.unlockDescription = GsonHelper.getAsString(o, "unlockDescription", "");
        f.unlockOnSkillLevel = GsonHelper.getAsInt(o, "unlockOnSkillLevel", 0);
        f.formCombo = GsonHelper.getAsString(o, "formCombo", "");
        f.customModel = GsonHelper.getAsString(o, "customModel", "");
        f.keepBaseFormHeadBones = GsonHelper.getAsBoolean(o, "keepBaseFormHeadBones", false);
        f.transformationAnimation = GsonHelper.getAsString(o, "transformationAnimation", "transf.generic");
        f.bodyColor1 = GsonHelper.getAsString(o, "bodyColor1", "");
        f.bodyColor2 = GsonHelper.getAsString(o, "bodyColor2", "");
        f.bodyColor3 = GsonHelper.getAsString(o, "bodyColor3", "");
        f.extraFormLayer = GsonHelper.getAsString(o, "extraFormLayer", "");
        f.extraFormColor = GsonHelper.getAsString(o, "extraFormColor", "");
        f.hairType = GsonHelper.getAsString(o, "hairType", "");
        f.forcedHairCode = GsonHelper.getAsString(o, "forcedHairCode", "");
        f.hairColor = GsonHelper.getAsString(o, "hairColor", "");
        f.eye1Color = GsonHelper.getAsString(o, "eye1Color", "");
        f.eye2Color = GsonHelper.getAsString(o, "eye2Color", "");
        f.auraType = GsonHelper.getAsString(o, "auraType", "");
        f.auraLayer = GsonHelper.getAsInt(o, "auraLayer", 0);
        f.auraColor = GsonHelper.getAsString(o, "auraColor", "");
        f.hasLightnings = GsonHelper.getAsBoolean(o, "hasLightnings", false);
        f.lightningColor = GsonHelper.getAsString(o, "lightningColor", "");
        // absent = "" / 0.0 = no tint
        f.tintColor = GsonHelper.getAsString(o, "tintColor", "");
        f.tintIntensity = GsonHelper.getAsDouble(o, "tintIntensity", 0.0);
        f.modelScaling = readFloatArray(o, "modelScaling", f.modelScaling);
        f.strMultiplier = GsonHelper.getAsDouble(o, "strMultiplier", 1.0);
        f.skpMultiplier = GsonHelper.getAsDouble(o, "skpMultiplier", 1.0);
        f.stmMultiplier = GsonHelper.getAsDouble(o, "stmMultiplier", 1.0);
        f.defMultiplier = GsonHelper.getAsDouble(o, "defMultiplier", 1.0);
        f.vitMultiplier = GsonHelper.getAsDouble(o, "vitMultiplier", 1.0);
        f.pwrMultiplier = GsonHelper.getAsDouble(o, "pwrMultiplier", 1.0);
        f.eneMultiplier = GsonHelper.getAsDouble(o, "eneMultiplier", 1.0);
        f.speedMultiplier = GsonHelper.getAsDouble(o, "speedMultiplier", 1.0);
        f.staminaDrainMultiplier = GsonHelper.getAsDouble(o, "staminaDrainMultiplier", 1.0);
        f.energyDrain = GsonHelper.getAsDouble(o, "energyDrain", 0.0);
        f.staminaDrain = GsonHelper.getAsDouble(o, "staminaDrain", 0.0);
        f.healthDrain = GsonHelper.getAsDouble(o, "healthDrain", 0.0);
        f.attackSpeed = GsonHelper.getAsDouble(o, "attackSpeed", 1.0);
        f.maxMastery = GsonHelper.getAsDouble(o, "maxMastery", 100.0);
        f.masteryPerHitDealt = GsonHelper.getAsDouble(o, "masteryPerHitDealt", 0.025);
        f.masteryPerHitReceived = GsonHelper.getAsDouble(o, "masteryPerHitReceived", 0.025);
        f.passiveMasteryEveryFiveSeconds = GsonHelper.getAsDouble(o, "passiveMasteryEveryFiveSeconds", 0.01);
        f.maxCostMultiplier = GsonHelper.getAsDouble(o, "maxCostMultiplier", 1.0);
        f.maxStatsMultiplier = GsonHelper.getAsDouble(o, "maxStatsMultiplier", 1.0);
        f.formRequisite = GsonHelper.getAsString(o, "formRequisite", "");
        f.unlockOnMastery = GsonHelper.getAsDouble(o, "unlockOnMastery", 0.0);
        f.stackOnMastery = GsonHelper.getAsDouble(o, "stackOnMastery", 0.0);
        f.instantTransformOnMastery = GsonHelper.getAsDouble(o, "instantTransformOnMastery", 40.0);
        f.allowFreeTransformOnMastery = GsonHelper.getAsDouble(o, "allowFreeTransformOnMastery", 0.0);
        f.stackDrainMultiplier = GsonHelper.getAsDouble(o, "stackDrainMultiplier", 1.0);
        f.incompatibleWith = readStringList(o, "incompatibleWith");
        // an old sdu build stamped a spurious false onto every form. reinterpret stored false as true
        // (mirror of DmzCompat.repairFormStackable) UNLESS this is ultimate or declares a genuine
        // (non-ultimate.ultimate) incompatibility, where the false is intentional.
        boolean storedStackable = GsonHelper.getAsBoolean(o, "formStackable", true);
        f.owningGroup = groupName == null ? "" : groupName;
        f.formStackable = storedStackable || !f.isGenuinelyNonStackable(key);
        f.shareMasteryWith = readStringList(o, "shareMasteryWith");
        f.shareMasteryMultiplier = GsonHelper.getAsDouble(o, "shareMasteryMultiplier", 1.0);
        if (o.has("outlineShader") && o.get("outlineShader").isJsonObject()) {
            f.outlineShader = o.getAsJsonObject("outlineShader");
        }
        f.triggerItemCosts = arr(o, "triggerItemCosts");
        f.durationItemCosts = arr(o, "durationItemCosts");
        f.mobEffects = arr(o, "mobEffects");
        return f;
    }

    // mirror of DmzCompat.isProtectedNonStackable: true = a stored formStackable=false is intentional
    // (ultimate, or a non-ultimate.ultimate incompatibility). everything else is the legacy corrupted
    // default, reinterpreted as stackable.
    private boolean isGenuinelyNonStackable(String key) {
        // GROUP check first, as DmzCompat does at runtime: a form dropped into the ultimate group is
        // protected whatever it is called.
        if ("ultimate".equalsIgnoreCase(owningGroup == null ? "" : owningGroup.trim())) {
            return true;
        }
        if ("ultimate".equalsIgnoreCase(key == null ? "" : key.trim())
                || "ultimate".equalsIgnoreCase(name == null ? "" : name.trim())) {
            return true;
        }
        if (incompatibleWith != null) {
            for (String entry : incompatibleWith) {
                if (entry != null && !"ultimate.ultimate".equalsIgnoreCase(entry.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonObject defaultShader() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", false);
        o.addProperty("primaryColor", "#7FFFFF");
        o.addProperty("secondaryColor", "#7FFFFF");
        o.addProperty("noiseScale", 1.0);
        o.addProperty("colorMixSpeed", 1.0);
        o.addProperty("outlineThickness", 1.0);
        return o;
    }

    private static JsonArray floatArray(float[] values) {
        JsonArray a = new JsonArray();
        for (float v : values) {
            a.add(v);
        }
        return a;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }

    private static float[] readFloatArray(JsonObject o, String key, float[] fallback) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return fallback;
        }
        JsonArray a = o.getAsJsonArray(key);
        float[] out = new float[Math.max(3, a.size())];
        for (int i = 0; i < out.length; i++) {
            out[i] = i < a.size() ? a.get(i).getAsFloat() : DEFAULT_MODEL_SCALE;
        }
        return out;
    }

    private static List<String> readStringList(JsonObject o, String key) {
        List<String> list = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (var el : o.getAsJsonArray(key)) {
                list.add(el.getAsString());
            }
        }
        return list;
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }
}
