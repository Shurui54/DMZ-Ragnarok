package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

/**
 * Overworld-attached saved data for the seven SUPER dragon-ball bodies: the ONE authoritative home for their progress.
 * Mirrors {@link GeneratedPlanetClaims} and {@link PlanetGarrisonData} (persisted under a fixed name on the overworld
 * data storage, keyed by the body's STABLE id {@code susuper:<star>}).
 *
 * <p>A super body's POSITION now lives here rather than being a pure function, because a body may RELOCATE when its ball
 * is lost (point 5). Each body carries:
 * <ul>
 *   <li>{@code pos}: its current world position. Seeded from {@link SuperPlanetPositions#initialPosition} on first init,
 *       replaced on relocation. Synced to clients (see {@link PacketSpaceLayoutSync}) so the drawn body sits exactly
 *       where the server lands.</li>
 *   <li>{@code ballDropped}: an intermediate state. The Destroyer God has been beaten and the Super Dragon Ball BLOCK
 *       placed, but no player has COLLECTED it yet. The body stays stone-grey and LANDABLE in this state so a player who
 *       missed the drop (died, logged off) can fly back for it, and its god is NOT respawned (it is already beaten). This
 *       flag is what fixes the old soft-lock: marking a body claimed on the god's DEATH stranded a ball nobody collected
 *       on a body that could no longer be landed on.</li>
 *   <li>{@code claimed}: whether the body's Super Dragon Ball has been COLLECTED by a player. A claimed body draws as a
 *       dragon ball and is NOT landable; a grey body (neither claimed nor ballDropped, OR ballDropped) is landable.
 *       Synced. A claimed OR ballDropped body reverts to grey (and relocates) only when its ball is DEFINITIVELY gone,
 *       driven by {@link SuperPlanetLifecycle} on a super-ball item despawn, never on mere absence (which could dup a
 *       ball). {@code claimed} and {@code ballDropped} are mutually exclusive: at most one is true.</li>
 * </ul>
 * The god's ALIVENESS is never stored: it is reconciled by scanning the loaded surface on the next visit (see
 * {@link SuperPlanetGod}).
 */
public final class SuperPlanetData extends SavedData
{
    private static final String NAME = "shuruisutilities_super_planets";

    // one body's persisted state.
    static final class BodyState
    {
        Vec3 pos;
        boolean claimed;
        boolean ballDropped;

        BodyState(Vec3 pos, boolean claimed, boolean ballDropped)
        {
            this.pos = pos;
            this.claimed = claimed;
            this.ballDropped = ballDropped;
        }
    }

    // super id -> its state. Populated for all seven ids by ensureInitialized.
    private final Map<String, BodyState> bodies = new HashMap<>();

