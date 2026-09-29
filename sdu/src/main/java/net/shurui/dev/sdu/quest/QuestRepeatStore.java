package net.shurui.dev.sdu.quest;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When each player last completed a repeatable quest, and when its cooldown was last spent, keyed
 * {@code "<uuid>|<questKey>"}. DMZ tracks only a per-quest SUCCESS status with no timestamp, and an in-memory
 * map would reset on relog (wrong for daily/weekly cooldowns), so this persists as SavedData on the OVERWORLD
 * DimensionDataStorage.
 *
 * <h2>Two timestamps, because deletion cannot cross a shard</h2>
 *
 * <p>This used to hold ONE timestamp per pair and express "cooldown spent" by DELETING the entry. That is
 * unrepresentable in {@link #mergeInto}, which is the cross-server write path ({@code sdu:quest_repeats} in
 * {@code ShuruisUtilities.registerCrossServerPlayerState}): the merge walks the keys present in the incoming
 * snapshot and keeps the later value, so it can add and it can advance, but it can never remove. A peer shard
 * that had not seen the deletion therefore put the entry straight back, with its ORIGINAL, now ancient,
 * timestamp, within about five seconds ({@code ShardStateSync} interval).
 *
 * <p>The consequence was not a stale cooldown, it was quest completions evaporating. With the stamp
 * resurrected, the next completion of that quest saw "already stamped", skipped recording a fresh time, and
 * compared the ancient time against the interval, which had of course elapsed long ago. The handler then reset
 * the quest about a second after the player turned it in, so the completion did not register and the NPC
 * offered the quest again. Occasional, because it needed a peer holding the old stamp to publish, and only ever
 * on repeatable quests, because everything else exits before this store is touched.
 *
 * <p>So nothing is ever deleted now. {@code completedAt} records a completion, {@code resetAt} records the
 * cooldown being spent, and a completion COUNTS only while it is newer than the reset that followed it
 * ({@link #hasLiveCompletion}). Both maps merge by max, which is order-independent and idempotent, so a
 * resurrected old value can no longer win: an ancient {@code completedAt} arriving from a peer is dominated by
 * the local {@code resetAt} and correctly reads as "spent, awaiting a new completion".
 *
 * <p>Files written before this change carry only {@code completedAt}. They load with an empty {@code resetAt},
 * which makes every existing stamp live, exactly the old meaning, so no cooldown is lost on the upgrade.
 */
public final class QuestRepeatStore extends SavedData {

    // Cross-shard state sync change signal: a monotonic counter bumped on every mutation. NEVER reset (unlike
    // SavedData's own dirty flag, which the autosave clears), so ShardStateSync can skip rebuilding this store's NBT
    // while it has not moved and can never miss a change. See ShardStateSync.register.
    private long shardDirtyVersion;

    @Override
    public void setDirty() {
        shardDirtyVersion++;
        super.setDirty();
    }

    /** Monotonic mutation counter for the cross-shard state sync; see the field note. */
    public long shardDirtyVersion() {
        return shardDirtyVersion;
    }

    // Pinned literal: the on-disk data/<NAME>.dat filename. Deriving it from MODID would orphan saved quest
    // cooldowns if MODID is ever renamed.
    public static final String NAME = "sdu_quest_repeats";

    // "<uuid>|<questKey>" -> epoch millis at completion.
    private final Map<String, Long> completedAt = new HashMap<>();

    // "<uuid>|<questKey>" -> epoch millis at which that completion's cooldown was spent (the quest re-unlocked).
    private final Map<String, Long> resetAt = new HashMap<>();

    public QuestRepeatStore() {
    }

    public static QuestRepeatStore get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(QuestRepeatStore::load, QuestRepeatStore::new, NAME);
    }

    private static String composite(UUID player, String questKey) {
        return player.toString() + "|" + questKey;
    }

    // 0 if no record.
    public long lastCompleted(UUID player, String questKey) {
        Long v = completedAt.get(composite(player, questKey));
        return v == null ? 0L : v;
    }

    // 0 if this pair's cooldown has never been spent.
    public long lastReset(UUID player, String questKey) {
        Long v = resetAt.get(composite(player, questKey));
        return v == null ? 0L : v;
    }

    /**
     * Whether a recorded completion is still awaiting its cooldown. False both when nothing was ever recorded
     * and when the recorded completion has already been spent by a reset, which are the two cases the handler
     * must treat identically: in either, the next SUCCESS it sees is a NEW completion and must be stamped now.
     */
    public boolean hasLiveCompletion(UUID player, String questKey) {
        String key = composite(player, questKey);
        Long completed = completedAt.get(key);
        if (completed == null) {
            return false;
        }
        Long reset = resetAt.get(key);
        return reset == null || completed > reset;
    }

    // overwrites any prior stamp.
    public void recordCompletion(UUID player, String questKey, long epochMillis) {
        completedAt.put(composite(player, questKey), epochMillis);
        setDirty();
    }

    /**
     * Called when the quest re-unlocks. Replaces the old {@code clearCompletion}: it marks the completion spent
     * instead of deleting it, so the fact survives a merge from a peer that still holds the completion. Never
     * moves backwards, so a late-arriving older reset cannot re-open a cooldown.
     */
    public void recordReset(UUID player, String questKey, long epochMillis) {
        String key = composite(player, questKey);
        Long current = resetAt.get(key);
        if (current == null || epochMillis > current) {
            resetAt.put(key, epochMillis);
            setDirty();
        }
    }

    /**
     * Cross-server state sync write path: keep the LATER value per (player, quest) in BOTH maps rather than
     * replacing the table. Most recent wins is the truth for a cooldown, so the wait carries across a hop (no
     * farming a daily by bouncing servers) and no other player's record is ever dropped.
     *
     * <p>Max-merging is the whole reason the reset is a timestamp rather than a deletion. See the class note:
     * this method structurally cannot remove a key, so any state that needs to travel has to be expressible as
     * a value that only goes up.
     */
    public void mergeInto(CompoundTag tag) {
        boolean changed = mergeMap(completedAt, tag.getCompound("completedAt"));
        changed |= mergeMap(resetAt, tag.getCompound("resetAt"));
        if (changed) {
            setDirty();
        }
    }

    private static boolean mergeMap(Map<String, Long> into, CompoundTag incoming) {
        boolean changed = false;
        for (String key : incoming.getAllKeys()) {
            long value = incoming.getLong(key);
            Long current = into.get(key);
            if (current == null || value > current) {
                into.put(key, value);
                changed = true;
            }
        }
        return changed;
    }

    public static QuestRepeatStore load(CompoundTag tag) {
        QuestRepeatStore s = new QuestRepeatStore();
        readMap(s.completedAt, tag.getCompound("completedAt"));
        // Absent in files written before the two-timestamp change: every old stamp then reads as live, which is
        // what it meant when it was written, so upgrading loses nobody's cooldown.
        readMap(s.resetAt, tag.getCompound("resetAt"));
        return s;
    }

    private static void readMap(Map<String, Long> into, CompoundTag from) {
        for (String key : from.getAllKeys()) {
            into.put(key, from.getLong(key));
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("completedAt", writeMap(completedAt));
        tag.put("resetAt", writeMap(resetAt));
        return tag;
    }

    private static CompoundTag writeMap(Map<String, Long> from) {
        CompoundTag map = new CompoundTag();
        for (Map.Entry<String, Long> e : from.entrySet()) {
            map.putLong(e.getKey(), e.getValue());
        }
        return map;
    }
}
