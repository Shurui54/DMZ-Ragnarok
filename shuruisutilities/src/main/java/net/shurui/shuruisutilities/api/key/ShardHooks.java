package net.shurui.shuruisutilities.api.key;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.patreon.PatreonGrants;
import net.shurui.shuruisutilities.shard.ShardCommandLog;
import net.shurui.shuruisutilities.shard.ShardEvents;
import net.shurui.shuruisutilities.shard.ShardPresence;
import net.shurui.shuruisutilities.shard.ShardPunishments;

/**
 * Core-side hook for the PRIVATE cross-server network (the shard engine: the player vault, hops, presence, network
 * chat, event hosting, state sync, selector fan-out and the moderation database). Core keeps {@code shard.json}
 * ({@code ShardConfig}), the executor, the ghost packet and codec, the netkick, the handoff record, the pre-hop
 * teardown registry, and a facade at every old class name ({@code ShardSync}, {@code ShardStateSync},
 * {@code ShardEvents}, {@code ShardPresence}, {@code ShardTransfer}, {@code ShardRouter}, {@code ShardChat},
 * {@code ShardSelectorFanout}, {@code ShardDimensions}, {@code ShardPunishments}, {@code ShardCommandLog}) whose
 * bodies call this hook. The engine answers only once the key installs it.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour, and each matches what a keyless server answered before
 * this hook existed: the network is off ({@link Impl#active()} false), nobody is handing off, nobody is online
 * anywhere else, nothing is published or polled, and a moderation or command-log record writes only its
 * {@code [audit]} server log line (the facades keep that half in core). The keyless server-spawn placement is not
 * behind this hook at all: it lives in core ({@code SpawnPoints.placeAtServerSpawn}).
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads). The
 * key installs this FIRST, so every later key install already sees it.
 */
