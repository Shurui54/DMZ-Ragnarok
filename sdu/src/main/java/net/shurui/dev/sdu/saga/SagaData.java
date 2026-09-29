package net.shurui.dev.sdu.saga;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.shurui.dev.sdu.transform.TransformChain;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory model of a DMZ saga + its quests, mirroring the world-save JSON {@code QuestRegistry} reads
 * ({@code dragonminez/sagas/<id>.json} + {@code dragonminez/quests/<folder>/NN_*.json}). Objectives may
 * carry an extra {@code sdu_definition} tag (DMZ ignores it) so our quest-spawn hook can apply a custom
 * NPC definition.
 */
public class SagaData {

    public static final String[] OBJECTIVE_TYPES = {"KILL", "TALK_TO", "ITEM", "DIMENSION", "BIOME", "STRUCTURE", "COORDS", "SKILL"};
    public static final String[] REWARD_TYPES = {"TPS", "ITEM", "SKILL", "TECHNIQUE", "COMMAND", "FORM_PURCHASE"};
    // NOTE: SAGA_QUEST is intentionally NOT here. Quest-completion gating (branching) is done via a
    // quest's PREREQUISITES ("Branch after" in the editor); putting a SAGA_QUEST in a quest's
    // start-REQUIREMENTS creates a second, conflicting linear gate that silently breaks branches.
    public static final String[] CONDITION_TYPES = {"LEVEL", "DIMENSION", "BIOME", "ALIGNMENT", "TIME", "RACE"};
    /**
     * DMZ {@code QuestPrerequisites.TimeMode} values for a TIME condition. GAME_TIME counts world ticks
     * ({@code ticks}, 20/sec, 24000/MC-day); REAL_TIME counts wall-clock {@code milliseconds}
     * (86400000 = 24h). Timer starts once the quest's other start requirements are met, so TIME is how
     * DMZ gates a repeatable/daily quest's cooldown.
     */
    public static final String[] TIME_MODES = {"GAME_TIME", "REAL_TIME"};

    public String id = "custom_saga";
    public String name = "Custom Saga";
    public String previousSaga = "";
    public String questFolder = "saga_custom";
    /**
     * Addon-only saga prerequisite: this saga stays locked until a NAMED quest is completed, in addition to
     * DMZ's native "previous saga complete" gate ({@link #previousSaga}). The reference is a PAIR: the saga
     * that owns the gating quest ({@link #prereqQuestSaga}) plus that quest's numeric id within it
     * ({@link #prereqQuestId}). Both gates are ANDed (a saga unlocks only when EVERY prerequisite is met),
     * matching DMZ's single-gate model where the one gate must be satisfied. Blank saga / id 0 = no quest
     * gate = exactly today's behaviour.
     *
     * <p>The quest is referenced by its STABLE authored id, never by a list index or a synthetic branch handle:
     * the pair {@code (prereqQuestSaga, prereqQuestId)} is exactly DMZ's own quest key
     * ({@code PlayerQuestData.sagaQuestKey}), so it survives quests being added, removed or reordered. DMZ's
     * {@code Saga.SagaRequirements} record can only hold {@code previousSagaId}, so this rides its own sidecar
     * ({@link net.shurui.dev.sdu.saga.SagaQuestGateConfig}, synced to clients) and is enforced by the client
     * quest-tree mixin, exactly where DMZ evaluates the previous-saga lock. Round-trips in the saga manifest as
     * {@code sdu_prereq_saga} / {@code sdu_prereq_quest}.
     */
    public String prereqQuestSaga = "";
    public int prereqQuestId = 0;
    /**
     * Addon-only: when true, this saga's quests may be STARTED (and resummoned) even inside a region whose
     * {@code quest-start} flag is set to deny. The region deny is enforced server-side by shuruisutilities'
     * {@code MixinDmzQuestStartRegion}, which consults {@link net.shurui.dev.sdu.saga.SagaRegionBypass} before
     * refusing; this flag is what that lookup reads. Default false, so existing sagas keep being blocked by the
     * flag. Like {@link #prereqQuestSaga}, DMZ's live {@code Saga} object drops our {@code sdu_*} keys, so the
     * runtime value rides a sidecar ({@link net.shurui.dev.sdu.saga.SagaRegionBypassConfig}); the authoritative
     * copy is the saga manifest, round-tripping as {@code sdu_allow_quest_start_in_blocked_region}.
     */
    public boolean allowStartInQuestBlockedRegion = false;
    public final List<Quest> quests = new ArrayList<>();
    /**
     * Top-level keys from the loaded saga manifest that this model does NOT map to a field (most importantly
     * DMZ's {@code defaultsVersion}). Kept verbatim and re-emitted on save so a round-trip never silently
     * drops a field DMZ relies on; see {@link DmzQuestDefaults} for why dropping {@code defaultsVersion} makes
     * DMZ revert the file. Empty for a brand-new saga.
     */
    public final JsonObject preservedFields = new JsonObject();

    /** Deep copy via JSON round-trip (editor copy/paste). */
    public SagaData copy() {
        return fromBundle(toBundle());
    }

    public static class Quest {
        /** Top-level keys {@link #toJson} writes itself; anything else on a loaded quest file is preserved. */
        private static final java.util.Set<String> MODELED_KEYS = java.util.Set.of(
                "id", "title", "description", "type", "category", "parallel_objectives", "party_scaling",
                "secret", "claim_mode", "sdu_branch", "sdu_time_limit", "sdu_repeat_interval", "sdu_source_path",
                "sdu_consume_items", "prerequisites", "requirements", "objectives", "rewards");

