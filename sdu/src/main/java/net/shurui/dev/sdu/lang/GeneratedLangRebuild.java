package net.shurui.dev.sdu.lang;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;

import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.form.FormFileManager;
import net.shurui.dev.sdu.race.RaceData;
import net.shurui.dev.sdu.race.RaceFileManager;

/**
 * Rebuilds the generated-lang overlay from the race and form configs on disk.
 *
 * <p>{@code generated_lang.json} is DERIVED, so it is excluded from the shard config sync (syncing a derived
 * file alongside its sources is how two servers end up disagreeing). That exclusion is only honest if the
 * derivation runs on the receiving end, which is this.
 *
 * <p>Called at server start, and again whenever the shard sync writes a config file. Without the second call a
 * race edited on one server showed on the others with its stats but its raw lang keys.
 *
 * <p>Derived names (a prettified id) go in with put-if-absent so they never overwrite an admin's typed name.
 * Names and descriptions the admin typed travel inside the race file and are authoritative here, so they
 * overwrite. Same order the editor's save path uses, which keeps a receiving server identical.
 */
public final class GeneratedLangRebuild {

    private GeneratedLangRebuild() {
    }

    /** Rebuild from disk and push to every connected client. {@code server} may be null to skip the push. */
    public static void fromConfigs(MinecraftServer server) {
        try {
            Map<String, String> derived = new LinkedHashMap<>();
            Map<String, String> explicit = new LinkedHashMap<>();
            for (var group : FormFileManager.loadAll()) {
                derived.putAll(GeneratedNames.formKeys(group));
            }
            for (RaceData race : RaceFileManager.loadAll()) {
                derived.putAll(GeneratedNames.raceKeys(race.raceId, race.racialSkill, race.displayName,
                        race.description, race.racialName, race.racialDesc));
                if (race.classes != null) {
                    derived.putAll(GeneratedNames.classKeys(race.classes.keySet()));
                    explicit.putAll(GeneratedNames.classDisplayKeys(race.classes));
                }
            }
            GeneratedLangStore.putAllIfAbsent(derived);
            GeneratedLangStore.putAll(explicit);
            if (server != null) {
                net.shurui.dev.sdu.network.DmzNet.syncLangToAll(server);
            }
            DmzNpc.LOGGER.info("[{}] Rebuilt generated-lang from configs: {} derived, {} explicit.",
                    DmzNpc.MODID, derived.size(), explicit.size());
        } catch (Throwable t) {
            // A stale overlay costs display names, never the server: this must not stop a config sync applying.
            DmzNpc.LOGGER.warn("[{}] Could not rebuild generated lang from configs: {}", DmzNpc.MODID, t.toString());
        }
    }
}
