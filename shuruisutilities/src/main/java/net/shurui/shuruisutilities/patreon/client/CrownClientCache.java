package net.shurui.shuruisutilities.patreon.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of each player's supporter crown codepoint (0 = none), synced from the server so the name-tag
 * and tab-list renderers can draw it. Entitlements themselves never reach the client: the server sends only the
 * resolved glyph, so a client can neither see nor assert anyone's tier. No server-only references.
 */
public final class CrownClientCache
{
    private CrownClientCache() {}

    private static final Map<UUID, Integer> crowns = new ConcurrentHashMap<>();

    public static void put(UUID player, int codepoint)
    {
        if (codepoint <= 0)
            crowns.remove(player);
        else
            crowns.put(player, codepoint);
    }

    public static void replaceAll(Map<UUID, Integer> fresh)
    {
        crowns.clear();
        for (Map.Entry<UUID, Integer> e : fresh.entrySet())
            put(e.getKey(), e.getValue());
    }

    /** The player's crown codepoint, or 0 when they have none. */
    public static int codepointOf(UUID player)
    {
        Integer cp = crowns.get(player);
        return cp == null ? 0 : cp;
    }
}
