package net.shurui.dev.shuruis_dmz_dungeons.client;

import java.util.HashMap;
import java.util.Map;

// client-side cache of DMZ NPC default stats. filled by SyncNpcDefaultsPacket replies. the spawner editor
// asks for an entity's defaults when it's picked, then reads them back once the reply lands.
// version() bumps on every reply so a screen can spot "something new arrived" without per-request callbacks.
public final class ClientNpcDefaults {

    public record Defaults(double health, double melee, double ki, int aiTier1Based) {
    }

    private static final Map<String, Defaults> CACHE = new HashMap<>();
    private static int version = 0;

    private ClientNpcDefaults() {
    }

    // store a reply and bump the version so open screens know to re-read
    public static void put(String entityId, double health, double melee, double ki, int aiTier1Based) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }
        CACHE.put(entityId, new Defaults(health, melee, ki, aiTier1Based));
        version++;
    }

    // cached defaults for entityId, or null if no reply has landed yet
    public static Defaults get(String entityId) {
        return entityId == null ? null : CACHE.get(entityId);
    }

    public static int version() {
        return version;
    }
}
