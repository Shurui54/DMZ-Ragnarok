package net.shurui.dev.shuruis_raid_bosses.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.shurui.dev.shuruis_raid_bosses.item.ZStat;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player record of how far each DMZ stat is pushed BEYOND the global cap via Z-Souls, and the source
 * of truth for it. The live DMZ bonus is only a projection applied while the soul is worn
 * ({@code ZSoulManager}). Stored apart from raid data so invested points survive un-equipping, relogs,
 * and swapping to a lower-tier soul.
 */
public class ZSoulData extends SavedData {
    private static final String NAME = "shuruis_raid_bosses_zsouls";

    private final Map<UUID, EnumMap<ZStat, Integer>> invested = new HashMap<>();

    public static ZSoulData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(ZSoulData::load, ZSoulData::new, NAME);
    }

    public int get(UUID player, ZStat stat) {
        EnumMap<ZStat, Integer> m = invested.get(player);
        return m == null ? 0 : m.getOrDefault(stat, 0);
    }

    /** clamped to >= 0 */
    public void set(UUID player, ZStat stat, int amount) {
        int v = Math.max(0, amount);
        EnumMap<ZStat, Integer> m = invested.computeIfAbsent(player, k -> new EnumMap<>(ZStat.class));
        Integer old = m.put(stat, v);
        if (old == null || old != v) setDirty();
    }

    /** serialise one player's banked points, for storing Z-Soul progress per character slot */
    public CompoundTag saveFor(UUID player) {
        CompoundTag tag = new CompoundTag();
        EnumMap<ZStat, Integer> m = invested.get(player);
        if (m != null) {
            m.forEach((stat, value) -> {
                if (value != null && value > 0) tag.putInt(stat.name(), value);
            });
        }
        return tag;
    }

    /** replace one player's banked points from NBT; an empty/absent tag clears them */
    public void loadFor(UUID player, CompoundTag tag) {
        EnumMap<ZStat, Integer> m = new EnumMap<>(ZStat.class);
        if (tag != null) {
            for (ZStat stat : ZStat.values()) {
                if (tag.contains(stat.name())) m.put(stat, tag.getInt(stat.name()));
            }
        }
        if (m.isEmpty()) {
            invested.remove(player);
        } else {
            invested.put(player, m);
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag players = new CompoundTag();
        invested.forEach((uuid, stats) -> {
            CompoundTag statTag = new CompoundTag();
            stats.forEach((stat, value) -> {
                if (value != null && value > 0) statTag.putInt(stat.name(), value);
            });
            if (!statTag.isEmpty()) players.put(uuid.toString(), statTag);
        });
        tag.put("players", players);
        return tag;
    }

    public static ZSoulData load(CompoundTag tag) {
        ZSoulData data = new ZSoulData();
        CompoundTag players = tag.getCompound("players");
        for (String key : players.getAllKeys()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            CompoundTag statTag = players.getCompound(key);
            EnumMap<ZStat, Integer> m = new EnumMap<>(ZStat.class);
            for (ZStat stat : ZStat.values()) {
                if (statTag.contains(stat.name())) m.put(stat, statTag.getInt(stat.name()));
            }
            if (!m.isEmpty()) data.invested.put(uuid, m);
        }
        return data;
    }
}
