package net.shurui.shuruisutilities.shrine;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

/**
 * Per-player shrine state in the player's PlayerPersisted NBT so it survives relog + death (Forge copies that
 * sub-tag onto the respawn clone); mirrors jail.JailData. DMZ's BonusStats persist with NO expiry, so SU owns
 * the timer: record which buffs are active and when each expires, and {@code ShrineManager} (Ragnarok Key)
 * tick/login applies or removes the actual DMZ bonus from these records.
 *
 * <p>Multi-slot: every buff keyed by shrine name so many shrines coexist + stack. Two maps:
 * <ul>
 *   <li>STAT: {@code shrineName -> {statKey, percent, expiry}}, each a DMZ BonusStats multiplier under source
 *       {@code su_shrine:<name>} that must be purged on expiry;</li>
 *   <li>TP: {@code shrineName -> {percent, expiry}}, personal TP-gain multipliers summed by the shared
 *       {@code TpBoostState} listener (Ragnarok Key).</li>
 * </ul>
 * Re-activating the SAME named shrine upserts its entry rather than stacking a duplicate.
 *
 * <p>NBT under su_shrine: {@code stats:[{name,statKey,pct,until}], tps:[{name,pct,until}]}. Legacy single-slot
 * keys (statKey/statPercent/statUntil, tpPercent/tpUntil) are migrated on first read into one "legacy" entry each.
 */
public final class ShrinePlayerData
{
    private ShrinePlayerData() {}

    private static final String TAG = "su_shrine";

    // multi-slot list keys
    private static final String STATS_LIST = "stats";
    private static final String TPS_LIST = "tps";

    // per-entry keys
    private static final String E_NAME = "name";
    private static final String E_STAT_KEY = "statKey";
    private static final String E_PCT = "pct";
    private static final String E_UNTIL = "until";

    // legacy single-slot keys (pre-fix builds)
    private static final String OLD_STAT_KEY = "statKey";
    private static final String OLD_STAT_PCT = "statPercent";
    private static final String OLD_STAT_UNTIL = "statUntil";
    private static final String OLD_TP_PCT = "tpPercent";
    private static final String OLD_TP_UNTIL = "tpUntil";
    private static final String LEGACY_NAME = "legacy";

    // active STAT buff: which stat, percent boost, expiry (epoch millis)
    public static final class StatBuff
    {
        public final String statKey;
        public final double percent;
        public final long expiry;

        public StatBuff(String statKey, double percent, long expiry)
        {
            this.statKey = statKey;
            this.percent = percent;
            this.expiry = expiry;
        }
    }

    // active TP buff: percent boost, expiry (epoch millis)
    public static final class TpBuff
    {
        public final double percent;
        public final long expiry;

        public TpBuff(double percent, long expiry)
        {
            this.percent = percent;
            this.expiry = expiry;
        }
    }

