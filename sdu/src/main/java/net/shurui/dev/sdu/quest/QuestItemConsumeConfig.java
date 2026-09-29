package net.shurui.dev.sdu.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-quest CONSUME-ON-COMPLETION flags for the item-quest consumption feature, stored in
 * {@code config/sdu/quest_item_consume.json} keyed by the DMZ quest key (same key {@link RepeatConfig} uses:
 * {@code PlayerQuestData.sagaQuestKey(sagaId, questId)} for main quests, a branch quest's sidequest id, or a
 * side quest's own string id). {@code true} means: on completion, remove the items its ITEM objectives require.
 *
 * <p>DMZ has NO native item consumption (its ITEM objective is a "possess N" mirror), so this sidecar is what
 * {@code QuestItemConsumeHandler} reads at runtime: DMZ's {@code Quest} objects don't carry our
 * {@code sdu_consume_items} field, so we mirror it here on save like {@link RepeatConfig} mirrors
 * {@code sdu_repeat_interval}. Absent key = false = nothing consumed.
 */
public final class QuestItemConsumeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Map<String, Boolean> BY_KEY = new LinkedHashMap<>();
    private static boolean loaded = false;

    private QuestItemConsumeConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("quest_item_consume.json");
    }

    /** True when completing this quest key should consume its ITEM-objective items; false if not configured. */
    public static boolean get(String questKey) {
        ensureLoaded();
        Boolean v = BY_KEY.get(k(questKey));
        return v != null && v;
    }

    /** True when nothing at all is configured, so the runtime handler can skip its work entirely. */
    public static boolean isEmpty() {
        ensureLoaded();
        return BY_KEY.isEmpty();
    }

    /** Set (or, when {@code false}, clear) a quest's consume flag in memory. Call {@link #save()} to persist. */
    public static void put(String questKey, boolean consume) {
        ensureLoaded();
        String key = k(questKey);
        if (key.isEmpty()) {
            return;
        }
        if (!consume) {
            BY_KEY.remove(key);
        } else {
            BY_KEY.put(key, Boolean.TRUE);
        }
    }

    public static synchronized void load() {
        BY_KEY.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("consume") && root.get("consume").isJsonObject()) {
                JsonObject consume = root.getAsJsonObject("consume");
                for (String key : consume.keySet()) {
                    try {
                        if (consume.get(key).getAsBoolean()) {
                            BY_KEY.put(key, Boolean.TRUE);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded quest item-consume config: {} quest(s) consume required items.",
                    DmzNpc.MODID, BY_KEY.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read quest item-consume config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject consume = new JsonObject();
            for (Map.Entry<String, Boolean> e : BY_KEY.entrySet()) {
                consume.addProperty(e.getKey(), e.getValue());
            }
            root.add("consume", consume);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save quest item-consume config: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String k(String questKey) {
        return questKey == null ? "" : questKey.trim();
    }
}
