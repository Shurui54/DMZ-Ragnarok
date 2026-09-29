package net.shurui.dev.shuruis_raid_bosses.raid;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-player damage against a raid boss, keyed by the boss entity UUID so concurrent raids
 * don't collide. Nothing is written to NBT per hit: {@code record} runs on every qualifying
 * {@code LivingDamageEvent}, totals are read once at {@code LivingDeathEvent}, then {@link #clear}.
 */
public final class RaidDamageTracker {
    private RaidDamageTracker() {}

    /** bossEntityUUID -> (playerUUID -> total damage dealt). */
    private static final Map<UUID, Map<UUID, Double>> DAMAGE = new ConcurrentHashMap<>();
    /** bossEntityUUID -> owning raid def id, so the death handler can find the raid. */
    private static final Map<UUID, String> BOSS_DEF = new ConcurrentHashMap<>();

    public static void register(UUID bossId, String defId) {
        DAMAGE.put(bossId, new ConcurrentHashMap<>());
        BOSS_DEF.put(bossId, defId);
    }

    public static boolean isBoss(UUID bossId) {
        return bossId != null && BOSS_DEF.containsKey(bossId);
    }

    /** raid def id this boss belongs to, or null */
    public static String defOf(UUID bossId) {
        return BOSS_DEF.get(bossId);
    }

    public static void record(UUID bossId, UUID player, double amount) {
        if (amount <= 0) return;
        Map<UUID, Double> map = DAMAGE.get(bossId);
        if (map != null) map.merge(player, amount, Double::sum);
    }

    /** per-player totals for a boss, empty if unknown */
    public static Map<UUID, Double> snapshot(UUID bossId) {
        return DAMAGE.getOrDefault(bossId, Map.of());
    }

    /** stop tracking a boss and free its maps, once the fight has fully resolved */
    public static void clear(UUID bossId) {
        DAMAGE.remove(bossId);
        BOSS_DEF.remove(bossId);
    }
}
