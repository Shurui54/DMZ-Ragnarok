package net.shurui.dev.sdu.race;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// addon-only JSON-editable behaviour for a racial skill (config/sdu/racial_skills.json), keyed by the racial
// id a race's character.json stores in racialSkill. one Trigger decides when it activates plus a bag of
// while-active effects (DMZ stat boosts via BonusStats, regen/restore, damage resist, potions). lets us author
// DMZ-style racials, e.g. a Zenkai-like "below X% HP -> +stats" passive.
public class RacialSkillData {

    public enum Trigger {
        ALWAYS_ON,
        ON_LOW_HP,  // while below lowHpThreshold (Zenkai-style)
        ON_HIT,     // for durationSeconds after taking a hit
        ON_KILL,    // for durationSeconds after a kill
        ACTIVE      // for durationSeconds on keybind, then cooldown
    }

    public Trigger trigger = Trigger.ALWAYS_ON;

    public double lowHpThreshold = 30.0;   // ON_LOW_HP: active below this % of max health
    public double cooldownSeconds = 30.0;  // ACTIVE
    public double durationSeconds = 10.0;  // ON_HIT/ON_KILL/ACTIVE

    // per-stat buff while active, as % of that stat's base (added via BonusStats).
    public final Map<String, Double> statPercent = new LinkedHashMap<>();

    // regen per second while active, as % of the respective max.
    public double healthRegenPct = 0.0;
    public double kiRegenPct = 0.0;
    public double staminaRegenPct = 0.0;

    // instant restore on trigger, as % of the respective max.
    public double instantHealPct = 0.0;
    public double instantKiPct = 0.0;
    public double instantStaminaPct = 0.0;

    public double damageResistPct = 0.0;  // % incoming damage reduced while active

    // the ocarina this racial hands its owner, and the songs played on it. Off unless a race turns it on.
    public OcarinaData ocarina = new OcarinaData();

    // vanilla potion effects while active, each "namespace:path:amplifier".
    public final List<String> potionEffects = new ArrayList<>();

    public RacialSkillData() {
        for (String s : RacialSkillData.STATS) {
            statPercent.put(s, 0.0);
        }
    }

    // DMZ's six primary stat keys (shared with the form-combat system).
    public static final String[] STATS = {"STR", "SKP", "RES", "VIT", "PWR", "ENE"};

    public double stat(String s) {
        return statPercent.getOrDefault(s, 0.0);
    }

    public void setStat(String s, double v) {
        statPercent.put(s, v);
    }

    // no gameplay effect configured -> skippable.
    public boolean isEmpty() {
        if (ocarina != null && ocarina.enabled) {
            return false;
        }
        if (healthRegenPct != 0 || kiRegenPct != 0 || staminaRegenPct != 0
                || instantHealPct != 0 || instantKiPct != 0 || instantStaminaPct != 0
                || damageResistPct != 0 || !potionEffects.isEmpty()) {
            return false;
        }
        for (double v : statPercent.values()) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("trigger", trigger.name());
        o.addProperty("lowHpThreshold", lowHpThreshold);
        o.addProperty("cooldownSeconds", cooldownSeconds);
        o.addProperty("durationSeconds", durationSeconds);
        JsonObject stats = new JsonObject();
        for (String s : STATS) {
            stats.addProperty(s, stat(s));
        }
        o.add("statPercent", stats);
        o.addProperty("healthRegenPct", healthRegenPct);
        o.addProperty("kiRegenPct", kiRegenPct);
        o.addProperty("staminaRegenPct", staminaRegenPct);
        o.addProperty("instantHealPct", instantHealPct);
        o.addProperty("instantKiPct", instantKiPct);
        o.addProperty("instantStaminaPct", instantStaminaPct);
        o.addProperty("damageResistPct", damageResistPct);
        JsonArray potions = new JsonArray();
        for (String p : potionEffects) {
            potions.add(p);
        }
        o.add("potionEffects", potions);
        o.add("ocarina", ocarina.toJson());
        return o;
    }

    public static RacialSkillData fromJson(JsonObject o) {
        RacialSkillData d = new RacialSkillData();
        if (o == null) {
            return d;
        }
        try {
            d.trigger = Trigger.valueOf(GsonHelper.getAsString(o, "trigger", "ALWAYS_ON"));
        } catch (IllegalArgumentException ignored) {
            d.trigger = Trigger.ALWAYS_ON;
        }
        d.lowHpThreshold = GsonHelper.getAsDouble(o, "lowHpThreshold", 30.0);
        d.cooldownSeconds = GsonHelper.getAsDouble(o, "cooldownSeconds", 30.0);
        d.durationSeconds = GsonHelper.getAsDouble(o, "durationSeconds", 10.0);
        if (o.has("statPercent") && o.get("statPercent").isJsonObject()) {
            JsonObject stats = o.getAsJsonObject("statPercent");
            for (String s : STATS) {
                d.setStat(s, GsonHelper.getAsDouble(stats, s, 0.0));
            }
        }
        d.ocarina = OcarinaData.fromJson(o.has("ocarina") && o.get("ocarina").isJsonObject()
                ? o.getAsJsonObject("ocarina") : null);
        d.healthRegenPct = GsonHelper.getAsDouble(o, "healthRegenPct", 0.0);
        d.kiRegenPct = GsonHelper.getAsDouble(o, "kiRegenPct", 0.0);
        d.staminaRegenPct = GsonHelper.getAsDouble(o, "staminaRegenPct", 0.0);
        d.instantHealPct = GsonHelper.getAsDouble(o, "instantHealPct", 0.0);
        d.instantKiPct = GsonHelper.getAsDouble(o, "instantKiPct", 0.0);
        d.instantStaminaPct = GsonHelper.getAsDouble(o, "instantStaminaPct", 0.0);
        d.damageResistPct = GsonHelper.getAsDouble(o, "damageResistPct", 0.0);
        d.potionEffects.clear();
        if (o.has("potionEffects") && o.get("potionEffects").isJsonArray()) {
            for (var el : o.getAsJsonArray("potionEffects")) {
                String v = el.getAsString();
                if (v != null && !v.isBlank()) {
                    d.potionEffects.add(v.trim());
                }
            }
        }
        return d;
    }

    public RacialSkillData copy() {
        return fromJson(toJson());
    }
}
