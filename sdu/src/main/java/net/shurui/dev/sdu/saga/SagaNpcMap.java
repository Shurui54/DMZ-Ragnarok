package net.shurui.dev.sdu.saga;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config for feature 4.7 (sagas spawn Custom NPC clones). Reads {@code config/sdu/saga_npc_map.json},
 * which maps a DMZ saga entity id to a Custom NPCs clone (tab + name) and carries a global on/off toggle.
 *
 * <pre>{@code
 * {
 *   "useCustomNpcForSagas": true,
 *   "mappings": {
 *     "dragonminez:saga_raditz": { "tab": 0, "name": "Raditz" },
 *     "dragonminez:saga_nappa":  { "tab": 0, "name": "Nappa"  }
 *   }
 * }
 * }</pre>
 *
 * <p>When a saga kill-objective would spawn a mapped DMZ entity, {@code SagaCloneSubstitution} swaps in the
 * clone via the CNPC bridge; DMZ still stamps the quest NBT onto it so kill-credit is unchanged. Unmapped
 * ids (and everything when the toggle is off, or the CNPC stack is absent) fall through to DMZ's native mob.
 */
public final class SagaNpcMap {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** DMZ entity id (e.g. {@code dragonminez:saga_raditz}) -> clone reference. */
    private static final Map<ResourceLocation, CloneRef> MAPPINGS = new LinkedHashMap<>();
    private static volatile boolean useCustomNpcForSagas = false;

    private SagaNpcMap() {
    }

    /** A Custom NPCs clone address: its storage tab index and clone name. */
    public record CloneRef(int tab, String name) {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(DmzNpc.MODID).resolve("saga_npc_map.json");
    }

    public static boolean useCustomNpcForSagas() {
        return useCustomNpcForSagas;
    }

    /** Clone mapped to this entity type, or {@code null} if none. Never throws. */
    public static CloneRef lookup(EntityType<?> type) {
        if (type == null || MAPPINGS.isEmpty()) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return id == null ? null : MAPPINGS.get(id);
    }

    /** (Re)load from disk, writing a commented example if the file is missing. Called at server launch. */
    public static synchronized void load() {
        MAPPINGS.clear();
        useCustomNpcForSagas = false;
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                writeExample(path);
                DmzNpc.LOGGER.info("[{}] Wrote example saga->NPC map at {}", DmzNpc.MODID, path);
                return;
            }
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root == null) {
                return;
            }
            useCustomNpcForSagas = root.has("useCustomNpcForSagas") && root.get("useCustomNpcForSagas").getAsBoolean();
            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                JsonObject m = root.getAsJsonObject("mappings");
                for (String key : m.keySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(key);
                    if (id == null || !m.get(key).isJsonObject()) {
                        DmzNpc.LOGGER.warn("[{}] saga_npc_map: skipping bad entry '{}'", DmzNpc.MODID, key);
                        continue;
                    }
                    JsonObject e = m.getAsJsonObject(key);
                    String name = e.has("name") ? e.get("name").getAsString() : null;
                    if (name == null || name.isBlank()) {
                        DmzNpc.LOGGER.warn("[{}] saga_npc_map: entry '{}' has no clone name; skipping", DmzNpc.MODID, key);
                        continue;
                    }
                    int tab = e.has("tab") ? e.get("tab").getAsInt() : 0;
                    MAPPINGS.put(id, new CloneRef(tab, name));
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded saga->NPC map: {} mapping(s), useCustomNpcForSagas={}",
                    DmzNpc.MODID, MAPPINGS.size(), useCustomNpcForSagas);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to load saga_npc_map.json: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void writeExample(Path path) throws java.io.IOException {
        JsonObject root = new JsonObject();
        root.addProperty("useCustomNpcForSagas", false);
        JsonObject mappings = new JsonObject();
        JsonObject example = new JsonObject();
        example.addProperty("tab", 0);
        example.addProperty("name", "Raditz");
        mappings.add("dragonminez:saga_raditz", example);
        root.add("mappings", mappings);
        Files.writeString(path, GSON.toJson(root));
    }
}
