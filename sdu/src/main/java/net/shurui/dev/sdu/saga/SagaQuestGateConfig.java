package net.shurui.dev.sdu.saga;

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
 * Quest-gated SAGA unlocks. Maps a saga id to a NAMED quest ({@code (gateSagaId, gateQuestId)} pair) that
 * must be COMPLETED before that saga unlocks, in addition to DMZ's native "previous saga complete" gate. The
 * two gates are ANDed.
 *
 * <p>Persisted at {@code config/sdu/saga_quest_gates.json}, keyed by the gated saga's id. Each entry is
 * {@code { "gateSaga": "<sagaId>", "gateQuest": <numericQuestId> }}. DMZ's {@code Saga.SagaRequirements}
 * record can only carry {@code previousSagaId}, so this sidecar is where the addon keeps the quest gate; the
 * authoritative copy is the saga manifest itself ({@code dragonminez/sagas/<id>.json}, {@code sdu_prereq_*}
 * keys), which this mirrors on save. Server reads it and syncs it to clients; the quest-tree mixin evaluates
 * it exactly where DMZ evaluates the previous-saga lock. Mirrors {@link net.shurui.dev.sdu.form.FormQuestGateConfig}.
 *
 * <p>The reference is a {@code (saga, quest)} pair because DMZ quest ids are only unique WITHIN a saga, and it
 * resolves to DMZ's own quest key {@code sagaQuestKey(gateSaga, gateQuest)}. That key is a stable authored id,
 * not a list index or a synthetic branch handle, so it survives quests being added, removed or reordered.
 * Absent entry = no quest gate = today's behaviour.
 */
public final class SagaQuestGateConfig {

    /** one saga's quest gate: the (saga, quest) pair that must be complete to unlock it. */
    public static final class Gate {
        public final String gateSaga;
        public final int gateQuest;

        public Gate(String gateSaga, int gateQuest) {
            this.gateSaga = gateSaga == null ? "" : gateSaga.trim();
            this.gateQuest = gateQuest;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("gateSaga", gateSaga);
            o.addProperty("gateQuest", gateQuest);
            return o;
        }

        static Gate fromJson(JsonObject o) {
            String gs = o.has("gateSaga") && !o.get("gateSaga").isJsonNull() ? o.get("gateSaga").getAsString() : "";
            int gq = o.has("gateQuest") && !o.get("gateQuest").isJsonNull() ? o.get("gateQuest").getAsInt() : 0;
            return new Gate(gs, gq);
        }

        /** A gate is only meaningful with both halves set; a missing half means "no gate". */
        public boolean isValid() {
            return gateSaga != null && !gateSaga.isBlank() && gateQuest > 0;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Map<String, Gate> BY_SAGA = new LinkedHashMap<>();
    private static boolean loaded = false;

    private SagaQuestGateConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("saga_quest_gates.json");
    }

    /** The quest gate for a saga id, or null when it has none. */
    public static Gate get(String sagaId) {
        ensureLoaded();
        return BY_SAGA.get(key(sagaId));
    }

    /** Add, replace, or (when {@code gate} is null/invalid) clear one saga's quest gate. Doesn't persist. */
    public static synchronized void put(String sagaId, Gate gate) {
        ensureLoaded();
        String k = key(sagaId);
        if (k.isEmpty()) {
            return;
        }
        if (gate == null || !gate.isValid()) {
            BY_SAGA.remove(k);
        } else {
            BY_SAGA.put(k, gate);
        }
    }

    /** Snapshot of every gate, for server -> client sync. */
    public static synchronized Map<String, Gate> all() {
        ensureLoaded();
        return new LinkedHashMap<>(BY_SAGA);
    }

    public static synchronized void load() {
        BY_SAGA.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("gates") && root.get("gates").isJsonObject()) {
                JsonObject gates = root.getAsJsonObject("gates");
                for (String sagaId : gates.keySet()) {
                    if (!gates.get(sagaId).isJsonObject()) {
                        continue;
                    }
                    Gate gate = Gate.fromJson(gates.getAsJsonObject(sagaId));
                    if (gate.isValid()) {
                        BY_SAGA.put(key(sagaId), gate);
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded saga-quest-gate config: {} gated saga(s).", DmzNpc.MODID, BY_SAGA.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read saga-quest-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject gates = new JsonObject();
            for (Map.Entry<String, Gate> e : BY_SAGA.entrySet()) {
                gates.add(e.getKey(), e.getValue().toJson());
            }
            root.add("gates", gates);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save saga-quest-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** Replace gates from a server sync (client-side; its own config has none). Enables the quest-tree lock. */
    public static synchronized void applySynced(Map<String, Gate> data) {
        BY_SAGA.clear();
        loaded = true;
        if (data != null) {
            for (Map.Entry<String, Gate> e : data.entrySet()) {
                if (e.getValue() != null && e.getValue().isValid()) {
                    BY_SAGA.put(key(e.getKey()), e.getValue());
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced saga-quest-gate config: {} gated saga(s).", DmzNpc.MODID, BY_SAGA.size());
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
