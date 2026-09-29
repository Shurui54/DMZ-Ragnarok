package net.shurui.dev.sdu.form;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Addon-only combat tuning for a single DMZ form, stored in our config ({@code config/sdu/form_combat.json}),
 * not baked into DMZ's form JSON. Two runtime systems while in the form:
 *
 * <ul>
 *   <li><b>Auto dodge</b>: percentage chance to fully avoid a hit, rolled per damage category (basic
 *       physical, melee skills, energy/ki skills).</li>
 *   <li><b>Form stat increases</b>: each stat is buffed by a percentage of the player's <em>base</em> stat
 *       (snapshotted on the transform edge). Stacked layers add together, clamped to a cap, recomputed each
 *       tick from the active layers so dropping a layer removes its share. Optionally mitigates incoming
 *       damage by a percentage.</li>
 * </ul>
 *
 * Keyed by form name, so it applies to any form the user adds without touching DMZ's configs.
 */
public class FormCombatData {

    /** DMZ's six primary stat keys (as used by {@code Stats.addStat}/{@code BonusStats}). */
    public static final String[] STATS = {"STR", "SKP", "RES", "VIT", "PWR", "ENE"};

    /** Chance (0-100) to fully avoid a basic physical/melee attack. */
    public double dodgePhysical = 0.0;
    /** Chance (0-100) to fully avoid a DMZ melee (strike) skill. */
    public double dodgeMeleeSkill = 0.0;
    /** Chance (0-100) to fully avoid a DMZ energy/ki skill. */
    public double dodgeEnergySkill = 0.0;

    /** Percentage (0-100) of an incoming hit's damage that is mitigated while in this form. */
    public double damageMitigation = 0.0;

    /**
     * Per-stat buff as a percentage of the player's <em>base</em> stat while this layer is active. Stacked
     * layers add together, clamped to {@link #maxBonusPercent}, recomputed live from the active layers.
     */
    public final Map<String, Double> statGainPercent = new LinkedHashMap<>();

    /** Cap on the total buff for each stat, as a percentage of that stat's base value. */
    public double maxBonusPercent = 100.0;

    public FormCombatData() {
        for (String s : STATS) {
            statGainPercent.put(s, 0.0);
        }
    }

    public double gain(String stat) {
        return statGainPercent.getOrDefault(stat, 0.0);
    }

    public void setGain(String stat, double v) {
        statGainPercent.put(stat, v);
    }

    /** True when nothing is configured (so we can skip persisting/handling it). */
    public boolean isEmpty() {
        if (dodgePhysical != 0 || dodgeMeleeSkill != 0 || dodgeEnergySkill != 0 || damageMitigation != 0) {
            return false;
        }
        for (double v : statGainPercent.values()) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("dodgePhysical", dodgePhysical);
        o.addProperty("dodgeMeleeSkill", dodgeMeleeSkill);
        o.addProperty("dodgeEnergySkill", dodgeEnergySkill);
        o.addProperty("damageMitigation", damageMitigation);
        o.addProperty("maxBonusPercent", maxBonusPercent);
        JsonObject gains = new JsonObject();
        for (String s : STATS) {
            gains.addProperty(s, gain(s));
        }
        o.add("statGainPercent", gains);
        return o;
    }

    public static FormCombatData fromJson(JsonObject o) {
        FormCombatData d = new FormCombatData();
        if (o == null) {
            return d;
        }
        d.dodgePhysical = GsonHelper.getAsDouble(o, "dodgePhysical", 0.0);
        d.dodgeMeleeSkill = GsonHelper.getAsDouble(o, "dodgeMeleeSkill", 0.0);
        d.dodgeEnergySkill = GsonHelper.getAsDouble(o, "dodgeEnergySkill", 0.0);
        d.damageMitigation = GsonHelper.getAsDouble(o, "damageMitigation", 0.0);
        d.maxBonusPercent = GsonHelper.getAsDouble(o, "maxBonusPercent", 100.0);
        if (o.has("statGainPercent") && o.get("statGainPercent").isJsonObject()) {
            JsonObject gains = o.getAsJsonObject("statGainPercent");
            for (String s : STATS) {
                d.setGain(s, GsonHelper.getAsDouble(gains, s, 0.0));
            }
        }
        return d;
    }

    public FormCombatData copy() {
        return fromJson(toJson());
    }
}
