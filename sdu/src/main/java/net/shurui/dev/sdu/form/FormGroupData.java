package net.shurui.dev.sdu.form;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A DMZ form group ({@code FormConfig}): a named set of {@link FormData} sharing a {@code formType}.
 * One JSON file, either per race at {@code config/dragonminez/races/<ownerRace>/forms/<groupName>.json} or
 * a race-agnostic "stack" group at {@code config/dragonminez/forms/<groupName>.json} when the owner is blank.
 */
public class FormGroupData {

    /** configVersion is written live from DMZ's running {@code ConfigManager.CONFIG_VERSION} (see DmzCompat). */

    /**
     * Owning race folders this group is written to (e.g. "saiyan"). An empty string {@code ""} entry
     * means the race-agnostic {@code config/dragonminez/forms/} stack folder. When loaded from disk a
     * group has exactly one owner (its file); the editor lets you target several at once on save.
     */
    public final List<String> ownerRaces = new ArrayList<>();
    /**
     * Owner races + name this group had when loaded from disk (empty for a new/copied group). On save, used
     * to delete this group's file from races you de-selected (or renamed away from) without touching a
     * same-named DMZ group on other races.
     */
    public final List<String> originalOwners = new ArrayList<>();
    public String originalName = "";
    public String groupName = "custom_forms";
    /** Shown group/category name (drives the lang key). Not a DMZ field; DMZ ignores it. */
    public String displayName = "";
    public String formType = "superforms";
    public final Map<String, FormData> forms = new LinkedHashMap<>();

    /** first owner (or "" for stack), for display/delete of a group loaded from a single file */
    public String primaryOwner() {
        return ownerRaces.isEmpty() ? "" : ownerRaces.get(0);
    }

    /**
     * Rebuild the forms map so each form's KEY equals its sanitized {@code name}. New forms start with a
     * placeholder key ({@code new_form}); this makes the key match once the user sets the Name, so the Form
     * Requisite picker (which lists map keys) shows the real id and DMZ stores it sensibly. Collisions suffixed.
     */
    public void normalizeFormKeys() {
        LinkedHashMap<String, FormData> rekeyed = new LinkedHashMap<>();
        for (FormData f : forms.values()) {
            String base = net.shurui.dev.sdu.util.SduIds.sanitize(
                    f.name == null || f.name.isBlank() ? "form" : f.name);
            if (base.isBlank()) {
                base = "form";
            }
            String key = base;
            int n = 2;
            while (rekeyed.containsKey(key)) {
                key = base + "_" + n++;
            }
            rekeyed.put(key, f);
        }
        forms.clear();
        forms.putAll(rekeyed);
    }

    /**
     * Move the form under {@code key} one slot forward ({@code delta == -1}) or back ({@code delta == +1})
     * in the ordered {@link #forms} map. DMZ ascends forms in JSON key order (FormConfig.forms is a
     * LinkedHashMap walked by TransformationsHelper), so reordering the map IS the reorder feature. Done as
     * a fresh-map permutation, never delete+re-add, which would trip FormFileManager's tombstone cleanup.
     *
     * <p>The two forms also swap their {@link FormData#unlockOnSkillLevel}, because DMZ gates purchasability
     * by that level independently of key order. Stock configs use non-contiguous levels (4/5/6/8), so
     * swapping the two levels (not renumbering 1..N) keeps the ladder consistent while preserving custom levels.
     *
     * @return {@code false} if {@code key} is absent or the move is out of bounds.
     */
    public boolean moveForm(String key, int delta) {
        if (delta != -1 && delta != 1) {
            return false;
        }
        List<String> keys = new ArrayList<>(forms.keySet());
        int i = keys.indexOf(key);
        if (i < 0) {
            return false;
        }
        int j = i + delta;
        if (j < 0 || j >= keys.size()) {
            return false; // out of bounds (first form up / last form down)
        }
        String other = keys.get(j);
        FormData a = forms.get(key);
        FormData b = forms.get(other);

        // Swap the skill-gate levels so the gate travels with the ladder position, not the form.
        int tmp = a.unlockOnSkillLevel;
        a.unlockOnSkillLevel = b.unlockOnSkillLevel;
        b.unlockOnSkillLevel = tmp;

        keys.set(i, other);
        keys.set(j, key);

        LinkedHashMap<String, FormData> reordered = new LinkedHashMap<>();
        for (String k : keys) {
            reordered.put(k, forms.get(k));
        }
        forms.clear();
        forms.putAll(reordered);
        return true;
    }

