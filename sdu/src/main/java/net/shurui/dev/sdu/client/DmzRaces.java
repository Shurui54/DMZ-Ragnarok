package net.shurui.dev.sdu.client;

import net.shurui.dev.sdu.DmzNpc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * All DragonMine Z race ids currently loaded (defaults + any custom races), read from DMZ's
 * {@code ConfigManager}. Used to populate the form editor's race selector. Guarded and rebuilt on
 * demand so it reflects races added since launch; falls back to the synced character-config keys.
 */
public final class DmzRaces {

    private DmzRaces() {
    }

    public static List<String> raceIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            List<String> loaded = com.dragonminez.common.config.ConfigManager.getLoadedRaces();
            if (loaded != null) {
                ids.addAll(loaded);
            }
            if (ids.isEmpty()) {
                var chars = com.dragonminez.common.config.ConfigManager.getAllRaceCharacters();
                if (chars != null) {
                    ids.addAll(chars.keySet());
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ races: {}", DmzNpc.MODID, t.toString());
        }
        List<String> list = new ArrayList<>(ids);
        list.sort(Comparator.naturalOrder());
        return list;
    }

    /** DMZ's built-in races (which it regenerates) - used to protect them from deletion in the editor. */
    public static java.util.Set<String> defaultRaceIds() {
        try {
            var def = com.dragonminez.common.config.ConfigManager.getDefaultRaces();
            if (def != null) {
                return new java.util.HashSet<>(def);
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ default races: {}", DmzNpc.MODID, t.toString());
        }
        return java.util.Set.of();
    }
}

