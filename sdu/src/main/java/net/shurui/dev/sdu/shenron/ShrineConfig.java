package net.shurui.dev.sdu.shenron;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// custom Shenron-shrine config in config/sdu/shenron_shrines.json. independent of DMZ's wish system: its own
// wishes, per-colour summon requirements and display-entity settings. loaded on server start (seeds defaults
// if absent); the in-memory state is the source of truth for the shrine block, packets and the summoned entity.
public final class ShrineConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final List<ShrineWish> WISHES = new ArrayList<>();
    private static final Map<ShrineColor, ShrineColorConfig> COLORS = new EnumMap<>(ShrineColor.class);
    private static boolean loaded = false;

    private ShrineConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("shenron_shrines.json");
    }

    public static List<ShrineWish> wishes() {
        ensureLoaded();
        return new ArrayList<>(WISHES);
    }

    public static ShrineWish wishById(String id) {
        ensureLoaded();
        if (id == null) {
            return null;
        }
        for (ShrineWish w : WISHES) {
            if (id.equals(w.id)) {
                return w;
            }
        }
        return null;
    }

    // seeded default if somehow absent.
    public static ShrineColorConfig color(ShrineColor color) {
        ensureLoaded();
        ShrineColorConfig c = COLORS.get(color);
        return c != null ? c : ShrineColorConfig.seedDefault();
    }

    // a colour's wishIds in order (skipping unknown), or every configured wish when wishIds is empty.
    public static List<ShrineWish> wishesFor(ShrineColor color) {
        ensureLoaded();
        ShrineColorConfig c = color(color);
        if (c.wishIds == null || c.wishIds.isEmpty()) {
            return new ArrayList<>(WISHES);
        }
        List<ShrineWish> out = new ArrayList<>();
        for (String id : c.wishIds) {
            ShrineWish w = wishById(id);
            if (w != null) {
                out.add(w);
            }
        }
        return out;
    }

    public static boolean colorAllowsWish(ShrineColor color, String wishId) {
        for (ShrineWish w : wishesFor(color)) {
            if (w.id.equals(wishId)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void load() {
        WISHES.clear();
        COLORS.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            seedDefaults();
            save();
            DmzNpc.LOGGER.info("[{}] Seeded default Shenron-shrine config.", DmzNpc.MODID);
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null) {
                if (root.has("wishes") && root.get("wishes").isJsonArray()) {
                    for (var el : root.getAsJsonArray("wishes")) {
                        if (el.isJsonObject()) {
                            WISHES.add(ShrineWish.fromJson(el.getAsJsonObject()));
                        }
                    }
                }
                if (root.has("colors") && root.get("colors").isJsonObject()) {
                    JsonObject colors = root.getAsJsonObject("colors");
                    for (ShrineColor c : ShrineColor.values()) {
                        if (colors.has(c.key()) && colors.get(c.key()).isJsonObject()) {
                            COLORS.put(c, ShrineColorConfig.fromJson(colors.getAsJsonObject(c.key())));
                        }
                    }
                }
            }
            // every colour gets an entry even if the file omitted some.
            for (ShrineColor c : ShrineColor.values()) {
                COLORS.computeIfAbsent(c, k -> ShrineColorConfig.seedDefault());
            }
            DmzNpc.LOGGER.info("[{}] Loaded Shenron-shrine config: {} wish(es).", DmzNpc.MODID, WISHES.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read Shenron-shrine config: {}", DmzNpc.MODID, e.toString());
            seedDefaults();
        }
    }

    // replace the in-memory model from the admin GUI and persist. null half = no change; every colour is
    // guaranteed an entry afterwards.
    public static synchronized void saveFrom(List<ShrineWish> newWishes, Map<ShrineColor, ShrineColorConfig> newColors) {
        ensureLoaded();
        if (newWishes != null) {
            WISHES.clear();
            WISHES.addAll(newWishes);
        }
        if (newColors != null) {
            COLORS.clear();
            COLORS.putAll(newColors);
        }
        for (ShrineColor c : ShrineColor.values()) {
            COLORS.computeIfAbsent(c, k -> ShrineColorConfig.seedDefault());
        }
        save();
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonArray wishes = new JsonArray();
            for (ShrineWish w : WISHES) {
                wishes.add(w.toJson());
            }
            root.add("wishes", wishes);
            JsonObject colors = new JsonObject();
            for (ShrineColor c : ShrineColor.values()) {
                colors.add(c.key(), COLORS.getOrDefault(c, ShrineColorConfig.seedDefault()).toJson());
            }
            root.add("colors", colors);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save Shenron-shrine config: {}", DmzNpc.MODID, e.toString());
        }
    }

    // whole live model (wishes + all colours) as one JSON string for the admin GUI open packet; shape matches save().
    public static synchronized String toBundleJson() {
        ensureLoaded();
        JsonObject root = new JsonObject();
        JsonArray wishes = new JsonArray();
        for (ShrineWish w : WISHES) {
            wishes.add(w.toJson());
        }
        root.add("wishes", wishes);
        JsonObject colors = new JsonObject();
        for (ShrineColor c : ShrineColor.values()) {
            colors.add(c.key(), color(c).toJson());
        }
        root.add("colors", colors);
        return GSON.toJson(root);
    }

    private static void seedDefaults() {
        WISHES.clear();
        COLORS.clear();
        WISHES.add(new ShrineWish("riches", "Riches", "A pile of zeni",
                List.of("say %player% wished for riches")));
        for (ShrineColor c : ShrineColor.values()) {
            COLORS.put(c, ShrineColorConfig.seedDefault());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }
}
