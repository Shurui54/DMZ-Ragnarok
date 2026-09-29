package net.shurui.shuruisutilities.api.key;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.guilds.GuildChat;
import net.shurui.shuruisutilities.guilds.GuildConfig;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.model.GuildPermission;
import net.shurui.shuruisutilities.guilds.model.GuildRelation;
import net.shurui.shuruisutilities.guilds.model.GuildRole;

/**
 * Core-side hook for the PRIVATE guilds (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps
 * {@code guilds.GuildManager}, {@code guilds.GuildProtectionHandler} and {@code guilds.GuildChat} as facades with their
 * public statics unchanged (Space, the grave, the ki-grief mixin, the protection and region handlers, sparring and the
 * key's own features read them), plus the guild model, {@link GuildConfig}, the client screens, the packets and their
 * DTOs, the claim upgrade items, the DragonMineZ battle power bridge and the raid SavedData. The guild registry, the
 * module, the commands, the territory handler, the guild chat channels, the GUI server, the claim map sync, the shard
 * guild table bridge and the hub rows live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, there are no guilds (every
 * lookup answers null or empty), no chunk is claimed, nothing is protected, every mutation does nothing and nothing
 * is loaded or written. The files under {@code ShuruisUtilities/guilds/} are never touched keyless, so a keyed server
 * reads them again unchanged.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class GuildHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "guilds";

    /** The settings a keyless server answers with: the defaults, never read from or written to disk. */
    private static final GuildConfig KEYLESS_CONFIG = new GuildConfig();

    private GuildHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether guilds are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        // ---- registry (GuildManager facade) ----

        /** The live settings. Keyless: the defaults, never loaded or saved. */
        default GuildConfig config()
        {
            return KEYLESS_CONFIG;
        }

        /** Load the settings and every guild. Keyless: no-op (nothing is read or created). */
        default void load()
        {
        }

        /** Persist one guild. Keyless: no-op. */
        default void save(Guild g)
        {
        }

        /** Persist the settings. Keyless: no-op. */
        default void saveConfig()
        {
        }

        /** Persist every guild. Keyless: no-op. */
        default void saveAll()
        {
        }

        /** Keyless: null. */
        default Guild byId(String id)
        {
            return null;
        }

        /** Keyless: null. */
        default Guild byName(String name)
        {
            return null;
        }

        /** Keyless: null (nobody is in a guild). */
        default Guild guildOf(UUID player)
        {
            return null;
        }

        /** A fresh, mutable copy of every guild. Keyless: empty. */
        default Collection<Guild> allGuilds()
        {
            return new ArrayList<>();
        }

        /** The guild owning this chunk key. Keyless: null (no chunk is claimed). */
        default Guild claimOwner(String chunkKey)
        {
            return null;
        }

        /** Keyless: false. */
        default boolean nameTaken(String name)
        {
            return false;
        }

        /** Keyless: null, nothing created. */
        default Guild create(String name, UUID leader)
        {
            return null;
        }

        /** Keyless: no-op. */
        default void disband(Guild g)
        {
        }

        /** Keyless: false, nothing renamed. */
        default boolean rename(Guild g, String newName)
        {
            return false;
        }

        /** Keyless: no-op. */
        default void addMember(Guild g, UUID player, GuildRole role, String name)
        {
        }

        /** Keyless: no-op. */
        default void removeMember(Guild g, UUID player)
        {
        }

        /** Keyless: no-op. */
        default void transferLeader(Guild g, UUID newLeader)
        {
        }

        /** Keyless: false. */
        default boolean isClaimed(String chunkKey)
        {
            return false;
        }

        /** Keyless: no-op. */
        default void claim(Guild g, String chunkKey)
        {
        }

        /** Keyless: no-op. */
        default void unclaim(Guild g, String chunkKey)
        {
        }

        /** Keyless: no-op. */
        default void unclaimAll(Guild g)
        {
        }

        /** Keyless: no-op. */
        default void setRelation(Guild from, Guild to, GuildRelation relation)
        {
        }

        /** Keyless: neutral. */
        default GuildRelation effectiveRelation(Guild a, Guild b)
        {
            return GuildRelation.NEUTRAL;
        }

        /** Keyless: no-op. */
        default void refreshMemberPower(Player player)
        {
        }

        /** Keyless: no-op. */
        default void flushMemberPower()
        {
        }

        /** Keyless: 0. */
        default long maxClaims(Guild g)
        {
            return 0L;
        }

        /** Keyless: false, nothing granted. */
        default boolean grantClaimBonus(Guild g, int amount)
        {
            return false;
        }

        /** Keyless: false. */
        default boolean isOverclaimable(Guild g)
        {
            return false;
        }

        /** Keyless: empty. */
        default List<String> allGuildNames()
        {
            return new ArrayList<>();
        }

        /** Any other guild also listing this member. Keyless: null. */
        default Guild otherGuildWith(Guild self, String uuid)
        {
            return null;
        }

        /** Take a guild another server changed. Keyless: no-op. */
        default void replaceLocally(Guild g)
        {
        }

        /** Drop a guild another server disbanded. Keyless: no-op. */
        default void forgetLocally(String id)
        {
        }

        // ---- territory (GuildProtectionHandler facade) ----

        /** Whether claim protection is switched on. Keyless: false (there are no claims to protect). */
        default boolean protecting()
        {
            return false;
        }

        /** Whether this player holds the guild territory bypass node. Keyless: false. */
        default boolean hasBypass(Player player)
        {
            return false;
        }

        /** Whether this player may act at a chunk owned by {@code owner}. Keyless: true (nothing is claimed). */
        default boolean canAffect(Player player, Guild owner, GuildPermission perm)
        {
            return true;
        }

        // ---- shard guild table (the key's shard chat poll) ----

        /** Whether guilds are stored in the shared network table. Keyless: false. */
        default boolean shardShared()
        {
            return false;
        }

        // ---- guild chat (GuildChat facade) ----

        /** The player's active chat channel. Keyless: public (there is no guild or ally chat). */
        default GuildChat.Channel chatChannel(UUID player)
        {
            return GuildChat.Channel.PUBLIC;
        }

        /** Switch the player's active chat channel. Keyless: no-op. */
        default void setChatChannel(UUID player, GuildChat.Channel channel)
        {
        }

        /** Send a line to every online member of the guild. Keyless: no-op (there are no guilds). */
        default void sendToGuild(Guild guild, Component message)
        {
        }

        /** Send a line to the guild and every allied guild's online members. Keyless: no-op. */
        default void sendToAllies(Guild guild, Component message)
        {
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether guilds are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
