package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld attached SavedData recording which DragonMineZ ball sets are DORMANT (turned to stone by a wish) and
 * when each wakes. Its own store, deliberately NOT a field on DMZ's {@code DragonBallSavedData}: renaming or
 * extending that class risks its persisted data, and dormancy is our concept, not DMZ's. Modelled on
 * {@link net.shurui.shuruisutilities.corrupted.ShadowDragonStorage} and {@link
 * net.shurui.shuruisutilities.grave.GraveStorage} (overworld data storage, one instance per world save).
 *
 * <h2>Two clocks, on purpose</h2>
 *
 * <p>Each dormant set carries a {@link Clock} telling the sweep WHICH deadline to compare. This is a deliberate
 * asymmetry the owner asked for:
 * <ul>
 *   <li>{@link Clock#GAME}: the deadline is a value of {@code level.getGameTime()} (the monotonic tick counter,
 *       which advances every server tick and is frozen while the server is down). Used by earth, namek, blackstar
 *       and cerulean, so their one Minecraft week of dormancy does NOT count server downtime. This mirrors the
 *       grave system, which stamps and compares {@code getGameTime()} for exactly the same reason. We use
 *       {@code getGameTime}, never {@code getDayTime}: DMZ's {@code DragonWishEntity.onDespawn} rewrites the day
 *       time with {@code setDayTime}, and an operator can freeze the daylight cycle, either of which would make a
 *       day-time deadline meaningless.</li>
 *   <li>{@link Clock#WALL}: the deadline is a value of {@code System.currentTimeMillis()} (wall clock). Used by
 *       super only, so its one real life day of dormancy DOES count downtime and is measured against real time.</li>
 * </ul>
 */
public final class BallDormancyStorage extends SavedData
{
    private static final String NAME = "shuruisutilities_ball_dormancy";

    /** Which clock a set's deadline is measured on. See the class note for why the two differ. */
    public enum Clock
    {
        GAME, WALL
    }

    /** One dormant set's record: the clock its deadline is read on, and the deadline value on that clock. */
    public record Dormant(Clock clock, long deadline) {}

    // setId -> its dormancy record. LinkedHashMap for stable iteration order in logs and the radar strip.
    private final Map<String, Dormant> dormant = new LinkedHashMap<>();

    public static BallDormancyStorage get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(BallDormancyStorage::load, BallDormancyStorage::new, NAME);
    }

    private static BallDormancyStorage load(CompoundTag tag)
    {
        BallDormancyStorage s = new BallDormancyStorage();
        ListTag list = tag.getList("dormant", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            String set = e.getString("set");
            if (set.isEmpty())
                continue;
            // A pre-existing or corrupt clock name reads as GAME, the common case, rather than throwing.
            Clock clock = "WALL".equals(e.getString("clock")) ? Clock.WALL : Clock.GAME;
            s.dormant.put(set, new Dormant(clock, e.getLong("deadline")));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (Map.Entry<String, Dormant> e : dormant.entrySet())
        {
            CompoundTag t = new CompoundTag();
            t.putString("set", e.getKey());
            t.putString("clock", e.getValue().clock().name());
            t.putLong("deadline", e.getValue().deadline());
            list.add(t);
        }
        tag.put("dormant", list);
        return tag;
    }

    /** The record for a set, or null when the set is not dormant. */
    public Dormant get(String setId)
    {
        return setId == null ? null : dormant.get(setId);
    }

    public boolean isDormant(String setId)
    {
        return setId != null && dormant.containsKey(setId);
    }

    /** Every dormant set id, a copy for safe iteration while the caller may clear entries. */
    public List<String> dormantSets()
    {
        return new ArrayList<>(dormant.keySet());
    }

    /** Mark a set dormant with the given clock and deadline. Overwrites any prior record for that set. */
    public void put(String setId, Clock clock, long deadline)
    {
        if (setId == null || setId.isEmpty())
            return;
        dormant.put(setId, new Dormant(clock, deadline));
        setDirty();
    }

    /** Clear a set's dormancy. Returns true when a record was actually removed. */
    public boolean clear(String setId)
    {
        if (setId == null)
            return false;
        if (dormant.remove(setId) != null)
        {
            setDirty();
            return true;
        }
        return false;
    }
}
