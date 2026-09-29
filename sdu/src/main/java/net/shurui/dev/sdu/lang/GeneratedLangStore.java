package net.shurui.dev.sdu.lang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

// both-sides storage for generated race/form/skill display names+descriptions, a flat key->value map in
// config/sdu/generated_lang.json. source of truth on disk: the editor client sends entries here, the server
// persists + syncs them, clients overlay onto the active language. overlay logic is in client/GeneratedLang;
// this is file I/O only so it can run on the server (which has no Language).
public final class GeneratedLangStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, String> ENTRIES = new LinkedHashMap<>();
    private static boolean loaded;

    private GeneratedLangStore() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("generated_lang.json");
    }

    // pre-rename location (mod id was dmznpc before the sdu refactor). read once to migrate.
    private static Path legacyFile() {
        return FMLPaths.CONFIGDIR.get().resolve("dmznpc").resolve("generated_lang.json");
    }

    public static synchronized Map<String, String> all() {
        ensureLoaded();
        return new LinkedHashMap<>(ENTRIES);
    }

    // merge + persist. server side when a client saves an edit (authoritative).
    public static synchronized void putAll(Map<String, String> entries) {
        ensureLoaded();
        if (entries != null && !entries.isEmpty()) {
            ENTRIES.putAll(entries);
            save();
        }
    }

    // Replace the whole store with an authoritative full snapshot: keys absent from the snapshot are DROPPED, not
    // just the present ones merged in. The editor client always sends its complete overlay (seeded from this store
    // on join, then locally edited), so a description or name it cleared is absent from the snapshot and must
    // vanish here too. With the old merge-only putAll a cleared key survived and synced straight back, so a
    // form-type description could never be emptied (bug 780). Guarded against an empty snapshot so a client that
    // somehow saves before it has been seeded can never wipe the store; a real full snapshot always carries many
    // keys.
    public static synchronized void replaceAll(Map<String, String> entries) {
        ensureLoaded();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (ENTRIES.equals(entries)) {
            return; // nothing changed; do not rewrite the file or churn a resync
        }
        ENTRIES.clear();
        ENTRIES.putAll(entries);
        save();
    }

    // remove by exact key, persist if anything changed (e.g. a form-type id rename).
    public static synchronized void remove(String... keys) {
        ensureLoaded();
        boolean changed = false;
        if (keys != null) {
            for (String k : keys) {
                if (k != null && ENTRIES.remove(k) != null) {
                    changed = true;
                }
            }
        }
        if (changed) {
            save();
        }
    }

    // add only keys not already present, persist if changed. server-start regen POPULATEs from configs (fixing
    // a missing file) without clobbering names already set by a real edit.
    public static synchronized void putAllIfAbsent(Map<String, String> entries) {
        ensureLoaded();
        boolean changed = false;
        if (entries != null) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && ENTRIES.putIfAbsent(e.getKey(), e.getValue()) == null) {
                    changed = true;
                }
            }
        }
        if (changed) {
            save();
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
            loaded = true;
        }
    }

    private static void load() {
        cleanupLegacyLangFolder();
        Path f = file();
        boolean migrated = false;
        if (!Files.exists(f) && Files.exists(legacyFile())) {
            f = legacyFile();
            migrated = true;
        }
        try {
            if (Files.exists(f)) {
                JsonObject o = GSON.fromJson(Files.readString(f), JsonObject.class);
                if (o != null) {
                    for (String k : o.keySet()) {
                        ENTRIES.put(k, o.get(k).getAsString());
                    }
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.warn("[{}] Could not read generated lang: {}", DmzNpc.MODID, e.toString());
        }
        if (migrated && !ENTRIES.isEmpty()) {
            save(); // copy the legacy entries into config/sdu/generated_lang.json
        }
    }

    // delete the obsolete <gamedir>/shuruis_dmz_utils_lang folder-pack (superseded by this store).
    private static void cleanupLegacyLangFolder() {
        try {
            Path old = FMLPaths.GAMEDIR.get().resolve("shuruis_dmz_utils_lang");
            if (Files.isDirectory(old)) {
                try (var walk = Files.walk(old)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
                DmzNpc.LOGGER.info("[{}] Removed obsolete shuruis_dmz_utils_lang folder; generated names now live only in config/sdu.", DmzNpc.MODID);
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            ENTRIES.forEach(o::addProperty);
            Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DmzNpc.LOGGER.warn("[{}] Could not write generated lang: {}", DmzNpc.MODID, e.toString());
        }
    }
}