        public int id = 1;
        public String title = "New Quest";
        public String description = "";
        public boolean parallelObjectives = false;
        public boolean partyScaling = true;
        public boolean secret = false;
        public String claimMode = "TREE_OR_NPC";
        /**
         * Addon-only: when true, completing this quest REMOVES the items its ITEM objectives require from the
         * player's inventory (the scaled required count per objective, item-only match, exactly what DMZ counted
         * to mark the objective complete). DMZ's ITEM objective is a "possess N" mirror that never consumes, so
         * this is enforced addon-side by {@code QuestItemConsumeHandler} off DMZ's non-cancelable
         * {@code QuestCompletedEvent}; mirrored on save into {@link net.shurui.dev.sdu.quest.QuestItemConsumeConfig}
         * (DMZ's loaded Quest objects don't carry our sdu_* fields). Round-trips as {@code sdu_consume_items}.
         * Default false = today's behaviour (nothing consumed).
         */
        public boolean consumeItems = false;
        /**
         * Addon-only time limit (seconds) to complete once accepted (0 = untimed). DMZ has no native quest
         * timer, so {@code QuestTimerHandler} enforces it via {@link net.shurui.dev.sdu.quest.QuestTimerConfig};
         * round-trips as {@code sdu_time_limit} (DMZ ignores it).
         */
        public int timeLimit = 0;
        /**
         * Addon-only repeat interval (seconds): after COMPLETION the quest re-unlocks once this many seconds
         * pass (0 = one-shot). DMZ has no repeat-with-cooldown and stores no completion timestamp, so
         * {@code QuestRepeatHandler} drives it via {@link net.shurui.dev.sdu.quest.RepeatConfig} + a persistent
         * {@code QuestRepeatStore}; round-trips as {@code sdu_repeat_interval}. 86400 = daily, 604800 = weekly.
         */
        public long repeatIntervalSeconds = 0;
        /**
         * For a branch quest loaded from a DMZ default side quest, the path (under {@code sidequests/}) it
         * came from. Save writes back to that path (only touching title/description/timer, keeping DMZ's
         * objectives/prereqs/giver) so a default saga's side quests show in the editor without duplicating
         * or corrupting them. Blank for our own branch quests (written to {@code sidequests/<questFolder>/}).
         */
        public String sourcePath = "";

        /**
         * The quest id this side quest already has in its OWN file, for a DMZ default we loaded as a branch.
         *
         * <p>Empty for our own branches, which are written by us and get their id from {@link #branchSid}. It matters
         * because a default side quest's id was chosen by whoever authored it, and nothing about it can be derived
         * from the saga: the numeric id the editor gives it is a synthetic handle assigned at load, not something the
         * file has ever heard of.
         */
        public String sourceId = "";

        /**
         * The parent's {@link #chainId} as recorded when this branch was saved, or empty for an older file.
         *
         * <p>Resolved back to a live parent by {@code SagaFileManager} once every quest in the saga is loaded.
         * Transient: it is a load-time repair, not part of the model the editor edits.
         */
        public transient String branchParentSid = "";
        /**
         * True = this quest branches off the main line. DMZ's main line is one straight row, so a side-path
         * quest must be a SIDEQUEST attached (via its {@code prerequisites} parent) to a main-line quest or
         * another branch. Saved to {@code sidequests/<questFolder>/} instead of the saga's quest folder.
         */
        public boolean branch = false;
        public final List<Condition> prerequisites = new ArrayList<>();
        public final List<Condition> requirements = new ArrayList<>();
        public final List<Objective> objectives = new ArrayList<>();
        public final List<Reward> rewards = new ArrayList<>();
        /**
         * Top-level keys from the loaded quest file this model does NOT map to a field (most importantly DMZ's
         * {@code defaultsVersion}). Kept verbatim and re-emitted on save so a round-trip never silently drops
         * a field DMZ relies on; see {@link DmzQuestDefaults}. Empty for a brand-new quest.
         */
        public final JsonObject preservedFields = new JsonObject();

        /** Deep copy via JSON round-trip (editor copy/paste). */
        public Quest copy() {
            Quest q = fromJson(toJson(""));
            q.sourcePath = ""; // a copy is a new custom branch, not tied to the original DMZ file
            return q;
        }

        public JsonObject toJson(String category) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("title", title);
            o.addProperty("description", description);
            o.addProperty("type", "SAGA");
            o.addProperty("category", category);
            o.addProperty("parallel_objectives", parallelObjectives);
            o.addProperty("party_scaling", partyScaling);
            o.addProperty("secret", secret);
            o.addProperty("claim_mode", claimMode);
            o.addProperty("sdu_branch", branch); // addon-only; DMZ ignores it. Round-trips in the editor bundle.
            o.addProperty("sdu_time_limit", timeLimit);
            o.addProperty("sdu_repeat_interval", repeatIntervalSeconds);
            o.addProperty("sdu_source_path", sourcePath);
            o.addProperty("sdu_consume_items", consumeItems);
            if (!prerequisites.isEmpty()) {
                o.add("prerequisites", conditionsBlock(prerequisites));
            }
            o.add("requirements", conditionsBlock(requirements));
            JsonArray objs = new JsonArray();
            for (Objective ob : objectives) {
                objs.add(ob.toJson());
            }
            o.add("objectives", objs);
            JsonArray rews = new JsonArray();
            for (Reward r : rewards) {
                rews.add(r.toJson());
            }
            o.add("rewards", rews);
            writePreserved(o, preservedFields);
            return o;
        }

        public static Quest fromJson(JsonObject o) {
            Quest q = new Quest();
            q.id = GsonHelper.getAsInt(o, "id", 1);
            q.title = GsonHelper.getAsString(o, "title", "Quest");
            q.description = GsonHelper.getAsString(o, "description", "");
            q.parallelObjectives = GsonHelper.getAsBoolean(o, "parallel_objectives", false);
            q.partyScaling = GsonHelper.getAsBoolean(o, "party_scaling", true);
            q.secret = GsonHelper.getAsBoolean(o, "secret", false);
            q.claimMode = GsonHelper.getAsString(o, "claim_mode", "TREE_OR_NPC");
            q.branch = GsonHelper.getAsBoolean(o, "sdu_branch", false);
            q.timeLimit = GsonHelper.getAsInt(o, "sdu_time_limit", 0);
            q.repeatIntervalSeconds = GsonHelper.getAsLong(o, "sdu_repeat_interval", 0);
            q.sourcePath = GsonHelper.getAsString(o, "sdu_source_path", "");
            q.consumeItems = GsonHelper.getAsBoolean(o, "sdu_consume_items", false);
            readConditions(o, "prerequisites", q.prerequisites);
            readConditions(o, "requirements", q.requirements);
            // footgun: a SAGA_QUEST in start-requirements is a linear gate that conflicts with (and
            // overrides) prereq branching. branching belongs only in prerequisites ("Branch after"),
            // so drop any SAGA_QUEST requirement on load.
            q.requirements.removeIf(c -> "SAGA_QUEST".equals(c.type));
            if (o.has("objectives")) {
                for (var el : GsonHelper.getAsJsonArray(o, "objectives")) {
                    q.objectives.add(Objective.fromJson(el.getAsJsonObject()));
                }
            }
            if (o.has("rewards")) {
                for (var el : GsonHelper.getAsJsonArray(o, "rewards")) {
                    q.rewards.add(Reward.fromJson(el.getAsJsonObject()));
                }
            }
            capturePreserved(o, q.preservedFields, MODELED_KEYS);
            return q;
        }

