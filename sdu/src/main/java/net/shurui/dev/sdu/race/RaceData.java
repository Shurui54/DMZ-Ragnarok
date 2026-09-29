package net.shurui.dev.sdu.race;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// in-memory model of a DMZ race: character.json (identity/appearance/default look) and stats.json (per-class
// base stats, scaling, regen, TP, passives). round-trips to DMZ's exact config JSON. loss-proof writes: the
// toJson methods start from a copy of the exact loaded JSON (rawCharacter/rawStats/ClassStats.raw) and only
// OVERLAY the modelled fields, so any DMZ key we don't model (incl. anything a future DMZ adds) survives a save
// and saving never resets DMZ's own fields. new race/class has empty raw and just writes the modelled fields.
public class RaceData {

    // configVersion is written live from DMZ's running ConfigManager.CONFIG_VERSION (see DmzCompat).

    public String raceId = "custom_race";

    public boolean hasGender = true;
    public boolean useVanillaSkin = true;
    public String customModel = "";
    public boolean isLayered = true;
    public final List<String> headBones = new ArrayList<>(List.of("hair"));
    public String racialSkill = "";
    public boolean hasSaiyanTail = false;
    public String auraType = "";
    // Addon-only display strings, stored in character.json under sdu* keys (DMZ ignores unknown fields)
    // so the server can regenerate the lang names from the config alone - see GeneratedNames.
    public String displayName = "";
    public String description = "";
    public String racialName = "";
    public String racialDesc = "";
    public float[] defaultModelScaling = {0.9375f, 0.9375f, 0.9375f};
    public int defaultBodyType = 0;
    public int defaultHairType = 0;
    public int defaultEyesType = 0;
    public int defaultNoseType = 0;
    public int defaultMouthType = 0;
    public int defaultTattooType = 0;
    public String defaultBodyColor = "#FFD3C9";
    public String defaultBodyColor2 = "#572117";
    public String defaultBodyColor3 = "#FFD3C9";
    public String defaultHairColor = "#222629";
    public String defaultEye1Color = "#222629";
    public String defaultEye2Color = "#222629";
    public String defaultAuraColor = "#7FFFFF";
    // base-race aura size multipliers (default 1.0), stored under auraWidth/auraHeight. applied by the
    // client AuraScaleMixin when the player is in their base race aura (no form modifier overriding).
    public float auraWidth = 1.0f;
    public float auraHeight = 1.0f;
    // Map<formGroup, List<Integer>> TP costs; preserved verbatim.
    public JsonObject formSkillsCosts = new JsonObject();

    public final Map<String, ClassStats> classes = new LinkedHashMap<>();

    // addon-only racial behaviour keyed by racialSkill. not in DMZ's config JSON: stored by RacialSkillConfig,
    // only carried on the editor bundle.
    public transient RacialSkillData racial = new RacialSkillData();

    // exact character.json/stats.json this race was loaded from (empty when new). the toJson methods copy these
    // first then overlay, so DMZ keys we don't touch survive a save. transient: rebuilt from fromJson after a
    // network round-trip.
    public transient JsonObject rawCharacter = new JsonObject();
    public transient JsonObject rawStats = new JsonObject();

    public static class ClassStats {
        /**
         * What the class is CALLED, and what its entry says, as opposed to {@code raceId}-style ids.
         *
         * <p>DMZ renders a class as whatever {@code class.dragonminez.<id>} resolves to and its blurb as
         * {@code class.dragonminez.<id>.passive.desc}. It ships those for its own seven classes only, so a class
         * added in the editor rendered the raw key and had no blurb at all. These two fields are what the admin
         * types; {@code GeneratedNames.classKeys} turns them into exactly those keys.
         *
         * <p>Stored on the class object as {@code sduDisplayName} / {@code sduDescription}, matching how a race
         * carries its own (see {@link RaceData#description}). DMZ ignores keys it does not know, and the raw
         * round-trip keeps them through a save it never reads.
         */
        public String displayName = "";
        public String description = "";

        public int str, skp, res, vit, pwr, ene;
        public double strScaling = 1, skpScaling = 1, stmScaling = 1, defScaling = 1, vitScaling = 1, pwrScaling = 1, eneScaling = 1;
        public double baseHp5 = 5, hp5VitScaling = 0.05, baseEp5 = 5, ep5EneScaling = 0.05, baseSp5 = 12, sp5StmScaling = 0.12;
        public double tpCostMultiplier = 1.0, tpGainMultiplier = 1.0;
        // {enabled, values{...}}, preserved verbatim (passive values vary per class).
        public JsonObject passive = defaultPassive();

        public transient JsonObject raw = new JsonObject();

