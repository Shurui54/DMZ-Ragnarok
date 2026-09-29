package net.shurui.dev.sdu.race;

import java.util.LinkedHashMap;
import java.util.Map;

import net.shurui.dev.sdu.DmzNpc;

// client-readable raceId -> aura size lookup (width/height multipliers, default 1.0), read at render time by
// AuraScaleMixin to scale a player's base race aura. exists because DMZ's character.json auraWidth/auraHeight
// deserialize into a strongly-typed POJO that drops keys it doesn't model, so the mixin can't read them from
// DMZ's synced config. mirrors FormAuraConfig: the server builds it from the race files and pushes it to
// clients on login and after any edit. non-default entries only, so an unmodified race stays absent.
public final class RaceAuraConfig {

    public static final class Size {
        public final float width;
        public final float height;

        public Size(float width, float height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final Map<String, Size> BY_RACE = new LinkedHashMap<>();

    private RaceAuraConfig() {
    }

    // {1.0, 1.0} when none saved.
    public static Size get(String raceId) {
        Size s = BY_RACE.get(key(raceId));
        return s == null ? new Size(1.0f, 1.0f) : s;
    }

    // rebuild from the race files (server side); non-default entries only to keep the lookup + sync small.
    public static synchronized void rebuildFromDisk() {
        BY_RACE.clear();
        try {
            for (RaceData race : RaceFileManager.loadAll()) {
                if (race.auraWidth != 1.0f || race.auraHeight != 1.0f) {
                    BY_RACE.put(key(race.raceId), new Size(race.auraWidth, race.auraHeight));
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to build race-aura config: {}", DmzNpc.MODID, e.toString());
        }
    }

    // snapshot for server -> client sync.
    public static synchronized Map<String, Size> all() {
        return new LinkedHashMap<>(BY_RACE);
    }

    // replace from a server sync (client side; the client has no race files of its own).
    public static synchronized void applySynced(Map<String, Size> data) {
        BY_RACE.clear();
        if (data != null) {
            for (Map.Entry<String, Size> e : data.entrySet()) {
                if (e.getValue() != null && (e.getValue().width != 1.0f || e.getValue().height != 1.0f)) {
                    BY_RACE.put(key(e.getKey()), e.getValue());
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced race-aura config: {} race(s).", DmzNpc.MODID, BY_RACE.size());
    }

    private static String key(String raceId) {
        return raceId == null ? "" : raceId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