        /** The quest id this quest branches from (its SAGA_QUEST prereq questId), or 0 = root/none. */
        public int branchParentId() {
            for (Condition c : prerequisites) {
                if ("SAGA_QUEST".equals(c.type)) {
                    return c.questId;
                }
            }
            return 0;
        }

        /** Stable SIDEQUEST string id for this branch quest within its saga. */
        public String branchSid(String sagaId) {
            return sagaId + "_b" + id;
        }

        /**
         * The id another quest must name to chain off this one.
         *
         * <p>For our own branches that is {@link #branchSid}, the id we write into the file. For a DMZ DEFAULT side
         * quest it has to be the id already in ITS file, because we do not write that file's id and nothing would
         * ever answer to a synthesized one.
         *
         * <p>This is the whole reason branching off a default side quest did not work. The chain was always built
         * from {@code branchSid}, which for a default quest produced something like {@code mysaga_b10000} from the
         * synthetic handle the editor had assigned at load. No file has that id, so DMZ could never satisfy the
         * prerequisite and the child quest simply never unlocked, with nothing logged to say why.
         */
        public String chainId(String sagaId) {
            return sourceId == null || sourceId.isBlank() ? branchSid(sagaId) : sourceId;
        }

        /**
         * Serialize this branch quest as a DMZ SIDEQUEST hanging off its parent in {@code saga}: a
         * {@code SAGA_QUEST} prereq if the parent is a main-line quest, a {@code QUEST} chain if it's
         * another branch. Extra {@code sdu_*} metadata (DMZ ignores it) lets us reload it as a branch quest.
         */
        public JsonObject toSideQuestJson(SagaData saga) {
            JsonObject o = new JsonObject();
            o.addProperty("id", branchSid(saga.id));
            o.addProperty("title", title);
            o.addProperty("description", description);
            o.addProperty("type", "SIDEQUEST");
            o.addProperty("category", saga.questFolder);
            o.addProperty("quest_giver", "");
            o.addProperty("turn_in", "");
            o.addProperty("parallel_objectives", parallelObjectives);
            o.addProperty("party_scaling", partyScaling);
            o.addProperty("secret", secret);
            o.addProperty("claim_mode", claimMode);
            int pid = branchParentId();
            Quest parent = null;
            for (Quest q : saga.quests) {
                if (q.id == pid) {
                    parent = q;
                    break;
                }
            }
            JsonArray conds = new JsonArray();
            if (parent != null) {
                JsonObject c = new JsonObject();
                if (parent.branch) {
                    c.addProperty("type", "QUEST");
                    // chain to the parent branch by the id THAT file actually carries: ours, or a DMZ default's own
                    c.addProperty("questId", parent.chainId(saga.id));
                } else {
                    c.addProperty("type", "SAGA_QUEST");
                    c.addProperty("sagaId", saga.id);
                    c.addProperty("questId", parent.id); // attach to a main-line quest
                }
                conds.add(c);
            }
            JsonObject prereq = new JsonObject();
            prereq.addProperty("operator", "AND");
            prereq.add("conditions", conds);
            o.add("prerequisites", prereq);
            o.add("requirements", conditionsBlock(requirements));
            JsonArray objs = new JsonArray();
            for (Objective ob : objectives) {
                objs.add(ob.toJson());
            }
            o.add("objectives", objs);
            JsonArray rews = new JsonArray();
            for (Reward r : rewards) {
                rews.add(r.toJson());
            }
            o.add("rewards", rews);
            o.addProperty("sdu_branch", true);
            o.addProperty("sdu_saga", saga.id);
            o.addProperty("sdu_branch_id", id);
            o.addProperty("sdu_branch_parent", pid);
            // The numeric handle above is only meaningful within one editor session: a DMZ default side quest's id
            // is SYNTHETIC, handed out in directory-walk order at load, so adding or removing one file shifts every
            // handle after it and a stored number would quietly start naming a different quest. The parent's real
            // chain id does not move, so it is written too and preferred on load.
            if (parent != null) {
                o.addProperty("sdu_branch_parent_sid", parent.chainId(saga.id));
            }
            o.addProperty("sdu_time_limit", timeLimit);
            o.addProperty("sdu_repeat_interval", repeatIntervalSeconds);
            o.addProperty("sdu_consume_items", consumeItems);
            return o;
        }

