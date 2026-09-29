package net.shurui.dev.sdu.saga;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.transform.TransformChain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a DragonMine Z quest KILL objective to a custom NPC definition. DMZ tags each quest-spawned
 * entity (in {@code QuestService#spawnKillObjectives}) via {@code getPersistentData()} with
 * {@code dmz_quest_key} (a <b>string</b>: {@code "<sagaId>:<questId>"} for saga quests, the string id
 * for side quests) and {@code dmz_quest_objective_index}. We index the {@code sdu_definition} the
 * editor stored on each KILL objective under the same key, so a quest spawn gets the fully-configured
 * custom NPC. Only genuine quest spawns match, so hand-placed NPCs are never affected.
 */
public final class SagaSpawnBindings {

    /** questKey|objIndex -&gt; raw definition string (a {@code cnpc$tab$name} saved-NPC ref, or an NPC-def resloc). */
    private static final Map<String, String> BINDINGS = new HashMap<>();

    /**
     * questKey|objIndex -&gt; the KILL objective's transform config (custom chain + useDefaultTransform flag).
     * Populated alongside {@link #BINDINGS} whenever the bindings are (re)built, so the spawned fighter can stamp
     * its transform chain / DMZ-native gate on its first server tick.
     */
    private static final Map<String, TransformBinding> TRANSFORMS = new HashMap<>();

    /**
     * questKey|objIndex -&gt; the KILL objective's ragnarok NPC character. Indexed the same way as the two maps
     * above, and read on the spawn itself rather than by the entity, because the whole ragnarok cast shares one
     * entity type: the spawned entity cannot tell us which character its objective asked for.
     */
    private static final Map<String, String> RG_LOOKS = new HashMap<>();

    /** Per-objective transform config carried into a quest spawn. {@code chain} may be empty. */
    public record TransformBinding(TransformChain chain, boolean useDefaultTransform) {
    }

    private SagaSpawnBindings() {
    }

    /** Binding key mirrors DMZ's {@code dmz_quest_key} + objective index. */
    private static String key(String questKey, int objectiveIndex) {
        return questKey + "|" + objectiveIndex;
    }

    public static synchronized void loadFrom(MinecraftServer server) {
        BINDINGS.clear();
        TRANSFORMS.clear();
        RG_LOOKS.clear();
        for (SagaData saga : SagaFileManager.loadAll(server)) {
            for (SagaData.Quest q : saga.quests) {
                index(saga.id + ":" + q.id, q.objectives);
            }
        }
        for (SideQuestData sq : SideQuestFileManager.loadAll(server)) {
            index(sq.id, sq.objectives);
        }
        DmzNpc.LOGGER.info("[{}] Loaded {} quest-spawn definition bindings", DmzNpc.MODID, BINDINGS.size());
    }

    private static void index(String questKey, List<SagaData.Objective> objectives) {
        for (int i = 0; i < objectives.size(); i++) {
            SagaData.Objective o = objectives.get(i);
            if (!"KILL".equals(o.type)) {
                continue;
            }
            if (o.definition != null && !o.definition.isBlank()) {
                BINDINGS.put(key(questKey, i), o.definition.trim());
            }
            if (o.rgModel != null && !o.rgModel.isBlank()) {
                RG_LOOKS.put(key(questKey, i), o.rgModel.trim());
            }
            // Carry the transform config whenever it deviates from stock (custom chain OR native disabled), so
            // the spawned fighter can stamp it. A stock objective (empty chain + default on) needs no entry.
            boolean hasChain = o.transformChain != null && !o.transformChain.forms.isEmpty();
            if (hasChain || !o.useDefaultTransform) {
                TransformChain chain = o.transformChain == null ? new TransformChain() : o.transformChain;
                TRANSFORMS.put(key(questKey, i), new TransformBinding(chain, o.useDefaultTransform));
            }
        }
    }

    /** The raw definition string bound to a quest-spawned entity (from its DMZ persistent data), or null. */
    public static String matchRaw(CompoundTag persistentData) {
        if (BINDINGS.isEmpty() || persistentData == null || !persistentData.contains("dmz_quest_key")) {
            return null;
        }
        return BINDINGS.get(key(persistentData.getString("dmz_quest_key"),
                persistentData.getInt("dmz_quest_objective_index")));
    }

    /** The transform config bound to a quest-spawned entity (from its DMZ persistent data), or null if stock. */
    public static TransformBinding matchTransform(CompoundTag persistentData) {
        if (TRANSFORMS.isEmpty() || persistentData == null || !persistentData.contains("dmz_quest_key")) {
            return null;
        }
        return TRANSFORMS.get(key(persistentData.getString("dmz_quest_key"),
                persistentData.getInt("dmz_quest_objective_index")));
    }

    /** The ragnarok NPC character bound to a quest-spawned entity, or null when the objective named none. */
    public static String matchLook(CompoundTag persistentData) {
        if (RG_LOOKS.isEmpty() || persistentData == null || !persistentData.contains("dmz_quest_key")) {
            return null;
        }
        return RG_LOOKS.get(key(persistentData.getString("dmz_quest_key"),
                persistentData.getInt("dmz_quest_objective_index")));
    }

    /** The binding as a {@link ResourceLocation} (legacy NPC-definition path); null for saved-NPC refs. */
    public static ResourceLocation match(CompoundTag persistentData) {
        String raw = matchRaw(persistentData);
        return raw == null ? null : ResourceLocation.tryParse(raw);
    }
}
