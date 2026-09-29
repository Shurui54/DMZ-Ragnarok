package net.shurui.dev.sdu.saga;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory model of a single DMZ side quest: a standalone quest file DMZ reads from
 * {@code dragonminez/sidequests/**.json} (walked recursively, one per file, gated by
 * {@code sideQuestsEnabled}). Unlike saga quests these use a string id, aren't in a saga tree, and can name
 * an NPC {@code quest_giver}/{@code turn_in}. Objectives/rewards/requirements reuse the {@link SagaData}
 * nested types, so the same edit screens work and a KILL objective may still carry {@code sdu_definition}.
 */
public class SideQuestData {

    /**
     * DMZ {@code Quest.QuestType} values a standalone file may use. {@code SIDEQUEST} = normal one-shot;
     * {@code DAILY}/{@code EVENT} = repeatable (always enabled regardless of {@code sideQuestsEnabled}), pair
     * with a {@code TIME} start requirement for the cooldown. {@code SAGA} is excluded (saga editor's job).
     */
    public static final String[] QUEST_TYPES = {"SIDEQUEST", "DAILY", "EVENT"};

    public String id = "custom_sidequest";
    public String title = "New Side Quest";
    public String description = "";
    /** DMZ quest {@code type}; see {@link #QUEST_TYPES}. DAILY/EVENT make the quest repeatable. */
    public String questType = "SIDEQUEST";
    public String category = "general";
    public String questGiver = "";      // NPC id that offers the quest (blank = none)
    public String turnIn = "";          // NPC id the quest is turned in to (blank = none)
    public boolean parallelObjectives = false;
    public boolean partyScaling = true;
    public boolean secret = false;
    public String claimMode = "TREE_OR_NPC";
    /**
     * Addon-only repeat interval (seconds): after COMPLETION the quest re-unlocks once this many seconds
     * pass (0 = one-shot). DMZ has no repeat-with-cooldown and stores no completion timestamp, so
     * {@code QuestRepeatHandler} drives it via {@link net.shurui.dev.sdu.quest.RepeatConfig} + a persistent
     * {@code QuestRepeatStore}; round-trips as {@code sdu_repeat_interval}. 86400 = daily, 604800 = weekly.
     * Independent of the DMZ DAILY/EVENT quest type.
     */
    public long repeatIntervalSeconds = 0;
    /**
     * Addon-only: when true, completing this side quest REMOVES the items its ITEM objectives require from the
     * player's inventory (the scaled required count per objective, item-only match). DMZ's ITEM objective never
     * consumes, so this is enforced addon-side by {@code QuestItemConsumeHandler} and mirrored on save into
     * {@link net.shurui.dev.sdu.quest.QuestItemConsumeConfig}. Round-trips as {@code sdu_consume_items};
     * default false = today's behaviour (nothing consumed).
     */
    public boolean consumeItems = false;
    public final List<SagaData.Condition> prerequisites = new ArrayList<>();
    public final List<SagaData.Condition> requirements = new ArrayList<>();
    public final List<SagaData.Objective> objectives = new ArrayList<>();
    public final List<SagaData.Reward> rewards = new ArrayList<>();
    /**
     * Top-level keys from the loaded side quest file this model does NOT map to a field (most importantly
     * DMZ's {@code defaultsVersion}). Kept verbatim and re-emitted on save so a round-trip never silently
     * drops a field DMZ relies on; see {@link DmzQuestDefaults} for why dropping {@code defaultsVersion} makes
     * DMZ revert the file. Empty for a brand-new side quest.
     */
    public final JsonObject preservedFields = new JsonObject();

    /** Top-level keys {@link #toJson} writes itself; anything else on a loaded file is preserved. */
    private static final java.util.Set<String> MODELED_KEYS = java.util.Set.of(
            "id", "title", "description", "type", "category", "parallel_objectives", "party_scaling",
            "secret", "claim_mode", "sdu_repeat_interval", "sdu_consume_items", "quest_giver", "turn_in",
            "prerequisites", "requirements", "objectives", "rewards");

    /** Relative path (under {@code sidequests/}) this quest loaded from; blank for a new quest. Kept so a
     *  Save overwrites the original in place (including DMZ defaults, which DMZ only re-creates when absent;
     *  writing elsewhere would leave a duplicate). */
    public String fileName = "";

    /** Deep copy (via bundle round-trip) - used by the editor's copy/paste. */
    public SideQuestData copy() {
        return fromBundle(toBundle());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("description", description);
        o.addProperty("type", questType == null || questType.isBlank() ? "SIDEQUEST" : questType);
        o.addProperty("category", category);
        o.addProperty("parallel_objectives", parallelObjectives);
        o.addProperty("party_scaling", partyScaling);
        o.addProperty("secret", secret);
        o.addProperty("claim_mode", claimMode);
        o.addProperty("sdu_repeat_interval", repeatIntervalSeconds); // addon-only; DMZ ignores it
        o.addProperty("sdu_consume_items", consumeItems); // addon-only; DMZ ignores it
        if (questGiver != null && !questGiver.isBlank()) {
            o.addProperty("quest_giver", questGiver);
        } else {
            o.add("quest_giver", JsonNull.INSTANCE);
        }
        if (turnIn != null && !turnIn.isBlank()) {
            o.addProperty("turn_in", turnIn);
        } else {
            o.add("turn_in", JsonNull.INSTANCE);
        }
        if (!prerequisites.isEmpty()) {
            o.add("prerequisites", conditionsBlock(prerequisites));
        }
        if (!requirements.isEmpty()) {
            o.add("requirements", conditionsBlock(requirements));
        }
        JsonArray objs = new JsonArray();
        for (SagaData.Objective ob : objectives) {
            objs.add(ob.toJson());
        }
        o.add("objectives", objs);
        JsonArray rews = new JsonArray();
        for (SagaData.Reward r : rewards) {
            rews.add(r.toJson());
        }
        o.add("rewards", rews);
        SagaData.writePreserved(o, preservedFields);
        return o;
    }

    public static SideQuestData fromJson(JsonObject o) {
        SideQuestData s = new SideQuestData();
        s.id = str(o, "id", "custom_sidequest");
        s.title = str(o, "title", "Side Quest");
        s.description = str(o, "description", "");
        s.questType = str(o, "type", "SIDEQUEST");
        s.category = str(o, "category", "general");
        s.questGiver = str(o, "quest_giver", "");
        s.turnIn = str(o, "turn_in", "");
        s.parallelObjectives = GsonHelper.getAsBoolean(o, "parallel_objectives", false);
        s.partyScaling = GsonHelper.getAsBoolean(o, "party_scaling", true);
        s.secret = GsonHelper.getAsBoolean(o, "secret", false);
        s.claimMode = str(o, "claim_mode", "TREE_OR_NPC");
        s.repeatIntervalSeconds = GsonHelper.getAsLong(o, "sdu_repeat_interval", 0);
        s.consumeItems = GsonHelper.getAsBoolean(o, "sdu_consume_items", false);
        readConditions(o, "prerequisites", s.prerequisites);
        readConditions(o, "requirements", s.requirements);
        if (o.has("objectives")) {
            for (var el : GsonHelper.getAsJsonArray(o, "objectives")) {
                s.objectives.add(SagaData.Objective.fromJson(el.getAsJsonObject()));
            }
        }
        if (o.has("rewards")) {
            for (var el : GsonHelper.getAsJsonArray(o, "rewards")) {
                s.rewards.add(SagaData.Reward.fromJson(el.getAsJsonObject()));
            }
        }
        SagaData.capturePreserved(o, s.preservedFields, MODELED_KEYS);
        return s;
    }

    /** Bundle for client sync: the quest json plus the on-disk file it came from. */
    public JsonObject toBundle() {
        JsonObject b = new JsonObject();
        b.add("quest", toJson());
        b.addProperty("file", fileName == null ? "" : fileName);
        return b;
    }

    public static SideQuestData fromBundle(JsonObject b) {
        SideQuestData s = fromJson(b.getAsJsonObject("quest"));
        s.fileName = str(b, "file", "");
        return s;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static JsonObject conditionsBlock(List<SagaData.Condition> conditions) {
        JsonObject block = new JsonObject();
        block.addProperty("operator", "AND");
        JsonArray arr = new JsonArray();
        for (SagaData.Condition c : conditions) {
            arr.add(c.toJson());
        }
        block.add("conditions", arr);
        return block;
    }

    private static void readConditions(JsonObject o, String key, List<SagaData.Condition> out) {
        if (o.has(key) && o.get(key).isJsonObject() && o.getAsJsonObject(key).has("conditions")) {
            for (var el : o.getAsJsonObject(key).getAsJsonArray("conditions")) {
                out.add(SagaData.Condition.fromJson(el.getAsJsonObject()));
            }
        }
    }
}