        /** Rebuild a branch Quest from a SIDEQUEST file we wrote, or null if it isn't one of ours. */
        public static Quest fromSideQuestJson(JsonObject o) {
            if (!GsonHelper.getAsBoolean(o, "sdu_branch", false)) {
                return null;
            }
            Quest q = new Quest();
            q.branch = true;
            q.id = GsonHelper.getAsInt(o, "sdu_branch_id", 1);
            q.title = GsonHelper.getAsString(o, "title", "Branch Quest");
            q.description = GsonHelper.getAsString(o, "description", "");
            q.parallelObjectives = GsonHelper.getAsBoolean(o, "parallel_objectives", false);
            q.partyScaling = GsonHelper.getAsBoolean(o, "party_scaling", true);
            q.secret = GsonHelper.getAsBoolean(o, "secret", false);
            q.claimMode = GsonHelper.getAsString(o, "claim_mode", "TREE_OR_NPC");
            q.timeLimit = GsonHelper.getAsInt(o, "sdu_time_limit", 0);
            q.repeatIntervalSeconds = GsonHelper.getAsLong(o, "sdu_repeat_interval", 0);
            q.consumeItems = GsonHelper.getAsBoolean(o, "sdu_consume_items", false);
            q.branchParentSid = GsonHelper.getAsString(o, "sdu_branch_parent_sid", "");
            int parent = GsonHelper.getAsInt(o, "sdu_branch_parent", 0);
            if (parent > 0) {
                Condition c = new Condition();
                c.type = "SAGA_QUEST";
                c.sagaId = GsonHelper.getAsString(o, "sdu_saga", "");
                c.questId = parent;
                q.prerequisites.add(c);
            }
            readConditions(o, "requirements", q.requirements);
            q.requirements.removeIf(c -> "SAGA_QUEST".equals(c.type));
            if (o.has("objectives")) {
                for (var el : GsonHelper.getAsJsonArray(o, "objectives")) {
                    q.objectives.add(Objective.fromJson(el.getAsJsonObject()));
                }
            }
            if (o.has("rewards")) {
                for (var el : GsonHelper.getAsJsonArray(o, "rewards")) {
                    q.rewards.add(Reward.fromJson(el.getAsJsonObject()));
                }
            }
            return q;
        }

        /**
         * If a DMZ side quest attaches to {@code sagaId} via a SAGA_QUEST prereq, the main-line quest id
         * it hangs off; else -1. That's DMZ's link from a side quest into a saga's tree.
         */
        public static int sideQuestSagaParent(JsonObject o, String sagaId) {
            if (o == null || !o.has("prerequisites") || !o.get("prerequisites").isJsonObject()) {
                return -1;
            }
            JsonObject pre = o.getAsJsonObject("prerequisites");
            if (!pre.has("conditions") || !pre.get("conditions").isJsonArray()) {
                return -1;
            }
            for (var el : pre.getAsJsonArray("conditions")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                if ("SAGA_QUEST".equals(GsonHelper.getAsString(c, "type", ""))
                        && sagaId.equals(GsonHelper.getAsString(c, "sagaId", ""))) {
                    return GsonHelper.getAsInt(c, "questId", -1);
                }
            }
            return -1;
        }

        /**
         * Build a branch quest from a DMZ default side quest so it shows in the editor like a custom branch.
         * {@code relPath} (under {@code sidequests/}) is kept in {@link #sourcePath} so save writes back to
         * the original file. Objective/reward parsing is guarded: an unknown type is skipped for display but
         * preserved on save from the original file.
         */
        public static Quest fromDmzSideQuest(JsonObject o, String relPath, int synthId) {
            Quest q = new Quest();
            q.branch = true;
            q.id = synthId;
            q.sourcePath = relPath;
            q.sourceId = GsonHelper.getAsString(o, "id", "");
            q.title = GsonHelper.getAsString(o, "title", relPath);
            q.description = GsonHelper.getAsString(o, "description", "");
            q.parallelObjectives = GsonHelper.getAsBoolean(o, "parallel_objectives", false);
            q.partyScaling = GsonHelper.getAsBoolean(o, "party_scaling", true);
            q.secret = GsonHelper.getAsBoolean(o, "secret", false);
            q.claimMode = GsonHelper.getAsString(o, "claim_mode", "TREE_OR_NPC");
            q.timeLimit = GsonHelper.getAsInt(o, "sdu_time_limit", 0);
            q.repeatIntervalSeconds = GsonHelper.getAsLong(o, "sdu_repeat_interval", 0);
            q.consumeItems = GsonHelper.getAsBoolean(o, "sdu_consume_items", false);
            readConditions(o, "prerequisites", q.prerequisites); // keeps the SAGA_QUEST link (branchParentId)
            readConditions(o, "requirements", q.requirements);
            if (o.has("objectives") && o.get("objectives").isJsonArray()) {
                for (var el : o.getAsJsonArray("objectives")) {
                    try {
                        q.objectives.add(Objective.fromJson(el.getAsJsonObject()));
                    } catch (Exception ignored) {
                    }
                }
            }
            if (o.has("rewards") && o.get("rewards").isJsonArray()) {
                for (var el : o.getAsJsonArray("rewards")) {
                    try {
                        q.rewards.add(Reward.fromJson(el.getAsJsonObject()));
                    } catch (Exception ignored) {
                    }
                }
            }
            return q;
        }

        private static JsonObject conditionsBlock(List<Condition> conditions) {
            JsonObject block = new JsonObject();
            block.addProperty("operator", "AND");
            JsonArray arr = new JsonArray();
            for (Condition c : conditions) {
                arr.add(c.toJson());
            }
            block.add("conditions", arr);
            return block;
        }