        JsonObject toJson() {
            JsonObject o = raw == null ? new JsonObject() : raw.deepCopy();
            // merge INTO the loaded baseStats/statScaling (don't replace) so an unknown sub-key a future DMZ
            // adds is never dropped. scaling especially must never be reset.
            JsonObject base = childObject(o, "baseStats");
            base.addProperty("STR", str);
            base.addProperty("SKP", skp);
            base.addProperty("RES", res);
            base.addProperty("VIT", vit);
            base.addProperty("PWR", pwr);
            base.addProperty("ENE", ene);
            o.add("baseStats", base);
            JsonObject sc = childObject(o, "statScaling");
            sc.addProperty("STR_scaling", strScaling);
            sc.addProperty("SKP_scaling", skpScaling);
            sc.addProperty("STM_scaling", stmScaling);
            sc.addProperty("DEF_scaling", defScaling);
            sc.addProperty("VIT_scaling", vitScaling);
            sc.addProperty("PWR_scaling", pwrScaling);
            sc.addProperty("ENE_scaling", eneScaling);
            o.add("statScaling", sc);
            o.addProperty("baseHp5", baseHp5);
            o.addProperty("hp5VitScaling", hp5VitScaling);
            o.addProperty("baseEp5", baseEp5);
            o.addProperty("ep5EneScaling", ep5EneScaling);
            o.addProperty("baseSp5", baseSp5);
            o.addProperty("sp5StmScaling", sp5StmScaling);
            o.addProperty("tpCostMultiplier", tpCostMultiplier);
            o.addProperty("tpGainMultiplier", tpGainMultiplier);
            o.add("passive", passive);
            // Blank means "not set": write nothing rather than an empty string, so an unnamed class falls back to
            // the prettified id instead of resolving to nothing on the character screen.
            if (displayName != null && !displayName.isBlank()) {
                o.addProperty("sduDisplayName", displayName);
            } else {
                o.remove("sduDisplayName");
            }
            if (description != null && !description.isBlank()) {
                o.addProperty("sduDescription", description);
            } else {
                o.remove("sduDescription");
            }
            return o;
        }

        static ClassStats fromJson(JsonObject o) {
            ClassStats c = new ClassStats();
            c.raw = o.deepCopy();
            c.displayName = GsonHelper.getAsString(o, "sduDisplayName", "");
            c.description = GsonHelper.getAsString(o, "sduDescription", "");
            if (o.has("baseStats")) {
                JsonObject b = o.getAsJsonObject("baseStats");
                c.str = GsonHelper.getAsInt(b, "STR", 0);
                c.skp = GsonHelper.getAsInt(b, "SKP", 0);
                c.res = GsonHelper.getAsInt(b, "RES", 0);
                c.vit = GsonHelper.getAsInt(b, "VIT", 0);
                c.pwr = GsonHelper.getAsInt(b, "PWR", 0);
                c.ene = GsonHelper.getAsInt(b, "ENE", 0);
            }
            if (o.has("statScaling")) {
                JsonObject s = o.getAsJsonObject("statScaling");
                c.strScaling = GsonHelper.getAsDouble(s, "STR_scaling", 1);
                c.skpScaling = GsonHelper.getAsDouble(s, "SKP_scaling", 1);
                c.stmScaling = GsonHelper.getAsDouble(s, "STM_scaling", 1);
                c.defScaling = GsonHelper.getAsDouble(s, "DEF_scaling", 1);
                c.vitScaling = GsonHelper.getAsDouble(s, "VIT_scaling", 1);
                c.pwrScaling = GsonHelper.getAsDouble(s, "PWR_scaling", 1);
                c.eneScaling = GsonHelper.getAsDouble(s, "ENE_scaling", 1);
            }
            c.baseHp5 = GsonHelper.getAsDouble(o, "baseHp5", 5);
            c.hp5VitScaling = GsonHelper.getAsDouble(o, "hp5VitScaling", 0.05);
            c.baseEp5 = GsonHelper.getAsDouble(o, "baseEp5", 5);
            c.ep5EneScaling = GsonHelper.getAsDouble(o, "ep5EneScaling", 0.05);
            c.baseSp5 = GsonHelper.getAsDouble(o, "baseSp5", 12);
            c.sp5StmScaling = GsonHelper.getAsDouble(o, "sp5StmScaling", 0.12);
            c.tpCostMultiplier = GsonHelper.getAsDouble(o, "tpCostMultiplier", 1.0);
            c.tpGainMultiplier = GsonHelper.getAsDouble(o, "tpGainMultiplier", 1.0);
            if (o.has("passive") && o.get("passive").isJsonObject()) {
                c.passive = o.getAsJsonObject("passive");
            }
            return c;
        }

        static JsonObject defaultPassive() {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", true);
            o.add("values", new JsonObject());
            return o;
        }

