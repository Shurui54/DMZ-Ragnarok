package net.shurui.dev.sdu.htc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;

// admin override for where LEAVING DMZ's Hyperbolic Time Chamber sends players. one global server-side record
// in config/sdu/htc_destination.json (write deferred until an admin sets one). when enabled, HtcExitHandler
// teleports the player here instead of DMZ's default exit (Kami's Lookout / world spawn); when false DMZ's own
// use() runs unchanged. entry (right-clicking a portal outside the HTC) is never touched.
public final class HtcDestination {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    // Live in-memory state: the single source of truth read by the exit redirect handler.
    private static boolean enabled = false;
    private static String dimension = "minecraft:overworld";
    private static double x = 0.0D;
    private static double y = 130.0D;
    private static double z = 0.0D;
    private static float yaw = 0.0F;
    private static float pitch = 0.0F;
    private static boolean loaded = false;

    private HtcDestination() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("htc_destination.json");
    }

    // an admin has configured an exit override.
    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static String dimension() {
        ensureLoaded();
        return dimension;
    }

    public static double x() {
        ensureLoaded();
        return x;
    }

    public static double y() {
        ensureLoaded();
        return y;
    }

    public static double z() {
        ensureLoaded();
        return z;
    }

    public static float yaw() {
        ensureLoaded();
        return yaw;
    }

    public static float pitch() {
        ensureLoaded();
        return pitch;
    }

    // store a new exit destination (enabled=true) and persist.
    public static synchronized void set(String dim, double px, double py, double pz, float pyaw, float ppitch) {
        ensureLoaded();
        enabled = true;
        dimension = dim;
        x = px;
        y = py;
        z = pz;
        yaw = pyaw;
        pitch = ppitch;
        save();
    }

    // disable the override (back to DMZ's default HTC exit) and persist.
    public static synchronized void clear() {
        ensureLoaded();
        enabled = false;
        save();
    }

    public static synchronized void load() {
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            // no file = no override; keep disabled defaults, don't write.
            enabled = false;
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null) {
                enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
                if (root.has("dimension")) {
                    dimension = root.get("dimension").getAsString();
                }
                if (root.has("x")) {
                    x = root.get("x").getAsDouble();
                }
                if (root.has("y")) {
                    y = root.get("y").getAsDouble();
                }
                if (root.has("z")) {
                    z = root.get("z").getAsDouble();
                }
                if (root.has("yaw")) {
                    yaw = root.get("yaw").getAsFloat();
                }
                if (root.has("pitch")) {
                    pitch = root.get("pitch").getAsFloat();
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded HTC exit destination: enabled={} dim={} {} {} {}.",
                    DmzNpc.MODID, enabled, dimension, x, y, z);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read HTC exit destination: {}", DmzNpc.MODID, e.toString());
            enabled = false;
        }
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            root.addProperty("enabled", enabled);
            root.addProperty("dimension", dimension);
            root.addProperty("x", x);
            root.addProperty("y", y);
            root.addProperty("z", z);
            root.addProperty("yaw", yaw);
            root.addProperty("pitch", pitch);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save HTC exit destination: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }
}