    /** The group config JSON, exactly as DMZ's FormConfig expects it. */
    public JsonObject toGroupJson() {
        return toGroupJson(null);
    }

    /**
     * Serialize for one owner race. A non-blank {@code race} folds its per-race colour overrides
     * ({@link FormData#resolvedFor}) into the written colours; blank/null writes the shared base colours.
     */
    public JsonObject toGroupJson(String race) {
        JsonObject o = new JsonObject();
        net.shurui.dev.sdu.compat.DmzCompat.writeConfigVersion(o);
        o.addProperty("groupName", groupName);
        o.addProperty("displayName", displayName);
        o.addProperty("formType", formType);
        JsonObject formsObj = new JsonObject();
        for (Map.Entry<String, FormData> e : forms.entrySet()) {
            FormData f = (race == null || race.isBlank()) ? e.getValue() : e.getValue().resolvedFor(race);
            formsObj.add(e.getKey(), f.toJson());
        }
        o.add("forms", formsObj);
        return o;
    }

    public static FormGroupData fromGroupJson(String ownerRace, JsonObject o) {
        FormGroupData g = new FormGroupData();
        g.ownerRaces.add(ownerRace == null ? "" : ownerRace);
        g.groupName = GsonHelper.getAsString(o, "groupName", "custom_forms");
        // Remember where/what it was loaded as, so a later save can clean up de-selected races / a rename.
        g.originalOwners.add(ownerRace == null ? "" : ownerRace);
        g.originalName = g.groupName;
        g.displayName = GsonHelper.getAsString(o, "displayName", "");
        g.formType = GsonHelper.getAsString(o, "formType", "superforms");
        if (o.has("forms") && o.get("forms").isJsonObject()) {
            JsonObject formsObj = o.getAsJsonObject("forms");
            for (String key : formsObj.keySet()) {
                if (formsObj.get(key).isJsonObject()) {
                    g.forms.put(key, FormData.fromJson(key, formsObj.getAsJsonObject(key), g.groupName));
                }
            }
        }
        return g;
    }

    /** Bundle for network sync: owning races + group JSON, plus each form's addon-only fields (combat,
     * unlock cost, extra aura, race colours) that never appear in the DMZ group JSON. */
    public JsonObject toBundle() {
        JsonObject o = new JsonObject();
        JsonArray owners = new JsonArray();
        for (String r : ownerRaces) {
            owners.add(r);
        }
        o.add("ownerRaces", owners);
        o.add("group", toGroupJson());
        JsonObject combat = new JsonObject();
        JsonObject unlockCosts = new JsonObject();
        JsonObject minLevels = new JsonObject();
        JsonObject alignmentGates = new JsonObject();
        JsonObject auras = new JsonObject();
        JsonObject raceColors = new JsonObject();
        for (Map.Entry<String, FormData> e : forms.entrySet()) {
            combat.add(e.getKey(), e.getValue().combat.toJson());
            unlockCosts.addProperty(e.getKey(), e.getValue().unlockCost);
            minLevels.addProperty(e.getKey(), e.getValue().minLevel);
            alignmentGates.add(e.getKey(), e.getValue().toAlignmentBounds().toJson());
            auras.add(e.getKey(), e.getValue().extraAura.toJson());
            raceColors.add(e.getKey(), e.getValue().raceColorsToJson());
        }
        o.add("combat", combat);
        o.add("unlockCosts", unlockCosts);
        o.add("minLevels", minLevels);
        o.add("alignmentGates", alignmentGates);
        o.add("auras", auras);
        o.add("raceColors", raceColors);
        JsonArray origOwners = new JsonArray();
        for (String r : originalOwners) {
            origOwners.add(r);
        }
        o.add("originalOwners", origOwners);
        o.addProperty("originalName", originalName);
        return o;
    }

