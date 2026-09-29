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
 * Per-quest time limits (seconds) for the timed-quest feature, stored in {@code config/sdu/quest_timers.json}
 * keyed by the DMZ quest key ({@code PlayerQuestData.sagaQuestKey(sagaId, questId)} for main quests, or a
 * branch quest's sidequest id). DMZ has no native quest timer, so this sidecar is what {@code QuestTimerHandler}
 * reads at runtime (DMZ's {@code Quest} objects don't carry our {@code sdu_time_limit} field).
 */
public final class QuestTimerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Map<String, Integer> BY_KEY = new LinkedHashMap<>();
    private static boolean loaded = false;

    private QuestTimerConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("quest_timers.json");
    }

    /** The time limit (seconds) for a quest key, or 0 if untimed. */
    public static int get(String questKey) {
        ensureLoaded();
        Integer v = BY_KEY.get(k(questKey));
        return v == null ? 0 : v;
    }

    /** Set (or, when {@code <= 0}, clear) a quest's time limit in memory. Call {@link #save()} to persist. */
    public static void put(String questKey, int seconds) {
        ensureLoaded();
        String key = k(questKey);
        if (key.isEmpty()) {
            return;
        }
        if (seconds <= 0) {
            BY_KEY.remove(key);
        } else {
            BY_KEY.put(key, seconds);
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
            if (root != null && root.has("timers") && root.get("timers").isJsonObject()) {
                JsonObject timers = root.getAsJsonObject("timers");
                for (String key : timers.keySet()) {
                    try {
                        BY_KEY.put(key, timers.get(key).getAsInt());
                    } catch (Exception ignored) {
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded quest-timer config: {} timed quest(s).", DmzNpc.MODID, BY_KEY.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read quest-timer config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject timers = new JsonObject();
            for (Map.Entry<String, Integer> e : BY_KEY.entrySet()) {
                timers.addProperty(e.getKey(), e.getValue());
            }
            root.add("timers", timers);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save quest-timer config: {}", DmzNpc.MODID, e.toString());
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