public final class ShardHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "shard";

    private ShardHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the key installed the network engine (not whether the network is configured on). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        // ------------------------------------------------------------------------------------------ ShardSync

        /** Whether the network is live: {@code shard.json} enabled and the key present. Keyless: false. */
        default boolean active()
        {
            return false;
        }

        /** True while this player is being handed to another server. Keyless: false (nobody is). */
        default boolean isHandingOff(UUID id)
        {
            return false;
        }

        /** Save this player to the shared vault now (a slot switch). Keyless: nothing to save to. */
        default void persistNow(ServerPlayer player)
        {
        }

        /** Save and release a player, then run the move. Keyless: the move runs at once, as with the network off. */
        default void handOff(ServerPlayer player, Runnable afterRelease)
        {
            afterRelease.run();
        }

        // ------------------------------------------------------------------------- ShardTransfer / ShardRouter

        /** Ask the proxy to move this player to another server. Keyless: ignored (every caller checks first). */
        default void connect(ServerPlayer player, String serverId)
        {
        }

        /** Record why a player is on their way to another server. Keyless: ignored. */
        default void expectArrival(UUID player, String serverId, UUID target)
        {
        }

        /** The open world a login or {@code /spawn} would be sent to. Keyless: none. */
        default String pickTarget()
        {
            return null;
        }

        // ------------------------------------------------------------------------------------- ShardPresence

        /** A player online anywhere on the network, by name. Keyless: nobody. */
        default ShardPresence.Located find(String name)
        {
            return null;
        }

        /** A player online anywhere on the network, by id. Keyless: nobody. */
        default ShardPresence.Located findById(UUID id)
        {
            return null;
        }

        /** Whether this player is online on any server. Keyless: false. */
        default boolean isOnlineAnywhere(UUID id)
        {
            return false;
        }

        /** How many players are online across the network. Keyless: 0. */
        default int onlineCount()
        {
            return 0;
        }

        /** Everyone online across the network. Keyless: empty. */
        default List<ShardPresence.Located> all()
        {
            return List.of();
        }

        /** Everyone online across the network with their skin. Keyless: empty. */
        default List<ShardPresence.Skinned> allWithSkins()
        {
            return List.of();
        }

        /** The remote player names offered to tab completion (the cached roster, no database read). Keyless: none. */
        default List<String> networkPlayerNames()
        {
            return List.of();
        }

        // ----------------------------------------------------------------------------------------- ShardChat

        /** Publish a decorated public chat line to the other servers. Keyless: ignored. */
        default void publishChat(ServerPlayer sender, Component decoratedLine, boolean senderVanished, String rawBody,
                Component bodyPrefix)
        {
        }

        /** Say something here AND on every other server. Keyless: ignored (not even shown here, as before). */
        default void announce(Component line)
        {
        }

        /** Show a line on every OTHER server. Keyless: ignored. */
        default void announceRemote(Component line)
        {
        }

        /** Publish a line on a non-public channel. Keyless: ignored. */
        default void publishChannel(String channel, Component line)
        {
        }

        /** Show a title on every OTHER server. Keyless: ignored. */
        default void announceTitleRemote(Component title, Component subtitle, int fadeIn, int stay, int fadeOut)
        {
        }

        /** Deliver a warning to one player wherever they are. Keyless: ignored. */
        default void sendWarning(UUID target, Component chatLine, Component title, Component subtitle, int fadeIn,
                int stay, int fadeOut)
        {
        }

        // --------------------------------------------------------------------------------------- ShardEvents

        /** Whether this server may host network events. Keyless: false. */
        default boolean eventsEligibleHost()
        {
            return false;
        }

        /** Take the network event a routed player came to join. Keyless: none. */
        default ShardEvents.Pending consumePendingEvent(ServerPlayer player)
        {
            return null;
        }

        /** Send a player to the server an event runs on. Keyless: false (the caller joins locally). */
        default boolean routeToEvent(ServerPlayer player, String kind, String defId, String hostServer)
        {
            return false;
        }

        /** Which server hosts an event. Keyless: none. */
        default ShardEvents.Host eventHost(String kind, String defId)
        {
            return null;
        }

        /** Record this server as an event's host. Keyless: ignored. */
        default void setEventHost(String kind, String defId, String phase)
        {
        }

        /** Stop hosting an event. Keyless: ignored. */
        default void clearEventHost(String kind, String defId)
        {
        }

        /** Enter the election for an event slot. Keyless: ignored (onWon never runs). */
        default void submitEventElection(String kind, String defId, long slotMillis, int players, Runnable onWon)
        {
        }

        // ------------------------------------------------------------------------------------ ShardStateSync

        /** True while applying state the network sent. Keyless: false. */
        default boolean stateSyncApplying()
        {
            return false;
        }

        /** Shared database clock minus local clock. Keyless: 0 (local time is the clock). */
        default long dbClockOffsetMillis()
        {
            return 0L;
        }

        /** Publish one registered state entry now. Keyless: ignored. */
        default void forcePublishState(String key)
        {
        }

        // ------------------------------------------------------------------------------- ShardSelectorFanout

        /** Whether a selector fan-out would be consumed anywhere. Keyless: false. */
        default boolean selectorFanoutActive()
        {
            return false;
        }

        /** The selector scope code ({@code ShardSelectorFanout.Scope} ordinal). Keyless: 0, LOCAL_ONLY. */
        default int classifySelector(String fullInput)
        {
            return 0;
        }

        /** Queue a by-name copy of a selector command for every other server. Keyless: ignored. */
        default void fanOutByName(ServerPlayer sender, String before, String after, Collection<UUID> localExclude)
        {
        }

        // ------------------------------------------------------- ShardPunishments / ShardCommandLog databases

        /** Write one punishment row to the shared table. Keyless: ignored (the audit line is already written). */
        default void recordPunishmentRow(String action, UUID targetId, String targetName, String reason,
                long durationSeconds, UUID issuerId, String issuerName, String shard)
        {
        }

        /** Whether the punishment history table can be read. Keyless: false. */
        default boolean punishmentsAvailable()
        {
            return false;
        }

        /** Read a player's punishment history off thread. Keyless: an empty list at once. */
        default void punishmentHistory(UUID targetId, String targetName, int limit, int offset,
                Consumer<List<ShardPunishments.Entry>> onDone, Consumer<Throwable> onError)
        {
            onDone.accept(new ArrayList<>());
        }

        /** Queue one command-log row for the shared table. Keyless: ignored (the audit line is already written). */
        default void commandLogRow(ShardCommandLog.Row row)
        {
        }

        /** Whether the command log table can be read. Keyless: false. */
        default boolean commandLogAvailable()
        {
            return false;
        }

        /** Read a player's command history off thread. Keyless: an empty list at once. */
        default void commandLookup(UUID playerId, String playerName, int limit, int offset,
                Consumer<List<ShardCommandLog.Entry>> onDone, Consumer<Throwable> onError)
        {
            onDone.accept(new ArrayList<>());
        }

        // ---------------------------------------------------------- core call sites whose shard class has no facade

        /** A player's DragonMineZ character from the shared vault (blocking). Keyless: none (local playerdata). */
        default CompoundTag vaultCharacter(UUID id)
        {
            return null;
        }

        /** Push reloaded Patreon file grants to the network and pull the shared set back. Keyless: ignored. */
        default void patreonGrantsReloaded(List<PatreonGrants.GrantEntry> fileGrants)
        {
        }

        /** Publish local region edits to the network. Keyless: ignored. */
        default void regionsSavedLocally()
        {
        }

        /**
         * An SU data file was INTENTIONALLY deleted here (a portal, kit, jail point or world border removed through
         * its command or GUI, so {@code DataManager.delete} really removed the file). The network engine turns this
         * into a tombstone so the entry does not come straight back from its still-present config-sync row on the
         * next poll or reboot, and so a re-create later (a newer stamp) wins over the tombstone.
         *
         * <p>{@code folder} is the {@code DataManager} type folder (the persisted class's simple name, e.g.
         * {@code "Portal"}); {@code name} is the file key without {@code .json}. The engine ignores folders that do
         * not travel and does nothing off the network, so this is always safe to call. Keyless / off the network:
         * ignored (a local delete is already complete and there is nothing to sync).
         *
         * <p>Only a delete that actually removed a file reaches here, so this can never tombstone an entry that was
         * merely missing on disk (a fresh shard, a manual copy, a read error).
         */
        default void suDataFileDeleted(String folder, String name)
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

    /** Whether the key installed the network engine on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
