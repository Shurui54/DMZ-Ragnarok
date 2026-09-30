package net.shurui.shuruisutilities.disguise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.shard.ShardStateSync;

/**
 * The server-side registry of who is currently disguised, and the ONLY writer of the client sync. The PRIVATE key
 * feature ({@code DisguiseHooks} impl in the Ragnarok Key) builds a {@link DisguiseView} and calls {@link #set}; core
 * owns the store, the broadcast and the lifecycle so none of it depends on the key mod at that call site (the store
 * simply stays empty on a keyless server, since nothing calls {@link #set}).
 *
 * <h2>Fully persistent (owner rule): survives shard hops AND logout/login, cleared only by /disguise clear.</h2>
 * A disguise MUST survive a shard hop, or a hop would unmask staff, and the owner further requires it to survive a
 * genuine logout/login so staff stay disguised across sessions. It is held in a PER-PLAYER MERGE store (mirroring
 * {@code VanishStorage}): each entry carries its own DB-clock stamp, the merge keeps the later stamp per player, and a
 * removal is a stamped tombstone so a clear or a toggle carries across shards. That is why the store never uses a
 * whole-map last-writer key. It is NOT dropped on logout of any kind (a hop and a quit both persist); only an explicit
 * {@code /disguise clear} removes it. {@code /disguise off} does not remove it: it flips {@link DisguiseView#enabled}
 * to false (kept, synced, rendered as the real identity) so {@code /disguise on} restores the same view. Clears and
 * toggles are audit-logged by the key.
 */
public final class DisguiseState
{
    /** Permission that lets a viewer see the real identity behind a disguise (tab / nametag marker). */
    public static final String PERM_SEE_THROUGH = "shuruisutilities.disguise.seethrough";

    private static final String SHARD_KEY = "dmzr:disguises";
    private static final String SAVED_NAME = "shuruisutilities_disguises";

    private static Store store;

    private DisguiseState() {}

    /** Bind to the running server's SavedData and register the shard sync. Idempotent; call at server start. */
    public static void bind(MinecraftServer server)
    {
        if (server == null)
            return;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        store = overworld.getDataStorage().computeIfAbsent(Store::load, Store::new, SAVED_NAME);
        ShardStateSync.register(SHARD_KEY, DisguiseState::readForShard, DisguiseState::applyFromShard,
                () -> store == null ? 0L : store.shardDirtyVersion());
    }

    public static void unbind()
    {
        store = null;
    }

    /** Whether this player is ACTIVELY disguised: a disguise is configured AND it is toggled on. */
    public static boolean isDisguised(UUID id)
    {
        DisguiseView v = raw(id);
        return v != null && v.enabled;
    }

    /** Whether a disguise is configured for this player, whether or not it is currently toggled on. */
    public static boolean hasConfigured(UUID id)
    {
        return raw(id) != null;
    }

    /** Whether the configured disguise is currently toggled on. False when none is configured. */
    public static boolean isEnabled(UUID id)
    {
        DisguiseView v = raw(id);
        return v != null && v.enabled;
    }

    /** A copy of every configured (enabled OR disabled) disguise's real UUID; safe to hand to a background task. */
    public static java.util.Set<UUID> disguisedIds()
    {
        return store == null ? new java.util.HashSet<>() : new java.util.HashSet<>(store.active.keySet());
    }

    /** The stored view regardless of the on/off flag (for the toggle path); null when none is configured. */
    private static DisguiseView raw(UUID id)
    {
        return store == null || id == null ? null : store.active.get(id);
    }

    /** The ACTIVE disguise for this player (null when none is configured or it is toggled off). */
    public static DisguiseView get(UUID id)
    {
        DisguiseView v = raw(id);
        return v != null && v.enabled ? v : null;
    }

    /** A snapshot for chat / command / join-leave use: the visible name for an actively disguised player, else null. */
    public static String visibleName(UUID id)
    {
        DisguiseView v = get(id);
        return v == null ? null : v.displayName();
    }

    /** A snapshot for chat: the rank id a disguised sender should show ("" = none), else null when not disguised. */
    public static String visibleRankId(UUID id)
    {
        DisguiseView v = get(id);
        return v == null ? null : v.rankId;
    }

