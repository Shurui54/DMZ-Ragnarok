package net.shurui.shuruisutilities.guilds.raid;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data holding the guild-planet-raid lockouts. Mirrors the SavedData idiom used across
 * the suite ({@link net.shurui.shuruisutilities.space.GeneratedPlanetClaims},
 * {@link net.shurui.shuruisutilities.corrupted.ShadowDragonStorage}): persisted with the overworld data storage
 * under a fixed name so it survives restarts.
 *
 * <p>Shape: a two-level map, attacker guild id -&gt; defender guild id -&gt; expiry millis. A lockout entry means
 * "this attacker may not raid this defender again until this wall-clock time". The value is a {@link
 * System#currentTimeMillis()} stamp, the same persistent-cooldown idiom already used by Kit, TempMute and Jail:
 * we store the absolute expiry and compare it at use time, so no scheduler is needed and a lockout simply lapses
 * once the clock passes it.
 *
 * <p>A FAILED raid sets a failure lockout; a SUCCESSFUL raid sets one when the success-lockout config is enabled
 * (it now defaults to three days, the same as the failure lockout, so by default winners are locked out too).
 * Both durations are config values; setting the success value to zero disables the win lockout.
 */
public final class GuildRaidLockouts extends SavedData
{
    private static final String NAME = "shuruisutilities_guild_raid_lockouts";

    // attacker guild id -> (defender guild id -> expiry millis)
    private final Map<String, Map<String, Long>> lockouts = new HashMap<>();

    public static GuildRaidLockouts get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(GuildRaidLockouts::load, GuildRaidLockouts::new, NAME);
    }

    private static GuildRaidLockouts load(CompoundTag tag)
    {
        GuildRaidLockouts s = new GuildRaidLockouts();
        ListTag entries = tag.getList("lockouts", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++)
        {
            CompoundTag e = entries.getCompound(i);
            String attacker = e.getString("attacker");
            String defender = e.getString("defender");
            long expiry = e.getLong("expiry");
            s.lockouts.computeIfAbsent(attacker, k -> new HashMap<>()).put(defender, expiry);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag entries = new ListTag();
        for (Map.Entry<String, Map<String, Long>> byAttacker : lockouts.entrySet())
        {
            for (Map.Entry<String, Long> byDefender : byAttacker.getValue().entrySet())
            {
                CompoundTag e = new CompoundTag();
                e.putString("attacker", byAttacker.getKey());
                e.putString("defender", byDefender.getKey());
                e.putLong("expiry", byDefender.getValue());
                entries.add(e);
            }
        }
        tag.put("lockouts", entries);
        return tag;
    }

    /**
     * Milliseconds of lockout remaining for this attacker-against-defender pair, or 0 if none is in force. Lapsed
     * entries are pruned lazily on read so the store does not accumulate dead lockouts.
     */
    public long remainingMillis(String attackerGuildId, String defenderGuildId)
    {
        Map<String, Long> byDefender = lockouts.get(attackerGuildId);
        if (byDefender == null)
        {
            return 0L;
        }
        Long expiry = byDefender.get(defenderGuildId);
        if (expiry == null)
        {
            return 0L;
        }
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0L)
        {
            // lapsed: prune so the map stays tidy, and drop the attacker bucket if it is now empty.
            byDefender.remove(defenderGuildId);
            if (byDefender.isEmpty())
            {
                lockouts.remove(attackerGuildId);
            }
            setDirty();
            return 0L;
        }
        return remaining;
    }

    /** True if this attacker is currently locked out of raiding this defender. */
    public boolean isLockedOut(String attackerGuildId, String defenderGuildId)
    {
        return remainingMillis(attackerGuildId, defenderGuildId) > 0L;
    }

    // cross-server state sync write path: keep the LATER expiry per attacker-against-defender pair rather than
    // replacing the whole table. Guilds are network-wide, so a raid lockout must be too, or a guild dodges its
    // cooldown by moving to a server that never saw the raid. Taking the max can only hold a lockout in force
    // longer, never end one early, and one pair's lockout is never dropped by an edit to another. A stale past
    // expiry that arrives is harmless: remainingMillis prunes it lazily on the next read.
    public void mergeInto(CompoundTag tag)
    {
        boolean changed = false;
        ListTag entries = tag.getList("lockouts", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++)
        {
            CompoundTag e = entries.getCompound(i);
            String attacker = e.getString("attacker");
            String defender = e.getString("defender");
            long incoming = e.getLong("expiry");
            Map<String, Long> byDefender = lockouts.computeIfAbsent(attacker, k -> new HashMap<>());
            Long current = byDefender.get(defender);
            if (current == null || incoming > current)
            {
                byDefender.put(defender, incoming);
                changed = true;
            }
        }
        if (changed)
        {
            setDirty();
        }
    }

    /**
     * Arm a lockout for this attacker against this defender, expiring {@code durationMillis} from now. A
     * non-positive duration clears any existing lockout instead (used so a disabled success lockout is a no-op).
     */
    public void set(String attackerGuildId, String defenderGuildId, long durationMillis)
    {
        if (durationMillis <= 0L)
        {
            clear(attackerGuildId, defenderGuildId);
            return;
        }
        lockouts.computeIfAbsent(attackerGuildId, k -> new HashMap<>())
                .put(defenderGuildId, System.currentTimeMillis() + durationMillis);
        setDirty();
    }

    /** Drop a single attacker-against-defender lockout. No-op if none is set. */
    public void clear(String attackerGuildId, String defenderGuildId)
    {
        Map<String, Long> byDefender = lockouts.get(attackerGuildId);
        if (byDefender != null && byDefender.remove(defenderGuildId) != null)
        {
            if (byDefender.isEmpty())
            {
                lockouts.remove(attackerGuildId);
            }
            setDirty();
        }
    }

    /**
     * Drop every lockout that names a guild id on either side (used when a guild disbands, so a dead guild id
     * never lingers as an attacker or a defender). No-op if the id appears nowhere.
     */
    public void forgetGuild(String guildId)
    {
        boolean changed = lockouts.remove(guildId) != null;
        for (Map<String, Long> byDefender : lockouts.values())
        {
            changed |= byDefender.remove(guildId) != null;
        }
        // drop any attacker buckets left empty by the removals above
        changed |= lockouts.values().removeIf(Map::isEmpty);
        if (changed)
        {
            setDirty();
        }
    }
}
