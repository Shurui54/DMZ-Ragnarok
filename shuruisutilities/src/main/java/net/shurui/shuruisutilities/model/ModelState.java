package net.shurui.shuruisutilities.model;

import java.util.HashMap;
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
 * Server-side registry of the LOOK-ONLY model override: which player is drawn as which rgnpc / DragonMineZ-NPC model.
 * Hitbox, stats, name, skin and rank are untouched; only the rendered model changes.
 *
 * <p>The owner spec requires a model override to survive relog AND a shard hop. It does, and it does so through a
 * PER-PLAYER MERGE store (never a whole-map last-writer key), mirroring {@code VanishStorage}: each entry carries its
 * own DB-clock-comparable stamp, the merge keeps the later stamp per player, and a removal is a stamped tombstone so
 * an "un-model" carries across shards just as a set does. So one admin's {@code /model} on shard A never clobbers
 * another admin's on shard B inside a sync interval. {@link SavedData} handles relog / restart; {@link ShardStateSync}
 * (key {@code dmzr:model_overrides}) handles the hop.
 */
public final class ModelState
{
    private static final String SHARD_KEY = "dmzr:model_overrides";
    private static final String SAVED_NAME = "shuruisutilities_model_overrides";

    private static Store store;

    private ModelState() {}

    /** Bind to the running server's SavedData and register the shard sync. Idempotent; call at server start. */
    public static void bind(MinecraftServer server)
    {
        if (server == null)
            return;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        store = overworld.getDataStorage().computeIfAbsent(Store::load, Store::new, SAVED_NAME);
        ShardStateSync.register(SHARD_KEY, ModelState::readForShard, ModelState::applyFromShard,
                () -> store == null ? 0L : store.shardDirtyVersion());
    }

    /** Detach on server stop so a fresh server does not read a stale in-memory store. */
    public static void unbind()
    {
        store = null;
    }

    /** The model override id for a player, or null. */
    public static String get(UUID id)
    {
        return store == null || id == null ? null : store.overrides.get(id);
    }

    /** Set (or replace) a player's model override, persist it and push it to every client. */
    public static void set(MinecraftServer server, UUID id, String modelId)
    {
        if (server == null || store == null || id == null || modelId == null || modelId.isEmpty())
            return;
        store.set(id, modelId);
        broadcast(server, PacketModelSync.single(id, modelId));
    }

    /** Clear a player's model override (a stamped tombstone), persist it and tell every client. */
    public static boolean clear(MinecraftServer server, UUID id)
    {
        if (server == null || store == null || id == null)
            return false;
        if (!store.clear(id))
            return false;
        broadcast(server, PacketModelSync.clear(id));
        return true;
    }

    /** Send the whole current map to one player (login). */
    public static void syncTo(ServerPlayer viewer)
    {
        if (viewer == null || store == null)
            return;
        NetworkUtils.sendTo(PacketModelSync.snapshot(new HashMap<>(store.overrides)), viewer);
    }

    private static void broadcast(MinecraftServer server, PacketModelSync p)
    {
        for (ServerPlayer sp : server.getPlayerList().getPlayers())
            NetworkUtils.sendTo(p, sp);
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

    /**
     * The persisted, per-player merge store. Simple class name is the folder-safe id inside the SavedData file (the
     * SavedData is named {@code shuruisutilities_model_overrides}); do not rename the fields' keys (orphans live data).
     */
    public static final class Store extends SavedData
    {
        final Map<UUID, String> overrides = new HashMap<>();
        // Stamp of the last change per player, kept for present AND removed players so the merge can carry a removal.
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

        void set(UUID id, String modelId)
        {
            overrides.put(id, modelId);
            stampedAt.put(id, dbNow());
            setDirty();
        }

        boolean clear(UUID id)
        {
            boolean had = overrides.remove(id) != null;
            // Stamp the removal even if there was no local entry, so a tombstone can override a stale remote set.
            stampedAt.put(id, dbNow());
            setDirty();
            return had;
        }

        static Store load(CompoundTag tag)
        {
            Store s = new Store();
            CompoundTag m = tag.getCompound("overrides");
            for (String key : m.getAllKeys())
                putUuid(s.overrides, key, m.getString(key));
            CompoundTag stamps = tag.getCompound("stamps");
            for (String key : stamps.getAllKeys())
                putUuidLong(s.stampedAt, key, stamps.getLong(key));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag)
        {
            CompoundTag m = new CompoundTag();
            for (Map.Entry<UUID, String> e : overrides.entrySet())
                m.putString(e.getKey().toString(), e.getValue());
            tag.put("overrides", m);
            CompoundTag stamps = new CompoundTag();
            for (Map.Entry<UUID, Long> e : stampedAt.entrySet())
                stamps.putLong(e.getKey().toString(), e.getValue());
            tag.put("stamps", stamps);
            return tag;
        }

        /** Adopt a sibling server's per-player state only where its stamp is newer, and re-sync those on this shard. */
        void mergeInto(CompoundTag tag)
        {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            CompoundTag inOverrides = tag.getCompound("overrides");
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
                long local = stampedAt.getOrDefault(id, 0L);
                if (incoming <= local)
                    continue;
                String incomingModel = inOverrides.contains(key) ? inOverrides.getString(key) : null;
                String localModel = overrides.get(id);
                boolean differs = incomingModel == null ? localModel != null : !incomingModel.equals(localModel);
                if (differs)
                {
                    if (incomingModel == null)
                        overrides.remove(id);
                    else
                        overrides.put(id, incomingModel);
                    if (server != null)
                    {
                        PacketModelSync p = incomingModel == null
                                ? PacketModelSync.clear(id) : PacketModelSync.single(id, incomingModel);
                        for (ServerPlayer sp : server.getPlayerList().getPlayers())
                            NetworkUtils.sendTo(p, sp);
                    }
                }
                stampedAt.put(id, incoming);
                changed = true;
            }
            if (changed)
                setDirty();
        }

        /** A stamp on the one clock the shards share, so cross-shard merge ordering agrees across hosts. */
        private static long dbNow()
        {
            return System.currentTimeMillis() + ShardStateSync.dbClockOffsetMillis();
        }

        private static void putUuid(Map<UUID, String> map, String key, String value)
        {
            try
            {
                map.put(UUID.fromString(key), value);
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }

        private static void putUuidLong(Map<UUID, Long> map, String key, long value)
        {
            try
            {
                map.put(UUID.fromString(key), value);
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }
    }
}
