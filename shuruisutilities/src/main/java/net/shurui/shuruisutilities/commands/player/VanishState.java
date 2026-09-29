package net.shurui.shuruisutilities.commands.player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The persisted vanish state and the visibility relation every vanish check reads (S18a: moved out of
 * {@code CommandVanish}, which now lives in the Ragnarok Key).
 *
 * <p>Stays in core on purpose: the set is seeded from {@link VanishStorage} at every server start, keyless included,
 * and the core mixins, the re-hide handler ({@link VanishEventHandler}) and the shard tables read it. That is exactly
 * what a keyless server did before the move (a player vanished on a keyed server stays hidden there), so the keyless
 * answers are unchanged. Toggling vanish ({@code /vanish}) and the cross-shard {@code su:vanish} registration are the
 * key's.
 */
public final class VanishState
{
    private VanishState() {}

    // In-memory fast path, keyed by UUID. Backed by VanishStorage (overworld SavedData) so vanish survives a restart.
    // Loaded from the store at server start via loadFromStorage(); written on every toggle, and on this shard's copy
    // of another shard's toggle (see applyReplicated). A concurrent set because isVanished is read from the
    // packet-send path, which can run off the server thread, while writes happen on the server thread.
    private static final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();

    /** Whether the player with this UUID is currently vanished. Usable from event handlers and mixins. */
    public static boolean isVanished(UUID uuid)
    {
        return uuid != null && vanishedPlayers.contains(uuid);
    }

    /**
     * The per-viewer visibility relation at the heart of "vanished people can see other vanished people". A player
     * who is NOT vanished is visible to everyone. A vanished player is visible only to themselves and to OTHER
     * vanished players. A null viewer resolves to hidden: never guess a vanished player into view.
     */
    public static boolean canSee(UUID viewerId, UUID targetId)
    {
        if (!isVanished(targetId))
            return true;
        if (viewerId == null)
            return false;
        if (viewerId.equals(targetId))
            return true;
        return isVanished(viewerId);
    }

    /** {@link #canSee(UUID, UUID)} for a live viewer entity. */
    public static boolean canSee(ServerPlayer viewer, UUID targetId)
    {
        return canSee(viewer == null ? null : viewer.getUUID(), targetId);
    }

    /** Record a vanish or un-vanish in the fast path (the key's toggle, or a replicated merge). */
    public static void set(UUID uuid, boolean vanished)
    {
        if (uuid == null)
            return;
        if (vanished)
            vanishedPlayers.add(uuid);
        else
            vanishedPlayers.remove(uuid);
    }

    /**
     * Adopt a vanish toggle that reached this shard from another one (called by {@link VanishStorage#mergeInto}), so
     * the fast path stays true for a player on a DIFFERENT server. Runs on the server thread.
     */
    public static void applyReplicated(UUID uuid, boolean vanished)
    {
        set(uuid, vanished);
    }

    /**
     * Seed the in-memory set from the persisted VanishStorage. Called once at server start so vanish state survives a
     * restart and the join-message / tab-list checks see the right value before the player's own login runs.
     */
    public static void loadFromStorage(MinecraftServer server)
    {
        vanishedPlayers.clear();
        vanishedPlayers.addAll(VanishStorage.get(server).all());
    }

    /**
     * Send a vanilla-styled presence line (a join or leave) only to the online players who are NOT vanished, skipping
     * the subject. Used for the FAKE leave/join shown when a player TOGGLES vanish.
     */
    public static void announcePresenceToNonVanished(ServerPlayer subject, Component message)
    {
        MinecraftServer server = subject.getServer();
        if (server == null)
            return;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
        {
            if (p == subject)
                continue;
            if (!isVanished(p.getUUID()))
                p.sendSystemMessage(message);
        }
    }

    /**
     * Send a vanilla-styled presence line only to online players who can currently SEE the subject, skipping the
     * subject. Used when a vanished player REALLY joins, dies or disconnects: other vanished staff see it, ordinary
     * players do not.
     */
    public static void announcePresenceToSeers(ServerPlayer subject, Component message)
    {
        MinecraftServer server = subject.getServer();
        if (server == null)
            return;
        UUID id = subject.getUUID();
        for (ServerPlayer p : server.getPlayerList().getPlayers())
        {
            if (p == subject)
                continue;
            if (canSee(p, id))
                p.sendSystemMessage(message);
        }
    }
}
