package net.shurui.shuruisutilities.compat.dmz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import com.dragonminez.common.config.ConfigManager;

/**
 * The only SU class that touches DMZ's config internals for the planet gravity seed. Reached solely through
 * {@link PlanetGravityCompat}, which confirms DMZ is present first, so the DMZ imports here are never classloaded on a
 * server without DragonMineZ. Follows the optional-dependency pattern.
 *
 * <p>DMZ owns a per-dimension gravity system: {@code GeneralServerConfig.GravityConfig.gravityPerWorld} maps a
 * dimension id to a multiplier, and {@code GravityLogic} reads it every tick for every player, so leaving the dimension
 * recomputes back to 1.0 on its own. We do NOT run any tick, dimension-change or clear logic; we only make sure the
 * Planet Vegeta entry exists.
 *
 * <p>The catch: the CLIENT's copy of this config is read straight off the on-disk {@code general-server.json} on player
 * login (DMZ's {@code ConfigManager.getSpecificConfigJson} does {@code Files.readString}) and sent to the client, and
 * jump/flight/fall feel is computed client-side. So the entry MUST land on disk, not only in the live map, or the
 * server would apply the penalty while the client still felt 1x. We therefore seed both: the on-disk file (for the
 * client sync) and the live in-memory map (so the running server agrees without a restart).
 */
public final class PlanetGravityBridge
{
    private PlanetGravityBridge() {}

    // DMZ writes general-server.json with a pretty-printing, lenient Gson (verified against dragonminez-2.1.3.jar):
    // new GsonBuilder().setPrettyPrinting().setLenient(). We re-serialize the parsed tree with the same settings so
    // DMZ reads our edit back byte-for-byte the way it reads its own writes, and every untouched field round-trips
    // unchanged.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().setLenient().create();

    /**
     * Seed the Planet Vegeta gravity entry if it is not already present. Seed-if-absent is keyed off the ON-DISK file:
     * if {@code shuruisutilities:planet_vegeta} already exists in {@code gravityPerWorld}, an admin (or a previous boot)
     * put it there, so its value is left exactly as-is and nothing is written. Only when the key is missing do we add
     * the default and rewrite the file, then mirror the same value into the live map so gravity applies this session
     * with no restart. Fail-soft: any missing file, malformed shape or IO error logs one line and returns; it never
     * stops the server from starting.
     */
    static void seed(double gravity)
    {
        try
        {
            Path file = FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve("general-server.json");
            if (!Files.exists(file))
            {
                LoggingHandler.sulog.warn("[PlanetGravity] DMZ general-server.json not found at {}; skipping Planet Vegeta gravity seed.", file);
                return;
            }

            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root == null || !root.has("gravity") || !root.get("gravity").isJsonObject())
            {
                LoggingHandler.sulog.warn("[PlanetGravity] DMZ general-server.json has no 'gravity' object; skipping Planet Vegeta gravity seed.");
                return;
            }

            JsonObject gravity_obj = root.getAsJsonObject("gravity");
            if (!gravity_obj.has("gravityPerWorld") || !gravity_obj.get("gravityPerWorld").isJsonObject())
            {
                LoggingHandler.sulog.warn("[PlanetGravity] DMZ gravity config has no 'gravityPerWorld' map; skipping Planet Vegeta gravity seed.");
                return;
            }

            JsonObject perWorld = gravity_obj.getAsJsonObject("gravityPerWorld");
            if (perWorld.has(PlanetGravityCompat.PLANET_VEGETA_DIM))
            {
                // present already (admin-tuned or seeded on a prior boot): never overwrite. the live map DMZ loaded
                // from this same file at construction already carries that value, so there is nothing to mirror.
                LoggingHandler.sulog.info("[PlanetGravity] Planet Vegeta gravity already present in DMZ config; leaving it untouched.");
                return;
            }

            perWorld.addProperty(PlanetGravityCompat.PLANET_VEGETA_DIM, gravity);
            Files.writeString(file, GSON.toJson(root));
            LoggingHandler.sulog.info("[PlanetGravity] Seeded Planet Vegeta gravity {} into DMZ general-server.json.", gravity);

            // mirror into the running server's in-memory config so the value takes effect this session without a
            // restart. DMZ loaded general-server.json at mod construction, so this map is the same object GravityLogic
            // reads each tick. putIfAbsent keeps it a strict no-op if DMZ somehow already had the key.
            Map<String, Double> live = ConfigManager.getServerConfig().getGravity().getGravityPerWorld();
            if (live != null)
                live.putIfAbsent(PlanetGravityCompat.PLANET_VEGETA_DIM, gravity);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetGravity] Could not seed Planet Vegeta gravity into DMZ config: {}", t.toString());
        }
    }
}