    public static FormGroupData fromBundle(JsonObject o) {
        FormGroupData g = fromGroupJson("", o.getAsJsonObject("group"));
        g.ownerRaces.clear();
        if (o.has("ownerRaces") && o.get("ownerRaces").isJsonArray()) {
            for (var el : o.getAsJsonArray("ownerRaces")) {
                g.ownerRaces.add(el.getAsString());
            }
        }
        if (g.ownerRaces.isEmpty()) {
            g.ownerRaces.add("");
        }
        // The original identity travels verbatim (fromGroupJson set placeholders; overwrite them).
        g.originalOwners.clear();
        if (o.has("originalOwners") && o.get("originalOwners").isJsonArray()) {
            for (var el : o.getAsJsonArray("originalOwners")) {
                g.originalOwners.add(el.getAsString());
            }
        }
        g.originalName = GsonHelper.getAsString(o, "originalName", "");
        if (o.has("combat") && o.get("combat").isJsonObject()) {
            JsonObject combat = o.getAsJsonObject("combat");
            for (String key : combat.keySet()) {
                FormData f = g.forms.get(key);
                if (f != null && combat.get(key).isJsonObject()) {
                    f.combat = FormCombatData.fromJson(combat.getAsJsonObject(key));
                }
            }
        }
        if (o.has("unlockCosts") && o.get("unlockCosts").isJsonObject()) {
            JsonObject unlockCosts = o.getAsJsonObject("unlockCosts");
            for (String key : unlockCosts.keySet()) {
                FormData f = g.forms.get(key);
                if (f != null && unlockCosts.get(key).isJsonPrimitive()) {
                    f.unlockCost = unlockCosts.get(key).getAsInt();
                }
            }
        }
        if (o.has("minLevels") && o.get("minLevels").isJsonObject()) {
            JsonObject minLevels = o.getAsJsonObject("minLevels");
            for (String key : minLevels.keySet()) {
                FormData f = g.forms.get(key);
                if (f != null && minLevels.get(key).isJsonPrimitive()) {
                    f.minLevel = Math.max(0, minLevels.get(key).getAsInt());
                }
            }
        }
        if (o.has("alignmentGates") && o.get("alignmentGates").isJsonObject()) {
            JsonObject alignmentGates = o.getAsJsonObject("alignmentGates");
            for (String key : alignmentGates.keySet()) {
                FormData f = g.forms.get(key);
                if (f != null && alignmentGates.get(key).isJsonObject()) {
                    f.setAlignmentBounds(FormAlignmentGateConfig.Bounds.fromJson(alignmentGates.getAsJsonObject(key)));
                }
            }
        }
        if (o.has("auras") && o.get("auras").isJsonObject()) {
            JsonObject auras = o.getAsJsonObject("auras");
            for (String key : auras.keySet()) {
                FormData f = g.forms.get(key);
                if (f != null && auras.get(key).isJsonObject()) {
                    f.extraAura = FormAuraData.fromJson(auras.getAsJsonObject(key));
                }
            }
        }
        if (o.has("raceColors") && o.get("raceColors").isJsonObject()) {
            JsonObject raceColors = o.getAsJsonObject("raceColors");
            for (String key : raceColors.keySet()) {
                FormData f = g.forms.get(key);
                if (f != null && raceColors.get(key).isJsonObject()) {
                    f.loadRaceColors(raceColors.getAsJsonObject(key));
                }
            }
        }
        return g;
    }

    public FormGroupData copy() {
        FormGroupData g = fromBundle(toBundle());
        // A copy is a brand-new group: it must not "own" (and later delete) the source's files.
        g.originalOwners.clear();
        g.originalName = "";
        return g;
    }
}
