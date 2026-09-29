package net.shurui.shuruisutilities.guilds;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import net.shurui.shuruisutilities.api.key.GuildHooks;
import net.shurui.shuruisutilities.guilds.model.ChunkKey;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.model.GuildRelation;
import net.shurui.shuruisutilities.guilds.model.GuildRole;

import net.minecraft.world.entity.player.Player;

/**
 * Central registry for all guilds: creation/disband, membership, chunk claims, diplomacy, and battle-power-driven
 * claim limits. Guilds persist as one JSON file each under {@code <SUdir>/guilds/data/}; the config lives at
 * {@code <SUdir>/guilds/config.json}.
 *
 * <p>A FACADE since S15: the same FQN and public statics, so Space, the grave, the ki-grief mixin, the protection and
 * region handlers, sparring, the claim upgrade item and the key's own features keep calling it unchanged. The registry
 * itself lives in the Ragnarok Key, reached through {@link GuildHooks}. Keyless there are no guilds: every lookup
 * answers null or empty, no chunk is claimed, every mutation does nothing, the settings are the defaults, and the
 * guild files are never read or written. The three profile-cache name helpers below are plain lookups and stay here.
 */
public final class GuildManager
{
    private GuildManager() {}

    private static GuildHooks.Impl impl()
    {
        return GuildHooks.get();
    }

    public static GuildConfig config()
    {
        return impl().config();
    }

    public static void load()
    {
        impl().load();
    }

    /**
     * Take a guild another server changed, replacing whatever we held. Never writes back: this IS the other server's
     * write arriving.
     */
    public static void replaceLocally(Guild g)
    {
        impl().replaceLocally(g);
    }

    /** Drop a guild another server disbanded. Local indexes only. */
    public static void forgetLocally(String id)
    {
        impl().forgetLocally(id);
    }

    public static void save(Guild g)
    {
        impl().save(g);
    }

    /** Persist the live {@link GuildConfig} back to {@code <SUdir>/guilds/config.json} after an admin edit. */
    public static void saveConfig()
    {
        impl().saveConfig();
    }

    public static void saveAll()
    {
        impl().saveAll();
    }

    public static Guild byId(String id)
    {
        return impl().byId(id);
    }

    public static Guild byName(String name)
    {
        return impl().byName(name);
    }

    public static Guild guildOf(UUID player)
    {
        return impl().guildOf(player);
    }

    public static Collection<Guild> allGuilds()
    {
        return impl().allGuilds();
    }

    public static Guild claimOwner(String chunkKey)
    {
        return impl().claimOwner(chunkKey);
    }

    public static Guild claimOwner(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos)
    {
        return claimOwner(ChunkKey.of(level, pos));
    }

    public static boolean nameTaken(String name)
    {
        return impl().nameTaken(name);
    }

    public static Guild create(String name, UUID leader)
    {
        return impl().create(name, leader);
    }

    public static void disband(Guild g)
    {
        impl().disband(g);
    }

    public static boolean rename(Guild g, String newName)
    {
        return impl().rename(g, newName);
    }

    public static void addMember(Guild g, UUID player, GuildRole role, String name)
    {
        impl().addMember(g, player, role, name);
    }

    public static void removeMember(Guild g, UUID player)
    {
        impl().removeMember(g, player);
    }

    public static void transferLeader(Guild g, UUID newLeader)
    {
        impl().transferLeader(g, newLeader);
    }

    public static boolean isClaimed(String chunkKey)
    {
        return impl().isClaimed(chunkKey);
    }

    public static void claim(Guild g, String chunkKey)
    {
        impl().claim(g, chunkKey);
    }

    public static void unclaim(Guild g, String chunkKey)
    {
        impl().unclaim(g, chunkKey);
    }

    public static void unclaimAll(Guild g)
    {
        impl().unclaimAll(g);
    }

    public static void setRelation(Guild from, Guild to, GuildRelation relation)
    {
        impl().setRelation(from, to, relation);
    }

    /** Effective relation between two guilds: enemy is one-sided; ally/truce require mutual agreement. */
    public static GuildRelation effectiveRelation(Guild a, Guild b)
    {
        return impl().effectiveRelation(a, b);
    }

    /** Refresh an online member's cached DMZ battle power and last-known name (persisted coalesced). */
    public static void refreshMemberPower(Player player)
    {
        impl().refreshMemberPower(player);
    }

    /** Flush the coalesced battle-power changes: at most one write per dirty guild per call. */
    public static void flushMemberPower()
    {
        impl().flushMemberPower();
    }

    /** Maximum number of chunks this guild may hold (member ceiling, battle-power gate, floor, cap and bonus). */
    public static long maxClaims(Guild g)
    {
        return impl().maxClaims(g);
    }

    /**
     * Permanently grant a guild {@code amount} more claimable chunks and persist it immediately.
     *
     * @return true only when the bonus was recorded AND durably saved.
     */
    public static boolean grantClaimBonus(Guild g, int amount)
    {
        return impl().grantClaimBonus(g, amount);
    }

    /** True if the guild controls more land than its power allows, making it raidable via overclaim. */
    public static boolean isOverclaimable(Guild g)
    {
        return impl().isOverclaimable(g);
    }

    public static List<String> allGuildNames()
    {
        return impl().allGuildNames();
    }

    /** Resolve a display name for a player uuid, falling back to the uuid string. */
    public static String nameForUuid(net.minecraft.server.MinecraftServer server, String uuid)
    {
        if (server != null && server.getProfileCache() != null)
        {
            try
            {
                return server.getProfileCache().get(UUID.fromString(uuid)).map(p -> p.getName()).orElse(uuid);
            }
            catch (Exception ignored)
            {
            }
        }
        return uuid;
    }

    /**
     * Display name for a member of {@code g}: the persisted last-known name if we have one, else the live profile
     * cache, else the raw uuid string (which callers shorten and mark). Prefer this over {@link #nameForUuid} for a
     * known member, so a genuine offline member no longer renders as raw hex. Read-only: never keys or persists.
     */
    public static String memberDisplayName(net.minecraft.server.MinecraftServer server, Guild g, String uuid)
    {
        if (g != null)
        {
            String stored = g.memberNames.get(uuid);
            if (stored != null && !stored.isBlank())
                return stored;
        }
        return nameForUuid(server, uuid);
    }

    /** Profile-cache name for a uuid, or null when the cache cannot resolve it (unlike {@link #nameForUuid}, no uuid fallback). */
    public static String profileCacheName(net.minecraft.server.MinecraftServer server, String uuid)
    {
        if (server != null && server.getProfileCache() != null)
        {
            try
            {
                return server.getProfileCache().get(UUID.fromString(uuid)).map(p -> p.getName()).orElse(null);
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }

    /** Any OTHER guild whose member map also contains this uuid (a duplicate-membership probe), or null. */
    public static Guild otherGuildWith(Guild self, String uuid)
    {
        return impl().otherGuildWith(self, uuid);
    }
}