        private static void readConditions(JsonObject o, String key, List<Condition> out) {
            if (o.has(key) && o.getAsJsonObject(key).has("conditions")) {
                for (var el : o.getAsJsonObject(key).getAsJsonArray("conditions")) {
                    out.add(Condition.fromJson(el.getAsJsonObject()));
                }
            }
        }
    }

    public static class Objective {
        public String type = "KILL";
        public String entity = "dmz_ragnarok:dmz_fighter";
        public String definition = "";     // our sdu_definition tag (empty = none)
        /**
         * KILL only (addon): which ragnarok NPC character the spawned entity wears, when {@link #entity} above is
         * the ragnarok NPC type. The whole cast shares that ONE entity type, so the entity picker alone can only
         * ever produce the default look. Round-trips via {@code sdu_rg_model} (DMZ ignores it) and is applied on
         * the quest spawn by {@link SagaSpawnLook}; blank leaves the entity alone.
         */
        public String rgModel = "";
        public int count = 1;
        public float health = 100f;
        public float meleeDamage = 10f;
        public float kiDamage = 20f;
        /**
         * KILL only: DMZ's {@code AITier}. Matches DMZ's own sentinel exactly: {@code -1} = Auto, i.e. no
         * {@code AITier} key on disk, which makes DMZ scale the spawned NPC's AI tier with the server
         * difficulty ({@code QuestService}: {@code getAiTier() > 0 ? getAiTier() : difficulty.aiTierId()}).
         * A positive value ({@code 1}=Simple, {@code 2}=Tactical, {@code 3}=Advanced) forces that fixed tier.
         * DMZ's {@code QuestDefaults} never writes {@code AITier} for its own quests, so the default MUST be
         * {@code -1}, or loading then re-saving a DMZ quest would stamp a fixed tier 1 and silently drop the
         * difficulty scaling (the "saga NPC AI reverts to defaults" bug). See {@link #toJson}/{@link #fromJson}.
         */
        public int aiTier = -1;
        public String npcId = "";
        /**
         * TALK_TO only (addon): a display name that also satisfies the objective. Talking to any living
         * entity with this exact name (CNPCs, sdu fighters, named mobs) completes it, alongside DMZ's own
         * npcId match. Round-trips via {@code sdu_talk_npc_name} (DMZ ignores it); enforced by {@link TalkNpcRegistry}.
         */
        public String npcName = "";
        public String dimension = "minecraft:overworld";
        public String biome = "minecraft:plains";
        public String item = "minecraft:diamond";
        public String skill = "";
        public int level = 1;
        public String structure = "";
        // COORDS objective: explicit world pos (+ arrival radius) to reach. DMZ parses it natively as a
        // CoordsObjective; our HUD compass points at it and it beats STRUCTURE/BIOME/kill targets, so it's
        // the "manually pin the waypoint here" objective.
        public int coordX = 0;
        public int coordY = 64;
        public int coordZ = 0;
        public int radius = 4;
        /**
         * KILL only (addon): hold this objective's quest-spawned NPCs back until the player reaches the
         * quest's COORDS location (see {@code DeferredSpawnRegistry}). Needs a COORDS objective too;
         * round-trips via {@code sdu_defer_spawn} (DMZ ignores it).
         */
        public boolean deferSpawnUntilLocation = false;
        /**
         * KILL only (addon): custom NPC transform chain (see {@code transform/}). Non-empty overrides DMZ's
         * built-in low-HP transformations; {@code TransformEngine} drives it as HP falls. Round-trips via
         * {@code sduTransforms} (DMZ ignores it); applied to the fighter via {@link SagaSpawnBindings} /
         * {@code SduDmzFighter}.
         */
        public TransformChain transformChain = new TransformChain();
        /**
         * KILL only (addon): keep DMZ's built-in transformations on for this NPC. Only consulted when
         * {@link #transformChain} is empty (a custom chain always wins). Off = an NPC that never transforms.
         * Round-trips via {@code sduUseDefaultTransform} (DMZ ignores it).
         */
        public boolean useDefaultTransform = true;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            switch (type) {
                case "KILL" -> {
                    o.addProperty("entity", entity);
                    o.addProperty("count", count);
                    o.addProperty("health", health);
                    o.addProperty("meleeDamage", meleeDamage);
                    o.addProperty("kiDamage", kiDamage);
                    o.addProperty("spawn", "QUEST");
                    o.addProperty("count_mode", "QUEST_SPAWNED_ONLY");
                    // Only emit AITier for an explicit fixed tier. Auto (-1) is written as ABSENT, exactly the
                    // form DMZ's own QuestDefaults writes and QuestParser reads back as -1, so the NPC keeps
                    // DMZ's difficulty-scaled AI. Always writing it (e.g. AITier:1) would pin every unset
                    // objective to a fixed tier and lose that scaling.
                    if (aiTier > 0) {
                        o.addProperty("AITier", aiTier);
                    }
                    if (definition != null && !definition.isBlank()) {
                        o.addProperty("sdu_definition", definition);
                    }
                    if (rgModel != null && !rgModel.isBlank()) {
                        o.addProperty("sdu_rg_model", rgModel);
                    }
                    if (deferSpawnUntilLocation) {
                        o.addProperty("sdu_defer_spawn", true);
                    }
                    // addon-only transform config (DMZ ignores these keys), like the sdu_definition passenger
                    o.add("sduTransforms", transformChain.toJson());
                    o.addProperty("sduUseDefaultTransform", useDefaultTransform);
                }
                case "TALK_TO" -> {
                    o.addProperty("npcId", npcId);
                    if (npcName != null && !npcName.isBlank()) {
                        o.addProperty("sdu_talk_npc_name", npcName);
                    }
                }
                case "ITEM" -> {
                    o.addProperty("item", item);
                    o.addProperty("count", count);
                }
                case "DIMENSION" -> o.addProperty("dimension", dimension);
                case "BIOME" -> o.addProperty("biome", biome);
                case "STRUCTURE" -> o.addProperty("structure", structure);
                case "COORDS" -> {
                    o.addProperty("x", coordX);
                    o.addProperty("y", coordY);
                    o.addProperty("z", coordZ);
                    o.addProperty("radius", radius);
                }
                case "SKILL" -> {
                    o.addProperty("skill", skill);
                    o.addProperty("level", level);
                }
                default -> { }
            }
            return o;
        }

        public static Objective fromJson(JsonObject o) {
            Objective ob = new Objective();
            ob.type = GsonHelper.getAsString(o, "type", "KILL");
            // Normalize a possibly pre-merge id (sdu:...) to dmz_ragnarok, so a saga saved before the merge keeps
            // resolving and re-saves in the new form. The npc->fighter migration below is checked in the NEW namespace
            // so it survives the id rename.
            ob.entity = net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(
                    GsonHelper.getAsString(o, "entity", "dmz_ragnarok:dmz_fighter"));
            // sagas saved before the entity rename still hold the retired npc id (dmz_ragnarok:npc once normalized)
            if ((net.shurui.shuruisutilities.ragnarok.LegacyIds.NEW_NAMESPACE + ":npc").equals(ob.entity)) {
                ob.entity = net.shurui.shuruisutilities.ragnarok.LegacyIds.NEW_NAMESPACE + ":dmz_fighter";
            }
            ob.definition = GsonHelper.getAsString(o, "sdu_definition", "");
            ob.rgModel = GsonHelper.getAsString(o, "sdu_rg_model", "");
            ob.count = GsonHelper.getAsInt(o, "count", 1);
            ob.health = GsonHelper.getAsFloat(o, "health", 100f);
            ob.meleeDamage = GsonHelper.getAsFloat(o, "meleeDamage", 10f);
            ob.kiDamage = GsonHelper.getAsFloat(o, "kiDamage", 20f);
            // Match DMZ's QuestParser: absent AITier means -1 (Auto / scale with difficulty), NOT tier 1.
            ob.aiTier = GsonHelper.getAsInt(o, "AITier", -1);
            ob.npcId = GsonHelper.getAsString(o, "npcId", "");
            ob.npcName = GsonHelper.getAsString(o, "sdu_talk_npc_name", "");
            ob.dimension = GsonHelper.getAsString(o, "dimension", "minecraft:overworld");
            ob.biome = GsonHelper.getAsString(o, "biome", "minecraft:plains");
            ob.item = GsonHelper.getAsString(o, "item", "minecraft:diamond");
            ob.skill = GsonHelper.getAsString(o, "skill", "");
            ob.level = GsonHelper.getAsInt(o, "level", 1);
            ob.structure = GsonHelper.getAsString(o, "structure", "");
            ob.coordX = GsonHelper.getAsInt(o, "x", 0);
            ob.coordY = GsonHelper.getAsInt(o, "y", 64);
            ob.coordZ = GsonHelper.getAsInt(o, "z", 0);
            ob.radius = GsonHelper.getAsInt(o, "radius", 4);
            ob.deferSpawnUntilLocation = GsonHelper.getAsBoolean(o, "sdu_defer_spawn", false);
            ob.transformChain = o.has("sduTransforms") && o.get("sduTransforms").isJsonObject()
                    ? TransformChain.fromJson(o.getAsJsonObject("sduTransforms"))
                    : new TransformChain();
            ob.useDefaultTransform = GsonHelper.getAsBoolean(o, "sduUseDefaultTransform", true);
            return ob;
        }

        public String summary() {
            return switch (type) {
                case "KILL" -> "Kill " + count + "x " + (definition.isBlank() ? entity : definition);
                case "TALK_TO" -> "Talk to " + (npcName != null && !npcName.isBlank() ? npcName : npcId);
                case "ITEM" -> "Collect " + count + "x " + item;
                case "DIMENSION" -> "Reach " + dimension;
                case "BIOME" -> "Reach " + biome;
                case "STRUCTURE" -> "Find " + structure;
                case "COORDS" -> "Go to " + coordX + " " + coordY + " " + coordZ;
                case "SKILL" -> "Skill " + skill + " lvl " + level;
                default -> type;
            };
        }
    }

    public static class Reward {
        public String type = "TPS";
        public int amount = 1000;
        public String item = "minecraft:diamond";
        public int count = 1;
        public String skill = "";
        public int level = 1;
        public String command = "";
        /** For a TECHNIQUE reward: the technique id (kamehameha, galick_gun, …). */
        public String technique = "";
        /**
         * ITEM only (addon): optional SNBT tag (a {@code {...}} compound) applied to the granted stack:
         * enchantments, a custom name, modded data, anything. This is the "custom nbt like other rewards do"
         * request, matching the suite's existing SNBT-on-item convention ({@code CustomDrop.nbt}, applied via
         * {@code TagParser.parseTag}). DMZ's own ITEM reward carries no NBT, so when this is set the reward is
         * serialized as a vanilla {@code /give} COMMAND reward (exactly how the TECHNIQUE reward rides a COMMAND),
         * with {@code sdu_item*} markers so the editor reloads it as an ITEM+nbt reward. Blank = a plain ITEM
         * reward, byte-for-byte as today (and difficulty reward-scaling is kept in that case). Malformed SNBT
         * falls back to a plain ITEM reward rather than a broken command, mirroring {@code CustomDrop}.
         */
        public String nbt = "";
        /**
         * FORM_PURCHASE reward: the DMZ {@code group.form} skill key the quest unlocks for purchase.
         * Completing the quest lets the player spend TP to buy the form at its normal price; it's not
         * granted. Persisted into {@code form_quest_gates.json} on saga save (see
         * {@link net.shurui.dev.sdu.form.FormQuestGateConfig}); the reward row is display-only
         * ({@code FormPurchaseReward.giveReward} no-ops).
         */
        public String formKey = "";

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            switch (type) {
                case "TPS" -> {
                    o.addProperty("type", type);
                    o.addProperty("amount", amount);
                }
                case "ITEM" -> {
                    String tag = nbt == null ? "" : nbt.trim();
                    boolean tagValid = false;
                    if (!tag.isEmpty()) {
                        try {
                            net.minecraft.nbt.TagParser.parseTag(tag);
                            tagValid = true;
                        } catch (Exception ignored) {
                            // Malformed SNBT: fall through to a plain ITEM reward rather than a broken /give,
                            // mirroring CustomDrop's "drop the plain item rather than nothing" philosophy.
                        }
                    }
                    if (!tagValid) {
                        // Plain ITEM reward, byte-for-byte as before (keeps DMZ's difficulty reward-scaling).
                        o.addProperty("type", type);
                        o.addProperty("item", item);
                        o.addProperty("count", count);
                    } else {
                        // DMZ's ITEM reward can't carry NBT, so grant the tagged stack via a vanilla /give run
                        // by DMZ's CommandReward (permission-4 server source), exactly how TECHNIQUE rides a
                        // COMMAND. The sdu_item* markers (DMZ ignores them) let the editor reload it as ITEM+nbt.
                        int giveCount = Math.max(1, count);
                        o.addProperty("type", "COMMAND");
                        o.addProperty("command", "give %player% " + item + tag + " " + giveCount);
                        o.addProperty("translationKey", "gui.dmz_ragnarok.quests.rewards.item_nbt");
                        o.addProperty("sdu_item", item);
                        o.addProperty("sdu_item_count", count);
                        o.addProperty("sdu_item_nbt", tag);
                    }
                }
                case "SKILL" -> {
                    o.addProperty("type", type);
                    o.addProperty("skill", skill);
                    o.addProperty("level", level);
                }
                case "COMMAND" -> {
                    o.addProperty("type", type);
                    o.addProperty("command", command);
                }
                case "TECHNIQUE" -> {
                    // DMZ can't parse a KI_TECHNIQUE reward, so grant it via a COMMAND that runs our
                    // /rg npc givetechnique. The sdu_technique tag (ignored by DMZ) lets the editor load
                    // it back as a TECHNIQUE reward.
                    o.addProperty("type", "COMMAND");
                    o.addProperty("command", "sdu givetechnique %player% " + technique);
                    o.addProperty("sdu_technique", technique);
                }
                case "FORM_PURCHASE" -> {
                    // editor-only marker reward. DMZ's QuestParser.parseReward returns null for unknown
                    // reward types (parseRewardList skips nulls), so this row is harmless on disk while it
                    // round-trips in our editor. the real effect (unlocking the buy) is persisted separately
                    // into form_quest_gates.json on saga save (SaveSagaPacket -> FormQuestGateConfig),
                    // keyed to the quest's completion.
                    o.addProperty("type", "FORM_PURCHASE");
                    o.addProperty("form", formKey);
                }
                default -> o.addProperty("type", type);
            }
            return o;
        }

        public static Reward fromJson(JsonObject o) {
            Reward r = new Reward();
            r.type = GsonHelper.getAsString(o, "type", "TPS");
            r.amount = GsonHelper.getAsInt(o, "amount", 1000);
            r.item = GsonHelper.getAsString(o, "item", "minecraft:diamond");
            r.count = GsonHelper.getAsInt(o, "count", 1);
            r.skill = GsonHelper.getAsString(o, "skill", "");
            r.level = GsonHelper.getAsInt(o, "level", 1);
            r.command = GsonHelper.getAsString(o, "command", "");
            r.formKey = GsonHelper.getAsString(o, "form", GsonHelper.getAsString(o, "formKey", ""));
            // a COMMAND reward we wrote for a technique carries sdu_technique; surface it as TECHNIQUE
            if (o.has("sdu_technique")) {
                r.type = "TECHNIQUE";
                r.technique = GsonHelper.getAsString(o, "sdu_technique", "");
            }
            // a COMMAND reward we wrote for a tagged item carries sdu_item*; surface it as ITEM+nbt
            if (o.has("sdu_item_nbt")) {
                r.type = "ITEM";
                r.item = GsonHelper.getAsString(o, "sdu_item", r.item);
                r.count = GsonHelper.getAsInt(o, "sdu_item_count", r.count);
                r.nbt = GsonHelper.getAsString(o, "sdu_item_nbt", "");
            }
            return r;
        }

        public String summary() {
            return switch (type) {
                case "TPS" -> amount + " TP";
                case "ITEM" -> count + "x " + item;
                case "SKILL" -> "Skill " + skill + " lvl " + level;
                case "TECHNIQUE" -> "Technique " + technique;
                case "COMMAND" -> "/" + command;
                case "FORM_PURCHASE" -> "Form Purchase: " + formKey;
                default -> type;
            };
        }
    }

    public static class Condition {
        public String type = "LEVEL";
        public int minLevel = 1;
        public String dimension = "minecraft:overworld";
        public String biome = "minecraft:plains";
        public int min = 0;
        public int max = 100;
        public String sagaId = "";
        public int questId = 1;
        /**
         * QUEST conditions store {@code questId} as a STRING (a side quest id), not an int, since DMZ's
         * default side quests chain that way. Kept separate so those files load instead of throwing
         * "Expected questId to be a Int".
         */
        public String requiredQuestId = "";
        /** For a {@code TIME} condition: {@link SagaData#TIME_MODES} value ("GAME_TIME"/"REAL_TIME"). */
        public String timeMode = "REAL_TIME";
        /** For a {@code TIME} condition: the delay, in ticks (GAME_TIME) or milliseconds (REAL_TIME). */
        public long timeAmount = 0;
        /**
         * RACE condition: DMZ race id, lowercased to match DMZ's {@code getRaceName()} (case-insensitive
         * compare). Put in prerequisites to hide the quest for the wrong race; DMZ's QuestParser reads the
         * {@code "race"} key.
         */
        public String race = "human";

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            switch (type) {
                case "LEVEL" -> o.addProperty("minLevel", minLevel);
                case "DIMENSION" -> o.addProperty("dimension", dimension);
                case "BIOME" -> o.addProperty("biome", biome);
                case "ALIGNMENT" -> {
                    o.addProperty("min", min);
                    o.addProperty("max", max);
                }
                case "TIME" -> {
                    // DMZ keys duration off mode: GAME_TIME reads "ticks", REAL_TIME reads "milliseconds"
                    // (parseTimeDuration). write only the matching key.
                    o.addProperty("mode", timeMode);
                    if ("REAL_TIME".equals(timeMode)) {
                        o.addProperty("milliseconds", timeAmount);
                    } else {
                        o.addProperty("ticks", timeAmount);
                    }
                }
                case "SAGA_QUEST" -> {
                    o.addProperty("sagaId", sagaId);
                    o.addProperty("questId", questId);
                }
                case "QUEST" -> o.addProperty("questId", requiredQuestId); // string id
                case "RACE" -> o.addProperty("race", race); // DMZ QuestParser reads "race" (lowercase id)
                default -> { }
            }
            return o;
        }

        public static Condition fromJson(JsonObject o) {
            Condition c = new Condition();
            c.type = GsonHelper.getAsString(o, "type", "LEVEL");
            c.minLevel = GsonHelper.getAsInt(o, "minLevel", 1);
            c.dimension = GsonHelper.getAsString(o, "dimension", "minecraft:overworld");
            c.biome = GsonHelper.getAsString(o, "biome", "minecraft:plains");
            c.min = GsonHelper.getAsInt(o, "min", 0);
            c.max = GsonHelper.getAsInt(o, "max", 100);
            c.sagaId = GsonHelper.getAsString(o, "sagaId", "");
            // DMZ accepts race/raceName/race_name; read whichever's present, lowercased for its
            // case-insensitive compare against getRaceName()
            String raceVal = GsonHelper.getAsString(o, "race",
                    GsonHelper.getAsString(o, "raceName", GsonHelper.getAsString(o, "race_name", "human")));
            c.race = raceVal == null ? "human" : raceVal.toLowerCase(java.util.Locale.ROOT);
            // TIME: infer mode from whichever duration key is present (DMZ writes only one), falling back
            // to the "mode" field. milliseconds => REAL_TIME, ticks => GAME_TIME.
            c.timeMode = GsonHelper.getAsString(o, "mode", "GAME_TIME");
            if (o.has("milliseconds")) {
                c.timeAmount = GsonHelper.getAsLong(o, "milliseconds", 0);
                c.timeMode = "REAL_TIME";
            } else if (o.has("ticks")) {
                c.timeAmount = GsonHelper.getAsLong(o, "ticks", 0);
                c.timeMode = "GAME_TIME";
            }
            // questId is an int for SAGA_QUEST but a string for QUEST; read whichever's present
            if (o.has("questId") && o.get("questId").isJsonPrimitive()) {
                var prim = o.getAsJsonPrimitive("questId");
                if (prim.isNumber()) {
                    c.questId = prim.getAsInt();
                } else {
                    c.requiredQuestId = prim.getAsString();
                }
            }
            return c;
        }

        public String summary() {
            return switch (type) {
                case "LEVEL" -> "Level >= " + minLevel;
                case "DIMENSION" -> "In " + dimension;
                case "BIOME" -> "In " + biome;
                case "ALIGNMENT" -> "Alignment " + min + "-" + max;
                case "TIME" -> "After " + timeAmount + ("REAL_TIME".equals(timeMode) ? " ms" : " ticks");
                case "SAGA_QUEST" -> "Done " + sagaId + " #" + questId;
                case "QUEST" -> "Done " + requiredQuestId;
                case "RACE" -> "Race: " + race;
                default -> type;
            };
        }
    }

    /**
     * Copy every top-level key of {@code src} that is NOT one this model already maps to a field into
     * {@code preserved}, so a save can re-emit them verbatim. {@code modeledKeys} is the set of keys the
     * caller's {@code toJson} writes itself; everything else (DMZ's {@code defaultsVersion}, any future DMZ
     * field we don't understand yet) is carried through untouched rather than silently dropped.
     */
    static void capturePreserved(JsonObject src, JsonObject preserved, java.util.Set<String> modeledKeys) {
        preserved.entrySet().clear();
        if (src == null) {
            return;
        }
        for (String key : src.keySet()) {
            if (!modeledKeys.contains(key)) {
                preserved.add(key, src.get(key));
            }
        }
    }

    /**
     * Re-emit preserved keys onto {@code target} (a freshly built {@code toJson} object), then guarantee a
     * {@code defaultsVersion} is present. If the loaded file carried one it round-trips as-is; if it did not
     * (a file a previous save already stripped) we stamp DMZ's current {@link DmzQuestDefaults#DEFAULTS_VERSION}
     * so DMZ stops reverting the file. A modeled key already on {@code target} always wins over a stale
     * preserved copy, so live edits are never clobbered.
     */
    static void writePreserved(JsonObject target, JsonObject preserved) {
        for (var e : preserved.entrySet()) {
            if (!target.has(e.getKey())) {
                target.add(e.getKey(), e.getValue());
            }
        }
        if (!target.has(DmzQuestDefaults.VERSION_KEY)) {
            target.addProperty(DmzQuestDefaults.VERSION_KEY, DmzQuestDefaults.DEFAULTS_VERSION);
        }
    }

    /** Top-level keys {@link #toSagaJson} writes itself; anything else on disk is preserved. */
    private static final java.util.Set<String> SAGA_MODELED_KEYS =
            java.util.Set.of("id", "name", "requirements", "questFolder", "sdu_prereq_saga", "sdu_prereq_quest",
                    "sdu_allow_quest_start_in_blocked_region");

    public JsonObject toSagaJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        JsonObject req = new JsonObject();
        req.addProperty("previousSaga", previousSaga == null ? "" : previousSaga);
        o.add("requirements", req);
        o.addProperty("questFolder", questFolder);
        // addon-only quest prerequisite (DMZ ignores these keys); a (saga, quest) pair, blank/0 = no gate
        o.addProperty("sdu_prereq_saga", prereqQuestSaga == null ? "" : prereqQuestSaga);
        o.addProperty("sdu_prereq_quest", prereqQuestId);
        // addon-only region-flag bypass (DMZ ignores this key); false = today's behaviour
        o.addProperty("sdu_allow_quest_start_in_blocked_region", allowStartInQuestBlockedRegion);
        writePreserved(o, preservedFields);
        return o;
    }

    /** Bundle used for client sync: saga json + all quest jsons in one object. */
    public JsonObject toBundle() {
        JsonObject o = new JsonObject();
        o.add("saga", toSagaJson());
        JsonArray qs = new JsonArray();
        for (Quest q : quests) {
            qs.add(q.toJson(questFolder));
        }
        o.add("quests", qs);
        return o;
    }

    public static SagaData fromBundle(JsonObject o) {
        SagaData s = fromSagaJson(o.getAsJsonObject("saga"));
        if (o.has("quests")) {
            for (var el : o.getAsJsonArray("quests")) {
                s.quests.add(Quest.fromJson(el.getAsJsonObject()));
            }
        }
        s.quests.sort((a, b) -> Integer.compare(a.id, b.id));
        return s;
    }

    public static SagaData fromSagaJson(JsonObject o) {
        SagaData s = new SagaData();
        s.id = GsonHelper.getAsString(o, "id", "custom_saga");
        s.name = GsonHelper.getAsString(o, "name", s.id);
        s.questFolder = GsonHelper.getAsString(o, "questFolder", "saga_" + s.id);
        if (o.has("requirements")) {
            s.previousSaga = GsonHelper.getAsString(o.getAsJsonObject("requirements"), "previousSaga", "");
        }
        s.prereqQuestSaga = GsonHelper.getAsString(o, "sdu_prereq_saga", "");
        s.prereqQuestId = GsonHelper.getAsInt(o, "sdu_prereq_quest", 0);
        s.allowStartInQuestBlockedRegion =
                GsonHelper.getAsBoolean(o, "sdu_allow_quest_start_in_blocked_region", false);
        capturePreserved(o, s.preservedFields, SAGA_MODELED_KEYS);
        return s;
    }
}
