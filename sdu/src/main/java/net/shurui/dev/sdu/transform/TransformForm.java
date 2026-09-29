package net.shurui.dev.sdu.transform;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// data holder for ONE form in an NPC transformation chain. carries every field the feature needs (swap target +
// multipliers, dodge tuning, per-stat buff); the dodge fields are persisted (TransformEngine's sdu_tf_active)
// for the dodge runtime handler and have no effect yet.
public class TransformForm {

    // a DMZ/other entity id (e.g. "dragonminez:saga_frieza_final") OR a clone ref "cnpc$tab$name".
    public String target = "";

    // which ragnarok NPC character the new form wears, when `target` is the ragnarok NPC entity type. the whole
    // cast shares that ONE entity type, so target alone can only ever reach the default look and a chain of them
    // would be the same model over and over. blank (and every other target) leaves the entity alone.
    public String rgModelId = "";

    // HP FRACTION (0..1) that triggers this form. the editor shows a percentage but stores the fraction.
    public double trigger = 0.15;

    public double hpMult = 1.0;
    public double meleeMult = 1.0;
    public double kiMult = 1.0;
    public double speedMult = 1.0;
    public double defMult = 1.0;

    // Dodge tuning (0..100), stored for the future dodge runtime handler.
    public double dodgePhysical = 0;
    public double dodgeMeleeSkill = 0;
    public double dodgeEnergySkill = 0;
    public double damageMitigation = 0;