        // existing child object under key (kept so its unknown sub-keys survive), else a new one.
        private static JsonObject childObject(JsonObject parent, String key) {
            return parent.has(key) && parent.get(key).isJsonObject()
                    ? parent.getAsJsonObject(key) : new JsonObject();
        }
    }

    // DMZ feeds customModel into dragonminez:geo/entity/races/<customModel>_<gender>.geo.json. a ResourceLocation
    // path only allows [a-z0-9/._-], so a "namespace:" prefix (e.g. sdu:default) makes DMZ's resolver throw
    // mid-render and crash. strip a leading namespace and drop illegal chars so a bad name falls back to human
    // instead of crashing.
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

    public JsonObject toCharacterJson() {
        // copy the loaded character.json then overlay the modelled fields.
        JsonObject o = rawCharacter == null ? new JsonObject() : rawCharacter.deepCopy();
        net.shurui.dev.sdu.compat.DmzCompat.writeConfigVersion(o);
        o.addProperty("raceName", raceId);
        o.addProperty("hasGender", hasGender);
        o.addProperty("useVanillaSkin", useVanillaSkin);
        // DMZ's BoneVisibilityHandler shows the tail bone for ANY custom race whose model it doesn't recognise
        // as tailless (only human/namekian/majin are), so hasSaiyanTail=false is ignored and a model-less race
        // always shows a tail. blank customModel already resolves to human, so with the tail OFF write "human"
        // explicitly -> DMZ treats it tailless and hides the bone. tail ON: leave blank so DMZ's per-character
        // tail control works.
        String model = sanitizeModel(customModel);
        String modelForDmz = model.isBlank() && !hasSaiyanTail ? "human" : model;
        o.addProperty("customModel", modelForDmz);
        o.addProperty("isLayered", isLayered);
        JsonArray bones = new JsonArray();
        for (String b : headBones) {
            bones.add(b);
        }
        o.add("headBones", bones);
        o.addProperty("racialSkill", racialSkill);
        o.addProperty("hasSaiyanTail", hasSaiyanTail);
        o.addProperty("auraType", auraType);
        JsonArray scale = new JsonArray();
        for (float f : defaultModelScaling) {
            scale.add(f);
        }
        o.add("defaultModelScaling", scale);
        o.addProperty("defaultBodyType", defaultBodyType);
        o.addProperty("defaultHairType", defaultHairType);
        o.addProperty("defaultEyesType", defaultEyesType);
        o.addProperty("defaultNoseType", defaultNoseType);
        o.addProperty("defaultMouthType", defaultMouthType);
        o.addProperty("defaultTattooType", defaultTattooType);
        o.addProperty("defaultBodyColor", defaultBodyColor);
        o.addProperty("defaultBodyColor2", defaultBodyColor2);
        o.addProperty("defaultBodyColor3", defaultBodyColor3);
        o.addProperty("defaultHairColor", defaultHairColor);
        o.addProperty("defaultEye1Color", defaultEye1Color);
        o.addProperty("defaultEye2Color", defaultEye2Color);
        o.addProperty("defaultAuraColor", defaultAuraColor);
        o.addProperty("auraWidth", auraWidth);
        o.addProperty("auraHeight", auraHeight);
        o.add("formSkillsCosts", formSkillsCosts);
        // addon-only display strings so the server can rebuild the lang names from config.
        o.addProperty("sduDisplayName", displayName);
        o.addProperty("sduDescription", description);
        o.addProperty("sduRacialName", racialName);
        o.addProperty("sduRacialDesc", racialDesc);
        return o;
    }

    public JsonObject toStatsJson() {
        // copy the loaded stats.json; each class rebuilds from its own raw (see ClassStats.toJson).
        JsonObject o = rawStats == null ? new JsonObject() : rawStats.deepCopy();
        net.shurui.dev.sdu.compat.DmzCompat.writeConfigVersion(o);
        JsonObject cl = new JsonObject();
        for (Map.Entry<String, ClassStats> e : classes.entrySet()) {
            cl.add(e.getKey(), e.getValue().toJson());
        }
        o.add("classes", cl);
        return o;
    }

