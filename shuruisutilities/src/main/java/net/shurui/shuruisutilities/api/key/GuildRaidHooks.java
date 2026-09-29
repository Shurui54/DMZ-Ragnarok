package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntity;

/**
 * Core-side hook for the PRIVATE guild planet raids and the guild gravity chamber's sparring (logic in the Ragnarok
 * Key, {@code dmz_ragnarok_key}). Core keeps everything persisted or registered: the raid SavedData
 * ({@code GuildRaidLockouts}, {@code GuildRaidVault}, {@code GuildRaidSalvageVault}, {@code GuildRaidSpoils}, whose
 * names and NBT are unchanged, and which public Space reads for planet busting and salvage), the raider marker
 * ({@code GuildRaidParticipant}, a persisted NBT flag Space's fly-up reads), the clone and spar dummy entities with
 * their AI goals, renderers and packets, and the chamber block, block entity (its DragonMineZ gravity zone), items
 * and packets. The raid state machine, the raid manager, the raid rules ({@code RaidCheck}), the clone spawning,
 * stats and targeting, the chamber spar manager and its Forge handlers, and the {@code guild:raid_lockouts} shard
 * state registration live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: no raid is running, so a fly-up never forfeits one (Space
 * clears a stale raider flag and returns the player to space, as it did keyless before); a raid clone targets nobody
 * and counts nobody as an owner (the fail-safe direction the clone targeting always took for an unresolvable raid);
 * the gravity chamber opens no menu and starts no spar.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class GuildRaidHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "guildraids";

    private GuildRaidHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether guild raids and chamber spars are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /**
         * A raider flew up off the planet: forfeit their live raid. Returns true when a live raid took it (the raid
         * resolves as a loss and brings them home). Keyless: false, no raid is running.
         */
        default boolean forfeitByLeaving(UUID player)
        {
            return false;
        }

        /** Whether a defending raid clone may target this entity. Keyless: false (target nobody). */
        default boolean cloneCanTarget(GuildRaidCloneEntity clone, LivingEntity target)
        {
            return false;
        }

        /** Whether this player belongs to the clone's owning (defending) guild. Keyless: false (there are no guilds). */
        default boolean cloneIsOwner(GuildRaidCloneEntity clone, ServerPlayer player)
        {
            return false;
        }

        /**
         * Open the gravity chamber's gravity + range menu (sneaking) or its sparring menu for this player. Returns
         * true when the click was handled. Keyless: false, nothing opens.
         */
        default boolean openChamber(ServerPlayer player, Level level, BlockPos pos, boolean sneaking)
        {
            return false;
        }

        /** Start a chamber spar against a copy of {@code memberId}. Keyless: no-op. */
        default void startChamberSpar(ServerPlayer player, BlockPos pos, UUID memberId)
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

    /** Whether guild raids and chamber spars are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