    // Buff: per-stat gain percentages keyed by STR,SKP,RES,VIT,PWR,ENE, capped by maxBonusPercent.
    public Map<String, Double> statGainPercent = new LinkedHashMap<>();
    public double maxBonusPercent = 100.0;

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("target", target == null ? "" : target);
        tag.putString("rgModel", rgModelId == null ? "" : rgModelId);
        tag.putDouble("trigger", trigger);
        tag.putDouble("hpMult", hpMult);
        tag.putDouble("meleeMult", meleeMult);
        tag.putDouble("kiMult", kiMult);
        tag.putDouble("speedMult", speedMult);
        tag.putDouble("defMult", defMult);
        tag.putDouble("dodgePhys", dodgePhysical);
        tag.putDouble("dodgeMelee", dodgeMeleeSkill);
        tag.putDouble("dodgeEnergy", dodgeEnergySkill);
        tag.putDouble("mitigation", damageMitigation);
        CompoundTag buff = new CompoundTag();
        // Write the stat keys SORTED, not in statGainPercent's own order, for the same reason DungeonCrateData.save
        // sorts: a raid/rift encounter's bossTransform NBT is the read supplier for the cross server state sync (see
        // RaidStateSync "raids:rifts"), which republishes whenever the serialised BYTES change. statGainPercent is a
        // LinkedHashMap whose insertion order is source dependent (fromNbt reads the stored buff's key order, fromJson
        // reads the saga JSON's key order, the editor builds a fresh UI order), and CompoundTag is HashMap backed, so
        // two keys that collide in the same bucket (VIT and STR do, at the default 16 slots) serialise in INSERTION
        // order within that bucket. Two servers holding the SAME six buffs but populated through different paths thus
        // wrote VIT/STR in opposite order, each read the other as a change, and republished at each other every poll
        // for ever. Seen live between OW1 and smp on .rifts[].encounter.bossTransform.forms[].buff. Sorting makes the
        // bytes a function of the content alone. Order carries no meaning on load (fromNbt reads keys into a map).
        List<String> keys = new ArrayList<>(statGainPercent.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            buff.putDouble(key, statGainPercent.get(key));
        }
        tag.put("buff", buff);
        tag.putDouble("maxBonusPct", maxBonusPercent);
        return tag;
    }

    public static TransformForm fromNbt(CompoundTag tag) {
        TransformForm f = new TransformForm();
        if (tag == null) {
            return f;
        }
        f.target = tag.getString("target");
        // Absent on every form written before August 2026; getString answers "" for a missing key, which is
        // exactly the "leave the entity alone" default, so nothing needs migrating.
        f.rgModelId = tag.getString("rgModel");
        if (tag.contains("trigger")) {
            f.trigger = tag.getDouble("trigger");
        }
        if (tag.contains("hpMult")) {
            f.hpMult = tag.getDouble("hpMult");
        }
        if (tag.contains("meleeMult")) {
            f.meleeMult = tag.getDouble("meleeMult");
        }
        if (tag.contains("kiMult")) {
            f.kiMult = tag.getDouble("kiMult");
        }
        if (tag.contains("speedMult")) {
            f.speedMult = tag.getDouble("speedMult");
        }
        if (tag.contains("defMult")) {
            f.defMult = tag.getDouble("defMult");
        }
        f.dodgePhysical = tag.getDouble("dodgePhys");
        f.dodgeMeleeSkill = tag.getDouble("dodgeMelee");
        f.dodgeEnergySkill = tag.getDouble("dodgeEnergy");
        f.damageMitigation = tag.getDouble("mitigation");
        f.statGainPercent = new LinkedHashMap<>();
        if (tag.contains("buff")) {
            CompoundTag buff = tag.getCompound("buff");
            for (String key : buff.getAllKeys()) {
                f.statGainPercent.put(key, buff.getDouble(key));
            }
        }
        f.maxBonusPercent = tag.contains("maxBonusPct") ? tag.getDouble("maxBonusPct") : 100.0;
        return f;
    }

    // JSON mirror of toNbt() (saga data serializes via Gson), same keys.
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("target", target == null ? "" : target);
        o.addProperty("rgModel", rgModelId == null ? "" : rgModelId);
        o.addProperty("trigger", trigger);
        o.addProperty("hpMult", hpMult);
        o.addProperty("meleeMult", meleeMult);
        o.addProperty("kiMult", kiMult);
        o.addProperty("speedMult", speedMult);
        o.addProperty("defMult", defMult);
        o.addProperty("dodgePhys", dodgePhysical);
        o.addProperty("dodgeMelee", dodgeMeleeSkill);
        o.addProperty("dodgeEnergy", dodgeEnergySkill);
        o.addProperty("mitigation", damageMitigation);
        JsonObject buff = new JsonObject();
        // Sorted for the same reason toNbt sorts: a JsonObject preserves INSERTION order, so writing statGainPercent's
        // source dependent order straight through would make this mirror's bytes depend on how the form was last
        // loaded rather than on its contents. Load reads keys into a map either way, so order carries no meaning.
        List<String> keys = new ArrayList<>(statGainPercent.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            buff.addProperty(key, statGainPercent.get(key));
        }
        o.add("buff", buff);
        o.addProperty("maxBonusPct", maxBonusPercent);
        return o;
    }

    // absent keys fall back to field defaults.
    public static TransformForm fromJson(JsonObject o) {
        TransformForm f = new TransformForm();
        if (o == null) {
            return f;
        }
        f.target = GsonHelper.getAsString(o, "target", "");
        f.rgModelId = GsonHelper.getAsString(o, "rgModel", "");
        f.trigger = GsonHelper.getAsDouble(o, "trigger", f.trigger);
        f.hpMult = GsonHelper.getAsDouble(o, "hpMult", f.hpMult);
        f.meleeMult = GsonHelper.getAsDouble(o, "meleeMult", f.meleeMult);
        f.kiMult = GsonHelper.getAsDouble(o, "kiMult", f.kiMult);
        f.speedMult = GsonHelper.getAsDouble(o, "speedMult", f.speedMult);
        f.defMult = GsonHelper.getAsDouble(o, "defMult", f.defMult);
        f.dodgePhysical = GsonHelper.getAsDouble(o, "dodgePhys", 0);
        f.dodgeMeleeSkill = GsonHelper.getAsDouble(o, "dodgeMelee", 0);
        f.dodgeEnergySkill = GsonHelper.getAsDouble(o, "dodgeEnergy", 0);
        f.damageMitigation = GsonHelper.getAsDouble(o, "mitigation", 0);
        f.statGainPercent = new LinkedHashMap<>();
        if (o.has("buff") && o.get("buff").isJsonObject()) {
            JsonObject buff = o.getAsJsonObject("buff");
            for (String key : buff.keySet()) {
                f.statGainPercent.put(key, GsonHelper.getAsDouble(buff, key, 0));
            }
        }
        f.maxBonusPercent = GsonHelper.getAsDouble(o, "maxBonusPct", 100.0);
        return f;
    }
}
