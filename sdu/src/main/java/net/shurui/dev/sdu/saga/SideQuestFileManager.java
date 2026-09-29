package net.shurui.dev.sdu.saga;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads/writes DMZ side quests directly in the world save, matching the layout {@code QuestRegistry} loads:
 * one quest JSON per file under {@code dragonminez/sidequests/} (walked recursively, since DMZ groups its
 * defaults into per-category subfolders). Edits apply after a world reload (DMZ scans on load, no runtime
 * disk reload).
 */
public final class SideQuestFileManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private SideQuestFileManager() {
    }

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dragonminez").resolve("sidequests");
    }

    public static List<SideQuestData> loadAll(MinecraftServer server) {
        List<SideQuestData> result = new ArrayList<>();
        Path root = dir(server);
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path p : files) {
                try {
                    JsonObject json = GSON.fromJson(Files.readString(p), JsonObject.class);
                    // Skip saga branch quests (written by the saga editor as SIDEQUESTs, tagged sdu_branch):
                    // they're managed in the saga editor, not here, so they don't belong in this list.
                    if (json != null && json.has("sdu_branch") && json.get("sdu_branch").getAsBoolean()) {
                        continue;
                    }
                    SideQuestData sq = SideQuestData.fromJson(json);
                    // Store the path relative to the sidequests root (forward slashes) so a later Save
                    // overwrites this exact file rather than orphaning it.
                    sq.fileName = root.relativize(p).toString().replace('\\', '/');
                    result.add(sq);
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read side quest '{}': {}", DmzNpc.MODID, p.getFileName(), e.toString());
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to list side quests: {}", DmzNpc.MODID, e.toString());
        }
        return result;
    }

    /** Write a side quest to the world save. Overwrites its original file when editing an existing one
     *  (preserving DMZ default paths so DMZ doesn't regenerate a duplicate). Returns null on success. */
    public static String save(MinecraftServer server, SideQuestData sq) {
        try {
            Path root = dir(server);
            Files.createDirectories(root);
            // fileName is a player-supplied relative path from the editor bundle (blank for a new quest,
            // where we derive a safe name from the id). Validate + contain it before writing.
            Path target;
            if (sq.fileName != null && !sq.fileName.isBlank()) {
                SafeFileNames.requireSafeRelJson(sq.fileName, "side quest file");
                target = SafeFileNames.resolveChecked(root, sq.fileName);
            } else {
                target = SafeFileNames.resolveChecked(root,
                        net.shurui.dev.sdu.util.SduIds.sanitize(sq.id) + ".json");
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(sq.toJson()));
            sq.fileName = root.relativize(target).toString().replace('\\', '/');
            // Mirror the addon-only repeat interval into the sidecar config the runtime reads. A DMZ side
            // quest's quest key is its own string id (DMZ's Quest objects don't carry our sdu_* field).
            net.shurui.dev.sdu.quest.RepeatConfig.put(sq.id, sq.repeatIntervalSeconds);
            net.shurui.dev.sdu.quest.RepeatConfig.save();
            net.shurui.dev.sdu.quest.QuestItemConsumeConfig.put(sq.id, sq.consumeItems);
            net.shurui.dev.sdu.quest.QuestItemConsumeConfig.save();
            DmzNpc.LOGGER.info("[{}] Saved side quest '{}' ({})", DmzNpc.MODID, sq.id, sq.fileName);
            return null;
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save side quest '{}': {}", DmzNpc.MODID, sq.id, e.toString());
            return e.getMessage();
        }
    }

    public static void delete(MinecraftServer server, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        try {
            // fileName comes straight from DeleteSideQuestPacket - validate + contain before deleting.
            SafeFileNames.requireSafeRelJson(fileName, "side quest file");
            Files.deleteIfExists(SafeFileNames.resolveChecked(dir(server), fileName));
            DmzNpc.LOGGER.info("[{}] Deleted side quest file '{}'", DmzNpc.MODID, fileName);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to delete side quest '{}': {}", DmzNpc.MODID, fileName, e.toString());
        }
    }
}
