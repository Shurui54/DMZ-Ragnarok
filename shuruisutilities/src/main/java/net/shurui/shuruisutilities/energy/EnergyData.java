package net.shurui.shuruisutilities.energy;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data holding every player's role energy, following the SavedData idiom used across the
 * suite ({@code GuildRaidSpoils}, {@code TournamentData}): persisted under a fixed name so it survives restarts.
 *
 * <p>Shape: player uuid -&gt; ({@link EnergyKind} -&gt; current value). All three kinds are stored per player rather
 * than only the one their current role grants, so that losing and regaining a title does not silently zero a bar the
 * player had spent up to, and so a player who somehow holds two roles keeps both. Which bar is VISIBLE and spendable
 * is a separate question answered by {@link EnergyManager#activeKind}.
 *
 * <p>Values are floats because two of the abilities drain the bar continuously rather than in whole points; storing
 * ints here would round every partial drain away and make a slow drain free.
 *
 * <p>Rows are keyed by uuid, not by online player, so an offline player's bar persists and can be read or cleared by
 * an admin command without them being logged in.
 */
public final class EnergyData extends SavedData
{
    // Cross-shard state sync change signal: a monotonic counter bumped on every mutation. NEVER reset (unlike
    // SavedData's own dirty flag, which the autosave clears), so ShardStateSync can skip rebuilding this store's NBT
    // while it has not moved and can never miss a change. See ShardStateSync.register.
    private long shardDirtyVersion;

    @Override
    public void setDirty()
    {
        shardDirtyVersion++;
        super.setDirty();
    }

    /** Monotonic mutation counter for the cross-shard state sync; see the field note. */
    public long shardDirtyVersion()
    {
        return shardDirtyVersion;
    }

    private static final String NAME = "shuruisutilities_role_energy";

    private final Map<UUID, EnumMap<EnergyKind, Float>> bars = new HashMap<>();

    /** The shared pool every role spends from: player uuid -> current value. */
    private final Map<UUID, Float> pool = new HashMap<>();

    // Last time each player's energy row (bars or pool) was written, kept so the cross-server merge is last-write-wins
    // per player. Regeneration and every spend both go through set()/setPool(), and regeneration only runs for ONLINE
    // players (EnergySync iterates the player list), so the server a player is actively on is always the one holding
    // the newest stamp. That is what lets the value rise over time where the player is, while a stale higher value on
    // a server they have left loses because its stamp is older, so a hop can never raise the bar.
    private final Map<UUID, Long> stampedAt = new HashMap<>();

    public static EnergyData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(EnergyData::load, EnergyData::new, NAME);
    }

    /**
     * Current value of one bar, or {@link EnergyManager#MAX} when this player has no row for it yet.
     *
     * <p>An absent row means FULL, not empty. A role is granted by a title or a race, and the first thing a new
     * holder does is try the ability; starting them at zero would make the grant look broken until the bar filled.
     * Spending is what creates the row, so the default only ever applies before the first spend.
     */
    public float get(UUID player, EnergyKind kind)
    {
        if (player == null || kind == null)
            return EnergyManager.MAX;
        EnumMap<EnergyKind, Float> row = bars.get(player);
        if (row == null)
            return EnergyManager.MAX;
        Float v = row.get(kind);
        return v == null ? EnergyManager.MAX : v;
    }

    /** Set one bar, clamped to 0..{@link EnergyManager#MAX}. Marks dirty only when the stored value really moves. */
    public void set(UUID player, EnergyKind kind, float value)
    {
        if (player == null || kind == null)
            return;
        float clamped = Math.max(0.0f, Math.min(EnergyManager.MAX, value));
        EnumMap<EnergyKind, Float> row = bars.computeIfAbsent(player, p -> new EnumMap<>(EnergyKind.class));
        Float previous = row.get(kind);
        if (previous != null && previous == clamped)
            return;
        row.put(kind, clamped);
        // Stamp every real write, so the cross-server merge can tell this server's fresher value from a stale one.
        stampedAt.put(player, System.currentTimeMillis());
        setDirty();
    }


    /**
     * The player's single shared energy pool.
     *
     * <p>ONE VALUE PER PLAYER, not one per kind. Every role draws on the same hundred points, so a shadow dragon who
     * also holds a title has one bar rather than two - which matters because the HUD only ever shows one track, and
     * a second pool would drain where nobody could see it.
     *
     * <p>An absent row means FULL. A role is granted by a title or a race and the first thing a new holder does is
     * try the ability; starting them at zero would make the grant look broken until it filled. Spending is what
     * creates the row.
     */
    public float getPool(UUID player)
    {
        if (player == null)
            return EnergyManager.MAX;
        Float v = pool.get(player);
        return v == null ? EnergyManager.MAX : v;
    }

    /** Set the shared pool, clamped to 0..{@link EnergyManager#MAX}. */
    public void setPool(UUID player, float value)
    {
        if (player == null)
            return;
        float clamped = Math.max(0.0f, Math.min(EnergyManager.MAX, value));
        Float previous = pool.get(player);
        if (previous != null && previous == clamped)
            return;
        pool.put(player, clamped);
        // Stamp every real write, so the cross-server merge can tell this server's fresher value from a stale one.
        stampedAt.put(player, System.currentTimeMillis());
        setDirty();
    }

    /** Drop every bar for one player (an admin reset). */
    public void clear(UUID player)
    {
        boolean changed = player != null && bars.remove(player) != null;
        changed |= player != null && pool.remove(player) != null;
        if (changed)
        {
            // Retain a stamp with no row (a tombstone) so the merge on another server carries the reset across a hop
            // rather than resurrecting the old bars from a sibling that still remembers them.
            stampedAt.put(player, System.currentTimeMillis());
            setDirty();
        }
    }

    /**
     * Cross-server state sync write path: adopt a sibling server's energy row for a player only when it is NEWER than
     * ours, per player, rather than replacing the whole table or comparing magnitudes. Last-write-wins per player is
     * the rule that both refuses a hop-refill and lets the value rise over time: regeneration and every spend go
     * through set()/setPool(), and regeneration only runs for ONLINE players, so the server the player is actively on
     * always holds the newest stamp. The drain the player just took there is therefore the most recent fact about them
     * and wins across a hop, while a stale higher value sitting on a server they left is older and loses, so a hop can
     * never raise the bar. A per-player merge keeps every OTHER player's row intact, which a whole-table replace could
     * not promise.
     *
     * <p>A magnitude merge (keep the lower value) was rejected: with two servers up it pins the bar near its minimum,
     * because every regeneration step on the active server is undone the next time a sibling's older, lower row is
     * seen. That reads as a broken energy system, which is worse than the hop-refill it would close.
     *
     * <p>The whole row (bars and pool together) is adopted atomically when it is newer, because a coherent snapshot
     * comes from a single active server; an absent side is a cleared side (an admin reset travelling in).
     */
    public void mergeInto(CompoundTag tag)
    {
        boolean changed = false;

        // Rebuild the incoming per-player snapshot so a whole row can be adopted atomically when it wins.
        Map<UUID, EnumMap<EnergyKind, Float>> incomingBars = new HashMap<>();
        for (Tag t : tag.getList("bars", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            EnergyKind kind = EnergyKind.byId(e.getString("kind"));
            // An unknown kind is a row from a newer build; drop it rather than guessing, as load() does.
            if (kind == null)
                continue;
            incomingBars.computeIfAbsent(e.getUUID("player"), p -> new EnumMap<>(EnergyKind.class))
                    .put(kind, e.getFloat("value"));
        }
        Map<UUID, Float> incomingPool = new HashMap<>();
        for (Tag t : tag.getList("pool", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            incomingPool.put(e.getUUID("player"), e.getFloat("value"));
        }

        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys())
        {
            UUID player;
            try
            {
                player = UUID.fromString(key);
            }
            catch (IllegalArgumentException ignored)
            {
                continue;
            }
            long incomingStamp = stamps.getLong(key);
            long localStamp = stampedAt.getOrDefault(player, 0L);
            if (incomingStamp <= localStamp)
                continue;

            EnumMap<EnergyKind, Float> inBars = incomingBars.get(player);
            if (inBars == null)
                bars.remove(player);
            else
                bars.put(player, new EnumMap<>(inBars));

            Float inPool = incomingPool.get(player);
            if (inPool == null)
                pool.remove(player);
            else
                pool.put(player, inPool);

            stampedAt.put(player, incomingStamp);
            changed = true;
        }
        if (changed)
            setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag rows = new ListTag();
        bars.forEach((uuid, row) -> row.forEach((kind, value) ->
        {
            CompoundTag e = new CompoundTag();
            e.putUUID("player", uuid);
            // Stable string id rather than the ordinal, so reordering the enum cannot silently reassign saved bars.
            e.putString("kind", kind.id());
            e.putFloat("value", value);
            rows.add(e);
        }));
        tag.put("bars", rows);

        ListTag pools = new ListTag();
        pool.forEach((uuid, value) ->
        {
            CompoundTag e = new CompoundTag();
            e.putUUID("player", uuid);
            e.putFloat("value", value);
            pools.add(e);
        });
        tag.put("pool", pools);

        CompoundTag stamps = new CompoundTag();
        for (Map.Entry<UUID, Long> e : stampedAt.entrySet())
            stamps.putLong(e.getKey().toString(), e.getValue());
        tag.put("stamps", stamps);
        return tag;
    }

    public static EnergyData load(CompoundTag tag)
    {
        EnergyData data = new EnergyData();
        for (Tag t : tag.getList("bars", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            EnergyKind kind = EnergyKind.byId(e.getString("kind"));
            // An unknown kind is a row written by a newer build; drop it rather than guessing, so a downgrade cannot
            // land somebody else's bar in the wrong slot.
            if (kind == null)
                continue;
            data.bars.computeIfAbsent(e.getUUID("player"), p -> new EnumMap<>(EnergyKind.class))
                    .put(kind, e.getFloat("value"));
        }
        for (Tag t : tag.getList("pool", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            data.pool.put(e.getUUID("player"), e.getFloat("value"));
        }
        // Stamps were added after the first release; an older .dat has none, so those players load with no stamp and
        // any incoming sync state wins for them once, which is the safe direction on a one-time upgrade.
        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys())
        {
            try
            {
                data.stampedAt.put(UUID.fromString(key), stamps.getLong(key));
            }
            catch (IllegalArgumentException ignored)
            {
                // skip malformed uuid keys rather than failing the whole load
            }
        }
        return data;
    }
}