    /**
     * Chat helper: the rank badge codepoint a disguised sender should show, or -1 when they are not disguised (the
     * caller then uses the sender's real rank). A disguised sender with no rank returns 0 (no badge).
     */
    public static int visibleRankCodepoint(UUID id)
    {
        DisguiseView v = get(id);
        if (v == null)
            return -1;
        if (v.rankId == null || v.rankId.isEmpty())
            return 0;
        net.shurui.shuruisutilities.ranks.RankManager.Rank rank =
                net.shurui.shuruisutilities.ranks.RankManager.get(v.rankId);
        return rank == null ? 0 : rank.codepoint();
    }

    /** Chat helper: the crown codepoint a disguised sender should show, or -1 when they are not disguised. */
    public static int visibleCrownCodepoint(UUID id)
    {
        DisguiseView v = get(id);
        return v == null ? -1 : v.crownCodepoint;
    }

    /** Install / replace a disguise and push it to every online client. */
    public static void set(MinecraftServer server, DisguiseView view)
    {
        if (server == null || store == null || view == null || view.realId == null)
            return;
        store.set(view);
        broadcast(server, PacketDisguiseSync.single(view));
    }

    /** Remove a disguise (a stamped tombstone) and tell every client. Returns true when one was removed. */
    public static boolean clear(MinecraftServer server, UUID realId)
    {
        if (server == null || store == null || realId == null)
            return false;
        if (!store.clear(realId))
            return false;
        broadcast(server, PacketDisguiseSync.clear(realId));
        return true;
    }

    /**
     * Toggle a configured disguise on or off WITHOUT losing it. Disabling keeps the stored view (so it persists and
     * shard-syncs) and pushes a CLEAR to clients so they render the real identity; enabling pushes the stored view
     * back. Returns -1 when no disguise is configured, 0 when it was already in that state, 1 when it changed.
     */
    public static int setEnabled(MinecraftServer server, UUID realId, boolean enable)
    {
        if (server == null || store == null || realId == null)
            return -1;
        DisguiseView v = store.active.get(realId);
        if (v == null)
            return -1;
        if (v.enabled == enable)
            return 0;
        store.setEnabled(realId, enable);
        broadcast(server, enable ? PacketDisguiseSync.single(v) : PacketDisguiseSync.clear(realId));
        return 1;
    }

    /**
     * Push this player's ACTIVE disguise (if any) to every online client. Called when a disguised player joins, so
     * everyone already online is shown the disguise at once rather than after their next full snapshot, and no client
     * flashes the real name. A no-op when the player is not actively disguised.
     */
    public static void rebroadcast(MinecraftServer server, UUID realId)
    {
        DisguiseView v = get(realId);
        if (server == null || v == null)
            return;
        broadcast(server, PacketDisguiseSync.single(v));
    }

    /** Send the full snapshot of ACTIVE disguises to one player, with the see-through flag resolved for THAT viewer. */
    public static void syncTo(ServerPlayer viewer)
    {
        if (viewer == null || store == null)
            return;
        List<DisguiseView> all = new ArrayList<>();
        for (DisguiseView v : store.active.values())
            if (v.enabled)
                all.add(v);
        NetworkUtils.sendTo(PacketDisguiseSync.snapshot(all, canSeeThrough(viewer)), viewer);
    }

    private static void broadcast(MinecraftServer server, PacketDisguiseSync p)
    {
        for (ServerPlayer sp : server.getPlayerList().getPlayers())
            NetworkUtils.sendTo(p, sp);
    }

