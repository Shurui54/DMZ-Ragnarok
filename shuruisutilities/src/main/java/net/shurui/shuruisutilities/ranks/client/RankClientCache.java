package net.shurui.shuruisutilities.ranks.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.shurui.shuruisutilities.ranks.RankManager;

/**
 * Client-side cache of each player's rank NAME (empty = none), synced from the server so the name-tag and
 * tab-list renderers can draw, and animate, the badge from the client's own copy of the rank index.
 * No server-only references.
 */
public final class RankClientCache
{
    private RankClientCache() {}

    private static final Map<UUID, String> ranks = new ConcurrentHashMap<>();

    public static void put(UUID player, String rankName)
    {
        if (rankName == null || rankName.isEmpty())
            ranks.remove(player);
        else
            ranks.put(player, rankName);
    }

    public static void replaceAll(Map<UUID, String> fresh)
    {
        ranks.clear();
        for (Map.Entry<UUID, String> e : fresh.entrySet())
            put(e.getKey(), e.getValue());
    }

    /** Forget every cached rank on disconnect, so one server's ranks never draw on the next server or the menu. */
    public static void clear()
    {
        ranks.clear();
    }

    /** The player's rank, resolved against the client rank index, or null if they have none. */
    public static RankManager.Rank rankOf(UUID player)
    {
        String name = ranks.get(player);
        if (name == null || name.isEmpty())
            return null;
        RankManager.ensureIndexLoaded();
        return RankManager.get(name);
    }
}
