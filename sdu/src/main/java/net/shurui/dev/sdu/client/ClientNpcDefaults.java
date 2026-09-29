package net.shurui.dev.sdu.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of DMZ NPC default combat stats, keyed by entity id, synced from the server (which
 * resolves them via {@code DmzNpcDefaults}). The KILL objective editor requests an id's defaults and, once the
 * reply lands here, auto-fills its Health / Melee Damage / Ki Damage / AI Tier fields. {@code aiTier} is DMZ
 * 1-based (1 = SIMPLE).
 */
public final class ClientNpcDefaults {

    /** DMZ default stats for one entity id. */
    public record Defaults(double health, double melee, double ki, int aiTier) {
    }

    private static final Map<String, Defaults> CACHE = new ConcurrentHashMap<>();

    private ClientNpcDefaults() {
    }

    public static void put(String entityId, double health, double melee, double ki, int aiTier) {
        if (entityId != null && !entityId.isBlank()) {
            CACHE.put(entityId, new Defaults(health, melee, ki, aiTier));
        }
    }

    /** Cached defaults for {@code entityId}, or {@code null} if none synced yet. */
    public static Defaults get(String entityId) {
        return entityId == null ? null : CACHE.get(entityId);
    }
}