    public static RaceData fromJson(String raceId, JsonObject character, JsonObject stats) {
        RaceData r = new RaceData();
        r.raceId = raceId;
        // keep the source JSON verbatim so toJson can preserve fields we don't model.
        if (character != null) {
            r.rawCharacter = character.deepCopy();
        }
        if (stats != null) {
            r.rawStats = stats.deepCopy();
        }
        if (character != null) {
            r.hasGender = GsonHelper.getAsBoolean(character, "hasGender", true);
            r.useVanillaSkin = GsonHelper.getAsBoolean(character, "useVanillaSkin", true);
            r.customModel = GsonHelper.getAsString(character, "customModel", "");
            r.isLayered = GsonHelper.getAsBoolean(character, "isLayered", true);
            r.headBones.clear();
            if (character.has("headBones") && character.get("headBones").isJsonArray()) {
                for (var el : character.getAsJsonArray("headBones")) {
                    r.headBones.add(el.getAsString());
                }
            }
            r.racialSkill = GsonHelper.getAsString(character, "racialSkill", "");
            r.hasSaiyanTail = GsonHelper.getAsBoolean(character, "hasSaiyanTail", false);
            // reverse toCharacterJson's tail-hiding: blank+tail-off was written as "human", read it back as
            // blank. ("human" WITH tail ON is a real human pick, keep it.)
            if ("human".equals(r.customModel) && !r.hasSaiyanTail) {
                r.customModel = "";
            }
            r.auraType = GsonHelper.getAsString(character, "auraType", "");
            r.displayName = GsonHelper.getAsString(character, "sduDisplayName", "");
            r.description = GsonHelper.getAsString(character, "sduDescription", "");
            r.racialName = GsonHelper.getAsString(character, "sduRacialName", "");
            r.racialDesc = GsonHelper.getAsString(character, "sduRacialDesc", "");
            r.defaultModelScaling = readFloat3(character, "defaultModelScaling", r.defaultModelScaling);
            r.defaultBodyType = GsonHelper.getAsInt(character, "defaultBodyType", 0);
            r.defaultHairType = GsonHelper.getAsInt(character, "defaultHairType", 0);
            r.defaultEyesType = GsonHelper.getAsInt(character, "defaultEyesType", 0);
            r.defaultNoseType = GsonHelper.getAsInt(character, "defaultNoseType", 0);
            r.defaultMouthType = GsonHelper.getAsInt(character, "defaultMouthType", 0);
            r.defaultTattooType = GsonHelper.getAsInt(character, "defaultTattooType", 0);
            r.defaultBodyColor = GsonHelper.getAsString(character, "defaultBodyColor", r.defaultBodyColor);
            r.defaultBodyColor2 = GsonHelper.getAsString(character, "defaultBodyColor2", r.defaultBodyColor2);
            r.defaultBodyColor3 = GsonHelper.getAsString(character, "defaultBodyColor3", r.defaultBodyColor3);
            r.defaultHairColor = GsonHelper.getAsString(character, "defaultHairColor", r.defaultHairColor);
            r.defaultEye1Color = GsonHelper.getAsString(character, "defaultEye1Color", r.defaultEye1Color);
            r.defaultEye2Color = GsonHelper.getAsString(character, "defaultEye2Color", r.defaultEye2Color);
            r.defaultAuraColor = GsonHelper.getAsString(character, "defaultAuraColor", r.defaultAuraColor);
            r.auraWidth = GsonHelper.getAsFloat(character, "auraWidth", 1.0f);
            r.auraHeight = GsonHelper.getAsFloat(character, "auraHeight", 1.0f);
            if (character.has("formSkillsCosts") && character.get("formSkillsCosts").isJsonObject()) {
                r.formSkillsCosts = character.getAsJsonObject("formSkillsCosts");
            }
        }
        if (stats != null && stats.has("classes") && stats.get("classes").isJsonObject()) {
            JsonObject cl = stats.getAsJsonObject("classes");
            for (String name : cl.keySet()) {
                if (cl.get(name).isJsonObject()) {
                    r.classes.put(name, ClassStats.fromJson(cl.getAsJsonObject(name)));
                }
            }
        }
        return r;
    }

    // network bundle: raceId + both config JSONs + the addon-only racial behaviour.
    public JsonObject toBundle() {
        JsonObject o = new JsonObject();
        o.addProperty("raceId", raceId);
        o.add("character", toCharacterJson());
        o.add("stats", toStatsJson());
        o.add("racial", racial.toJson());
        return o;
    }

    public static RaceData fromBundle(JsonObject o) {
        RaceData r = fromJson(GsonHelper.getAsString(o, "raceId", "custom_race"),
                o.has("character") ? o.getAsJsonObject("character") : null,
                o.has("stats") ? o.getAsJsonObject("stats") : null);
        if (o.has("racial") && o.get("racial").isJsonObject()) {
            r.racial = RacialSkillData.fromJson(o.getAsJsonObject("racial"));
        }
        return r;
    }

    public RaceData copy() {
        return fromBundle(toBundle());
    }

    private static float[] readFloat3(JsonObject o, String key, float[] fallback) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return fallback;
        }
        JsonArray a = o.getAsJsonArray(key);
        float[] out = {1, 1, 1};
        for (int i = 0; i < 3 && i < a.size(); i++) {
            out[i] = a.get(i).getAsFloat();
        }
        return out;
    }
}
