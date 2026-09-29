package net.shurui.dev.sdu.buff;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

// per-player token-granted buffs, shared by the TP-gain and stat-discount tokens. buffs live in
// getPersistentData() so they survive relog. expiry is a wall-clock timestamp, so a relog doesn't extend or
// reset a buff.
//
// Entries DO NOT ADD UP: the strongest one still running is the one that counts, and each keeps its own expiry, so
// a 50% gem used over a running 10% one gives 50% until it ends and then 10% for whatever is left of the other.
// They used to add, which is what let a player wear several at once and buy stats far under the intended floor
// (reported 2026-09-15, ticket 813). A gem cannot be applied over a live one of its category any more, but stacks
// bought before that guard shipped are still sitting in player data, and taking the strongest defuses those too.
public final class TokenBuffStore {

    // each maps to its own NBT list key under the player's persistent data.
    public enum Category {
        TP("sdu_tp_buffs"),
        STAT("sdu_stat_buffs");

        public final String nbtKey;

        Category(String nbtKey) {
            this.nbtKey = nbtKey;
        }
    }

    private static final String KEY_PCT = "pct";
    private static final String KEY_EXP = "exp";

    private TokenBuffStore() {
    }

    // percentFraction is a fraction (0.10 = 10%); expiry = now + durationMillis.
    public static void addBuff(ServerPlayer p, Category cat, double percentFraction, long durationMillis) {
        if (p == null || cat == null) {
            return;
        }
        CompoundTag root = p.getPersistentData();
        ListTag list = root.getList(cat.nbtKey, Tag.TAG_COMPOUND);
        CompoundTag entry = new CompoundTag();
        entry.putDouble(KEY_PCT, percentFraction);
        entry.putLong(KEY_EXP, System.currentTimeMillis() + durationMillis);
        list.add(entry);
        root.put(cat.nbtKey, list);
    }

    /**
     * Every still-running entry, for sending to the client.
     *
     * <p>The entries rather than their sum, because each expires at its own time and a client handed one number
     * would keep showing it after the first token ran out. Prunes as it reads, exactly like
     * {@link #strongestActive}. The client takes the strongest of these, the same rule the server applies.
     */
    public static java.util.List<net.shurui.dev.sdu.network.TokenBuffSyncPacket.Entry> activeEntries(
            Player p, Category cat) {
        java.util.List<net.shurui.dev.sdu.network.TokenBuffSyncPacket.Entry> out = new java.util.ArrayList<>();
        if (p == null || cat == null) {
            return out;
        }
        CompoundTag root = p.getPersistentData();
        if (!root.contains(cat.nbtKey, Tag.TAG_LIST)) {
            return out;
        }
        ListTag list = root.getList(cat.nbtKey, Tag.TAG_COMPOUND);
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long expiry = entry.getLong(KEY_EXP);
            if (expiry > now) {
                out.add(new net.shurui.dev.sdu.network.TokenBuffSyncPacket.Entry(entry.getDouble(KEY_PCT), expiry));
            }
        }
        return out;
    }

    // the largest still-running expiry for a category, or 0 if none is active. Used to gate the single-slot TP gem
    // (only one at a time) and to drive the TP-boost pip's remaining duration. Does NOT prune: read-only, so callers
    // that also need the store tidied still go through strongestActive.
    public static long activeExpiry(Player p, Category cat) {
        if (p == null || cat == null) {
            return 0L;
        }
        CompoundTag root = p.getPersistentData();
        if (!root.contains(cat.nbtKey, Tag.TAG_LIST)) {
            return 0L;
        }
        ListTag list = root.getList(cat.nbtKey, Tag.TAG_COMPOUND);
        long now = System.currentTimeMillis();
        long max = 0L;
        for (int i = 0; i < list.size(); i++) {
            long expiry = list.getCompound(i).getLong(KEY_EXP);
            if (expiry > now && expiry > max) {
                max = expiry;
            }
        }
        return max;
    }

    // milliseconds left on the still-running window for a category, 0 if none is active.
    public static long remainingMillis(Player p, Category cat) {
        long expiry = activeExpiry(p, cat);
        return expiry > 0L ? Math.max(0L, expiry - System.currentTimeMillis()) : 0L;
    }

    // true while any entry in the category is still running. The TP gem refuses a second use while this holds.
    public static boolean hasActive(Player p, Category cat) {
        return activeExpiry(p, cat) > 0L;
    }

    // the strongest still-active fraction, pruning expired entries and writing the pruned list back. 0.0 if none.
    public static double strongestActive(Player p, Category cat) {
        if (p == null || cat == null) {
            return 0.0;
        }
        CompoundTag root = p.getPersistentData();
        if (!root.contains(cat.nbtKey, Tag.TAG_LIST)) {
            return 0.0;
        }
        ListTag list = root.getList(cat.nbtKey, Tag.TAG_COMPOUND);
        long now = System.currentTimeMillis();
        ListTag kept = new ListTag();
        double strongest = 0.0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.getLong(KEY_EXP) <= now) {
                continue; // expired, drop it
            }
            kept.add(entry);
            strongest = Math.max(strongest, entry.getDouble(KEY_PCT));
        }
        // write back the pruned list, or clear the key if nothing remains.
        if (kept.isEmpty()) {
            root.remove(cat.nbtKey);
        } else if (kept.size() != list.size()) {
            root.put(cat.nbtKey, kept);
        }
        return strongest;
    }
}
