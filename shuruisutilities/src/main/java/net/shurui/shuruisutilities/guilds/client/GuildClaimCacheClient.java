package net.shurui.shuruisutilities.guilds.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.ChunkPos;

/**
 * Client-side cache of guild-claimed chunks, kept in sync by {@code PacketGuildClaims}. Colors are
 * pre-computed on the server relative to the viewing player (own guild / ally / enemy / neutral), so the
 * client just looks them up. Contains no Xaero references, so it is safe to load even without Xaero.
 */
public final class GuildClaimCacheClient
{
    private GuildClaimCacheClient() {}

    public static final class Claim
    {
        public final int color;   // ARGB
        public final String guild;

        public Claim(int color, String guild)
        {
            this.color = color;
            this.guild = guild;
        }
    }

    // dimension id -> (packed chunk -> claim)
    private static final Map<String, Map<Long, Claim>> byDim = new ConcurrentHashMap<>();

    /** Bumped on every update so Xaero's world-map region-highlight cache knows to re-render. */
    private static volatile int version = 0;

    public static void replaceAll(Map<String, Map<Long, Claim>> fresh)
    {
        byDim.clear();
        byDim.putAll(fresh);
        version++;
    }

    public static int version()
    {
        return version;
    }

    public static Map<String, Map<Long, Claim>> newBucket()
    {
        return new HashMap<>();
    }

    public static void put(Map<String, Map<Long, Claim>> bucket, String dim, int cx, int cz, int color, String guild)
    {
        bucket.computeIfAbsent(dim, d -> new HashMap<>()).put(ChunkPos.asLong(cx, cz), new Claim(color, guild));
    }

    public static boolean hasAnyIn(String dim)
    {
        Map<Long, Claim> m = byDim.get(dim);
        return m != null && !m.isEmpty();
    }

    public static Claim at(String dim, int cx, int cz)
    {
        Map<Long, Claim> m = byDim.get(dim);
        return m == null ? null : m.get(ChunkPos.asLong(cx, cz));
    }
}