    // the PlayerPersisted compound, created + re-attached if absent
    private static CompoundTag persisted(Player player)
    {
        CompoundTag data = player.getPersistentData();
        CompoundTag pt = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG))
            data.put(Player.PERSISTED_NBT_TAG, pt);
        return pt;
    }

    private static CompoundTag tag(Player player)
    {
        return persisted(player).getCompound(TAG);
    }

    // write back, or drop the whole su_shrine tag if empty
    private static void store(Player player, CompoundTag t)
    {
        if (t.isEmpty())
            persisted(player).remove(TAG);
        else
            persisted(player).put(TAG, t);
    }

    // active STAT buffs by shrine name (migrates legacy data on read); never null
    public static Map<String, StatBuff> getStats(Player player)
    {
        CompoundTag t = tag(player).copy();
        boolean dirty = migrate(t);
        Map<String, StatBuff> out = new LinkedHashMap<>();
        ListTag list = t.getList(STATS_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            String name = e.getString(E_NAME);
            if (name.isEmpty())
                continue;
            out.put(name, new StatBuff(e.getString(E_STAT_KEY), e.getDouble(E_PCT), e.getLong(E_UNTIL)));
        }
        if (dirty)
            store(player, t);
        return out;
    }

    // upsert the STAT buff for one named shrine
    public static void putStat(Player player, String shrineName, String statKey, double percent, long expiryMs)
    {
        CompoundTag t = tag(player).copy();
        migrate(t);
        ListTag list = t.getList(STATS_LIST, Tag.TAG_COMPOUND);
        removeByName(list, shrineName);
        CompoundTag e = new CompoundTag();
        e.putString(E_NAME, shrineName);
        e.putString(E_STAT_KEY, statKey);
        e.putDouble(E_PCT, percent);
        e.putLong(E_UNTIL, expiryMs);
        list.add(e);
        t.put(STATS_LIST, list);
        store(player, t);
    }

    // remove one named STAT buff; true if something went
    public static boolean removeStat(Player player, String shrineName)
    {
        CompoundTag t = tag(player).copy();
        migrate(t);
        ListTag list = t.getList(STATS_LIST, Tag.TAG_COMPOUND);
        boolean removed = removeByName(list, shrineName);
        if (list.isEmpty())
            t.remove(STATS_LIST);
        else
            t.put(STATS_LIST, list);
        store(player, t);
        return removed;
    }

    // active TP buffs by shrine name (migrates legacy data on read); never null
    public static Map<String, TpBuff> getTps(Player player)
    {
        CompoundTag t = tag(player).copy();
        boolean dirty = migrate(t);
        Map<String, TpBuff> out = new LinkedHashMap<>();
        ListTag list = t.getList(TPS_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            String name = e.getString(E_NAME);
            if (name.isEmpty())
                continue;
            out.put(name, new TpBuff(e.getDouble(E_PCT), e.getLong(E_UNTIL)));
        }
        if (dirty)
            store(player, t);
        return out;
    }

    // upsert the TP buff for one named shrine
    public static void putTp(Player player, String shrineName, double percent, long expiryMs)
    {
        CompoundTag t = tag(player).copy();
        migrate(t);
        ListTag list = t.getList(TPS_LIST, Tag.TAG_COMPOUND);
        removeByName(list, shrineName);
        CompoundTag e = new CompoundTag();
        e.putString(E_NAME, shrineName);
        e.putDouble(E_PCT, percent);
        e.putLong(E_UNTIL, expiryMs);
        list.add(e);
        t.put(TPS_LIST, list);
        store(player, t);
    }

    // remove one named TP buff; true if something went
    public static boolean removeTp(Player player, String shrineName)
    {
        CompoundTag t = tag(player).copy();
        migrate(t);
        ListTag list = t.getList(TPS_LIST, Tag.TAG_COMPOUND);
        boolean removed = removeByName(list, shrineName);
        if (list.isEmpty())
            t.remove(TPS_LIST);
        else
            t.put(TPS_LIST, list);
        store(player, t);
        return removed;
    }

    private static boolean removeByName(ListTag list, String name)
    {
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--)
        {
            if (name.equals(list.getCompound(i).getString(E_NAME)))
            {
                list.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    // convert legacy single-slot keys into one "legacy" multi-slot entry each, then strip the old keys. Mutates
    // t in place; true if anything changed (caller persists the migration).
    private static boolean migrate(CompoundTag t)
    {
        boolean changed = false;

        if (t.contains(OLD_STAT_UNTIL))
        {
            ListTag list = t.getList(STATS_LIST, Tag.TAG_COMPOUND);
            if (!containsName(list, LEGACY_NAME))
            {
                CompoundTag e = new CompoundTag();
                e.putString(E_NAME, LEGACY_NAME);
                e.putString(E_STAT_KEY, t.getString(OLD_STAT_KEY));
                e.putDouble(E_PCT, t.getDouble(OLD_STAT_PCT));
                e.putLong(E_UNTIL, t.getLong(OLD_STAT_UNTIL));
                list.add(e);
                t.put(STATS_LIST, list);
            }
            t.remove(OLD_STAT_KEY);
            t.remove(OLD_STAT_PCT);
            t.remove(OLD_STAT_UNTIL);
            changed = true;
        }

        if (t.contains(OLD_TP_UNTIL))
        {
            ListTag list = t.getList(TPS_LIST, Tag.TAG_COMPOUND);
            if (!containsName(list, LEGACY_NAME))
            {
                CompoundTag e = new CompoundTag();
                e.putString(E_NAME, LEGACY_NAME);
                e.putDouble(E_PCT, t.getDouble(OLD_TP_PCT));
                e.putLong(E_UNTIL, t.getLong(OLD_TP_UNTIL));
                list.add(e);
                t.put(TPS_LIST, list);
            }
            t.remove(OLD_TP_PCT);
            t.remove(OLD_TP_UNTIL);
            changed = true;
        }

        return changed;
    }

    private static boolean containsName(ListTag list, String name)
    {
        for (int i = 0; i < list.size(); i++)
            if (name.equals(list.getCompound(i).getString(E_NAME)))
                return true;
        return false;
    }
}