    public static SuperPlanetData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        SuperPlanetData data =
                overworld.getDataStorage().computeIfAbsent(SuperPlanetData::load, SuperPlanetData::new, NAME);
        data.ensureInitialized();
        return data;
    }

    private static SuperPlanetData load(CompoundTag tag)
    {
        SuperPlanetData s = new SuperPlanetData();
        ListTag list = tag.getList("bodies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag b = list.getCompound(i);
            String id = b.getString("id");
            Vec3 pos = new Vec3(b.getDouble("x"), b.getDouble("y"), b.getDouble("z"));
            boolean claimed = b.getBoolean("claimed");
            boolean ballDropped = b.getBoolean("ballDropped");
            s.bodies.put(id, new BodyState(pos, claimed, ballDropped));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            BodyState st = e.getValue();
            CompoundTag b = new CompoundTag();
            b.putString("id", e.getKey());
            b.putDouble("x", st.pos.x);
            b.putDouble("y", st.pos.y);
            b.putDouble("z", st.pos.z);
            b.putBoolean("claimed", st.claimed);
            b.putBoolean("ballDropped", st.ballDropped);
            list.add(b);
        }
        tag.put("bodies", list);
        return tag;
    }

    // seed the seven bodies at their initial ring positions the first time this data is touched. Idempotent: an already
    // populated store is left alone, and a store missing a star (a future COUNT bump) gains only the missing one.
    private void ensureInitialized()
    {
        boolean dirty = false;
        for (int star = 1; star <= SuperPlanetPositions.COUNT; ++star)
        {
            String id = SuperPlanetPositions.keyFor(star);
            if (!bodies.containsKey(id))
            {
                bodies.put(id, new BodyState(SuperPlanetPositions.initialPosition(star), false, false));
                dirty = true;
            }
        }
        if (dirty)
        {
            setDirty();
        }
    }

    /** The current world position of a super body, or null if the id is not a super body. */
    public Vec3 position(String superId)
    {
        BodyState st = bodies.get(superId);
        return st == null ? null : st.pos;
    }

    /**
     * The super body whose cube (half-extent plus {@code margin}) contains {@code p} AND is still LANDABLE (unclaimed),
     * or "" if none. A claimed body is deliberately excluded so it can never be landed on again until it relocates.
     */
    public String landableBodyAt(Vec3 p, double margin)
    {
        double reach = SuperPlanetPositions.radius() + margin;
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            BodyState st = e.getValue();
            if (st.claimed)
            {
                continue;
            }
            Vec3 c = st.pos;
            if (Math.abs(p.x - c.x) <= reach && Math.abs(p.y - c.y) <= reach && Math.abs(p.z - c.z) <= reach)
            {
                return e.getKey();
            }
        }
        return "";
    }

    public boolean isClaimed(String superId)
    {
        BodyState st = bodies.get(superId);
        return st != null && st.claimed;
    }

    /** Whether a super body's ball is dropped-but-uncollected: god beaten, ball block placed, no player has taken it. */
    public boolean isBallDropped(String superId)
    {
        BodyState st = bodies.get(superId);
        return st != null && st.ballDropped;
    }

    /**
     * Mark a super body's ball as DROPPED but not yet collected (its god just died and the ball block was placed). The
     * body stays grey and landable in this state; its god is not respawned. Returns true the first time only, so the
     * caller places exactly one ball and a double death / respawn re-kill places nothing.
     */
    public boolean markBallDropped(String superId)
    {
        BodyState st = bodies.get(superId);
        if (st == null || st.claimed || st.ballDropped)
        {
            return false;
        }
        st.ballDropped = true;
        setDirty();
        return true;
    }

    /**
     * Mark a super body CLAIMED: a player has collected its Super Dragon Ball. Clears the dropped-but-uncollected flag,
     * so the body now draws as a dragon ball and is no longer landable. Returns true the first time, so the caller acts
     * once.
     */
    public boolean markClaimed(String superId)
    {
        BodyState st = bodies.get(superId);
        if (st == null || st.claimed)
        {
            return false;
        }
        st.claimed = true;
        st.ballDropped = false;
        setDirty();
        return true;
    }

    /** Every currently CLAIMED super id. */
    public List<String> claimedIds()
    {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            if (e.getValue().claimed)
            {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Every currently UNCLAIMED (landable, grey) super id and its position, for the radar guidance. */
    public List<Map.Entry<String, Vec3>> unclaimedBodies()
    {
        List<Map.Entry<String, Vec3>> out = new ArrayList<>();
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            if (!e.getValue().claimed)
            {
                out.add(Map.entry(e.getKey(), e.getValue().pos));
            }
        }
        return out;
    }

    /**
     * Relocate a body: draw a fresh constraint-satisfying position clear of the other six, clear BOTH its claimed and its
     * dropped-but-uncollected flags, so its render reverts to stone-grey, it becomes landable again and its god re-spawns
     * on the next visit. Returns the new position. Runs on the server thread.
     */
    public Vec3 relocate(MinecraftServer server, String superId)
    {
        BodyState st = bodies.get(superId);
        if (st == null)
        {
            return null;
        }
        List<Vec3> others = new ArrayList<>();
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            if (!e.getKey().equals(superId))
            {
                others.add(e.getValue().pos);
            }
        }
        RandomSource random = RandomSource.create();
        Vec3 fresh = SuperPlanetPositions.sampleRelocation(server, others, random);
        st.pos = fresh;
        st.claimed = false;
        st.ballDropped = false;
        setDirty();
        return fresh;
    }

    /** An immutable snapshot of every body's id, position and claimed flag, for the layout sync. Seven entries. */
    public List<PacketSpaceLayoutSync.SuperBody> snapshot()
    {
        List<PacketSpaceLayoutSync.SuperBody> out = new ArrayList<>(bodies.size());
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            BodyState st = e.getValue();
            out.add(new PacketSpaceLayoutSync.SuperBody(e.getKey(), st.pos, st.claimed));
        }
        return out;
    }
}