    /**
     * Whether this viewer may be shown the real identity. Staff (the see-through permission) can; everyone else sees
     * only the disguise. Defensive: a perms backend that is not ready falls back to the server op check.
     */
    public static boolean canSeeThrough(ServerPlayer viewer)
    {
        if (viewer.hasPermissions(2))
            return true;
        try
        {
            return net.shurui.shuruisutilities.api.APIRegistry.perms.checkPermission(viewer, PERM_SEE_THROUGH);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // --- ShardStateSync glue -------------------------------------------------------------------------------------

    private static CompoundTag readForShard()
    {
        return store == null ? null : store.save(new CompoundTag());
    }

    private static void applyFromShard(CompoundTag t)
    {
        if (store == null || t == null)
            return;
        store.mergeInto(t);
    }

    /** The persisted, per-player merge store (name {@code shuruisutilities_disguises}). Do not rename its keys. */
    public static final class Store extends SavedData
    {
        final Map<UUID, DisguiseView> active = new HashMap<>();
        final Map<UUID, Long> stampedAt = new HashMap<>();
        private long shardDirtyVersion;

        long shardDirtyVersion()
        {
            return shardDirtyVersion;
        }

        @Override
        public void setDirty()
        {
            shardDirtyVersion++;
            super.setDirty();
        }

        void set(DisguiseView v)
        {
            active.put(v.realId, v);
            stampedAt.put(v.realId, dbNow());
            setDirty();
        }

        boolean clear(UUID id)
        {
            boolean had = active.remove(id) != null;
            stampedAt.put(id, dbNow());
            setDirty();
            return had;
        }

        /** Flip the on/off flag on a stored view and re-stamp it, so the toggle carries across shards like any edit. */
        void setEnabled(UUID id, boolean enabled)
        {
            DisguiseView v = active.get(id);
            if (v == null)
                return;
            v.enabled = enabled;
            stampedAt.put(id, dbNow());
            setDirty();
        }

        static Store load(CompoundTag tag)
        {
            Store s = new Store();
            CompoundTag views = tag.getCompound("views");
            for (String key : views.getAllKeys())
            {
                try
                {
                    s.active.put(UUID.fromString(key), DisguiseView.fromNbt(views.getCompound(key)));
                }
                catch (IllegalArgumentException ignored)
                {
                }
            }
            CompoundTag stamps = tag.getCompound("stamps");
            for (String key : stamps.getAllKeys())
            {
                try
                {
                    s.stampedAt.put(UUID.fromString(key), stamps.getLong(key));
                }
                catch (IllegalArgumentException ignored)
                {
                }
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag)
        {
            CompoundTag views = new CompoundTag();
            for (Map.Entry<UUID, DisguiseView> e : active.entrySet())
                views.put(e.getKey().toString(), e.getValue().toNbt());
            tag.put("views", views);
            CompoundTag stamps = new CompoundTag();
            for (Map.Entry<UUID, Long> e : stampedAt.entrySet())
                stamps.putLong(e.getKey().toString(), e.getValue());
            tag.put("stamps", stamps);
            return tag;
        }

        void mergeInto(CompoundTag tag)
        {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            CompoundTag inViews = tag.getCompound("views");
            CompoundTag inStamps = tag.getCompound("stamps");
            boolean changed = false;
            for (String key : inStamps.getAllKeys())
            {
                UUID id;
                try
                {
                    id = UUID.fromString(key);
                }
                catch (IllegalArgumentException ignored)
                {
                    continue;
                }
                long incoming = inStamps.getLong(key);
                if (incoming <= stampedAt.getOrDefault(id, 0L))
                    continue;
                DisguiseView incomingView = inViews.contains(key) ? DisguiseView.fromNbt(inViews.getCompound(key)) : null;
                if (incomingView == null)
                    active.remove(id);
                else
                    active.put(id, incomingView);
                if (server != null)
                {
                    // A view that arrived toggled OFF is pushed to clients as a CLEAR, exactly like a removal, so the
                    // client only ever holds ACTIVE disguises; the disabled view still lives in the store above.
                    PacketDisguiseSync p = incomingView == null || !incomingView.enabled
                            ? PacketDisguiseSync.clear(id) : PacketDisguiseSync.single(incomingView);
                    for (ServerPlayer sp : server.getPlayerList().getPlayers())
                        NetworkUtils.sendTo(p, sp);
                }
                stampedAt.put(id, incoming);
                changed = true;
            }
            if (changed)
                setDirty();
        }

        private static long dbNow()
        {
            return System.currentTimeMillis() + ShardStateSync.dbClockOffsetMillis();
        }
    }
}
