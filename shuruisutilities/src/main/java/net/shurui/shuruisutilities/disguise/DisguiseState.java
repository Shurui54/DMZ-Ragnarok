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
 * <h2>Survives shard hops (owner rule), cleared only by /disguise off or a real network leave.</h2>
 * A disguise MUST survive a shard hop, or a hop would unmask staff. It is held in a PER-PLAYER MERGE store (mirroring
 * {@code VanishStorage}): each entry carries its own DB-clock stamp, the merge keeps the later stamp per player, and a
 * removal is a stamped tombstone so an "un-disguise" carries across shards. That is why the store never uses a
 * whole-map last-writer key. It is NOT dropped on logout (a hop is a logout): the deferred, hop-aware clear lives in
 * {@code DisguiseServerEvents}, which removes a disguise only when the player has genuinely left the network (not a
 * hop), and {@code /disguise off} removes it explicitly. Both removals are audit-logged by the key.
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

    public static boolean isDisguised(UUID id)
    {
        return store != null && id != null && store.active.containsKey(id);
    }

    /** A copy of every disguised player's real UUID (server thread; safe to hand to a background task). */
    public static java.util.Set<UUID> disguisedIds()
    {
        return store == null ? new java.util.HashSet<>() : new java.util.HashSet<>(store.active.keySet());
    }

    public static DisguiseView get(UUID id)
    {
        return store == null || id == null ? null : store.active.get(id);
    }

    /** A snapshot for chat / command use: the visible name for a disguised sender, else null. */
    public static String visibleName(UUID id)
    {
        DisguiseView v = get(id);
        return v == null ? null : v.targetName;
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

    /** Send the full current snapshot to one player, with the see-through flag resolved for THAT viewer. */
    public static void syncTo(ServerPlayer viewer)
    {
        if (viewer == null || store == null)
            return;
        List<DisguiseView> all = new ArrayList<>(store.active.values());
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
                    PacketDisguiseSync p = incomingView == null
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
