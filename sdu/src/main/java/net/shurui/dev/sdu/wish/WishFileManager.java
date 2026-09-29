package net.shurui.dev.sdu.wish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// read/write DMZ wishes in the world save: one JSON array per dragon at world/dragonminez/wishes/<dragon>.json.
// after a write we call DMZ's WishManager.loadWishes so it applies without a restart; clients pick it up on the
// next sync (rejoin if not).
public final class WishFileManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private WishFileManager() {
    }

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dragonminez").resolve("wishes");
    }

    public static List<WishSetData> loadAll(MinecraftServer server) {
        List<WishSetData> result = new ArrayList<>();
        Path root = dir(server);
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    String dragon = p.getFileName().toString().replaceFirst("\\.json$", "");
                    JsonArray arr = GSON.fromJson(Files.readString(p), JsonArray.class);
                    result.add(WishSetData.fromWishArray(dragon, arr));
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read wishes '{}': {}", DmzNpc.MODID, p.getFileName(), e.toString());
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to list wishes: {}", DmzNpc.MODID, e.toString());
        }
        return result;
    }

    // null on success, else an error message.
    public static String save(MinecraftServer server, WishSetData set) {
        try {
            String dragon = net.shurui.dev.sdu.util.SduIds.sanitize(set.dragon);
            Path file = dir(server).resolve(dragon + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(set.toWishArray()));
            DmzNpc.LOGGER.info("[{}] Saved {} wishes for dragon '{}'", DmzNpc.MODID, set.wishes.size(), dragon);
            reload(server);
            return null;
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save wishes for '{}': {}", DmzNpc.MODID, set.dragon, e.toString());
            return e.getMessage();
        }
    }

    public static void delete(MinecraftServer server, String dragon) {
        try {
            Files.deleteIfExists(dir(server).resolve(net.shurui.dev.sdu.util.SduIds.sanitize(dragon) + ".json"));
            reload(server);
            DmzNpc.LOGGER.info("[{}] Deleted wishes for dragon '{}'", DmzNpc.MODID, dragon);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to delete wishes for '{}': {}", DmzNpc.MODID, dragon, e.toString());
        }
    }

    // ask DMZ to re-read the wish files (guarded; no-op if the API changed).
    private static void reload(MinecraftServer server) {
        try {
            com.dragonminez.common.wish.WishManager.loadWishes(server);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not reload DMZ wishes ({}); a rejoin may be needed.", DmzNpc.MODID, t.toString());
        }
    }

    /**
     * Re-read the wish files into DMZ's live registry, the SAME guarded call the editor save path makes. Exposed for
     * the shard config sync: a wish edited on one server writes the file on every server, and this makes it live on
     * the receivers exactly as it went live on the author, so the network never diverges on wishes. Must run on the
     * server thread (it swaps DMZ's static wish registry); the sync's reload already hops there before calling this.
     */
    public static void reloadFromDisk(MinecraftServer server) {
        reload(server);
    }
}
