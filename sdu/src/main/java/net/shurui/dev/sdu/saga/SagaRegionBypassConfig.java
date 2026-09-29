package net.shurui.dev.sdu.saga;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which sagas may have their quests STARTED (and resummoned) inside a region whose {@code quest-start} flag is set
 * to deny. Holds the set of saga ids whose {@code allowStartInQuestBlockedRegion} toggle is on. Absent = the flag
 * bites as usual (today's behaviour).
 *
 * <p>Persisted at {@code config/sdu/saga_region_bypass.json}. DMZ's live {@code Saga} object drops our
 * {@code sdu_*} manifest keys, so this sidecar is where the runtime reads the toggle from; the authoritative copy
 * is the saga manifest itself ({@code dragonminez/sagas/<id>.json}, key
 * {@code sdu_allow_quest_start_in_blocked_region}), which {@link SagaFileManager#save} mirrors here on save. Both
 * the manifest (world save channel) and this sidecar (config channel) travel across shards via
 * {@code ShardConfigFiles}, and {@code SduSidecarReload} re-reads this file after a shard config apply. Mirrors
 * {@link SagaQuestGateConfig}.
 *
 * <p>Server-side only: {@link SagaRegionBypass} reads it for shuruisutilities' region mixin. There is no client
 * use, so unlike {@link SagaQuestGateConfig} it is not synced to clients; the editor gets the value straight from
 * the saga bundle ({@code SagaData.sdu_allow_quest_start_in_blocked_region}).
 */
public final class SagaRegionBypassConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Set<String> ALLOWED = new LinkedHashSet<>();
    private static boolean loaded = false;

    private SagaRegionBypassConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("saga_region_bypass.json");
    }

    /** True when this saga's quests may start inside a quest-blocked region. */
    public static synchronized boolean isAllowed(String sagaId) {
        ensureLoaded();
        String k = key(sagaId);
        return !k.isEmpty() && ALLOWED.contains(k);
    }

    /** Add ({@code allow == true}) or clear ({@code false}) one saga's bypass. Doesn't persist. */
    public static synchronized void put(String sagaId, boolean allow) {
        ensureLoaded();
        String k = key(sagaId);
        if (k.isEmpty()) {
            return;
        }
        if (allow) {
            ALLOWED.add(k);
        } else {
            ALLOWED.remove(k);
        }
    }

    public static synchronized void load() {
        ALLOWED.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("sagas") && root.get("sagas").isJsonArray()) {
                for (var el : root.getAsJsonArray("sagas")) {
                    if (el != null && el.isJsonPrimitive()) {
                        String k = key(el.getAsString());
                        if (!k.isEmpty()) {
                            ALLOWED.add(k);
                        }
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded saga region-bypass config: {} saga(s).", DmzNpc.MODID, ALLOWED.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read saga region-bypass config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            com.google.gson.JsonArray sagas = new com.google.gson.JsonArray();
            for (String id : ALLOWED) {
                sagas.add(id);
            }
            root.add("sagas", sagas);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save saga region-bypass config: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String sagaId) {
        return sagaId == null ? "" : sagaId.trim();
    }
}
