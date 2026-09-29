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
 * Per-quest REPEAT intervals (seconds) for the repeatable-quest feature, stored in
 * {@code config/sdu/quest_repeats.json} keyed by the DMZ quest key ({@code PlayerQuestData.sagaQuestKey}
 * for main quests, a branch quest's sidequest id, or a side quest's own string id). {@code seconds > 0}
 * means: re-unlock the quest that many seconds after a player completes it.
 *
 * <p>DMZ has NO native repeatable-with-cooldown mechanism (only a per-quest SUCCESS status, no timestamp), so
 * this sidecar is what {@code QuestRepeatHandler} reads at runtime: DMZ's {@code Quest} objects don't carry our
 * {@code sdu_repeat_interval} field, so we mirror it here on save like {@link QuestTimerConfig} mirrors
 * {@code sdu_time_limit}.
 */
public final class RepeatConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Map<String, Long> BY_KEY = new LinkedHashMap<>();
    private static boolean loaded = false;

    private RepeatConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("quest_repeats.json");
    }

    /** The repeat interval (seconds) for a quest key, or 0 if the quest is not repeatable. */
    public static long get(String questKey) {
        ensureLoaded();
        Long v = BY_KEY.get(k(questKey));
        return v == null ? 0L : v;
    }

    /** All configured repeat keys (a snapshot copy). Lets the runtime handler iterate completed quests. */
    public static java.util.Set<String> keys() {
        ensureLoaded();
        return new java.util.HashSet<>(BY_KEY.keySet());
    }

    /** Set (or, when {@code <= 0}, clear) a quest's repeat interval in memory. Call {@link #save()} to persist. */
    public static void put(String questKey, long seconds) {
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
            if (root != null && root.has("repeats") && root.get("repeats").isJsonObject()) {
                JsonObject repeats = root.getAsJsonObject("repeats");
                for (String key : repeats.keySet()) {
                    try {
                        BY_KEY.put(key, repeats.get(key).getAsLong());
                    } catch (Exception ignored) {
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded quest-repeat config: {} repeatable quest(s).", DmzNpc.MODID, BY_KEY.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read quest-repeat config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject repeats = new JsonObject();
            for (Map.Entry<String, Long> e : BY_KEY.entrySet()) {
                repeats.addProperty(e.getKey(), e.getValue());
            }
            root.add("repeats", repeats);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save quest-repeat config: {}", DmzNpc.MODID, e.toString());
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
