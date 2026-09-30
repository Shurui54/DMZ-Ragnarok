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

// orbital maths + the shared cross-shard clock: a super body now orbits the central sun slowly, its position derived
// from stored elements at the shared instant so every shard and client agree without syncing a position per tick.

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

    // one body's persisted state. Since the space rework a super body ORBITS the central sun slowly on the shared
    // cross-shard clock, so its authoritative position is DERIVED from orbital ELEMENTS rather than a frozen xyz: a
    // stored xyz would diverge per shard as each shard's clock advances. The elements (radius from the sun, a start phase
    // and a period) are the additive schema field. They are derived ONCE from the body's stored position at first load,
    // so an existing save's current position becomes the orbit's starting point and nothing jumps; a fresh body derives
    // them from its seeded initial position. {@code pos} is kept as the derivation seed and the last-relocation anchor.
    static final class BodyState
    {
        Vec3 pos;
        boolean claimed;
        boolean ballDropped;

        // orbital elements around the sun. y is the body's fixed plane height. elementsSet is false until derived.
        double radius;
        double phase0;
        double period;
        double y;
        boolean elementsSet;

        BodyState(Vec3 pos, boolean claimed, boolean ballDropped)
        {
            this.pos = pos;
            this.claimed = claimed;
            this.ballDropped = ballDropped;
        }
    }

    // Derive (or re-derive) a body's orbital elements from a position, so that at {@code epochMillis} the orbit passes
    // through exactly that position and drifts on from there. Called at first load (from the stored position, so an
    // existing save is continuous) and on every relocation (from the fresh spot). The period comes from the same
    // Kepler-like curve the fixed planets use, which at a super body's large radius is already many hours per turn, i.e.
    // the "slowly" the design asks for.
    private static void deriveElements(BodyState st, long epochMillis)
    {
        Vec3 sun = PlanetPositions.sunPosition();
        double dx = st.pos.x - sun.x;
        double dz = st.pos.z - sun.z;
        double radius = Math.hypot(dx, dz);
        if (radius < 1.0)
        {
            radius = SuperPlanetPositions.MIN_FROM_EARTH;   // degenerate (on the sun): push out to a sane ring.
        }
        double currentAngle = Math.atan2(dz, dx);
        double period = Orbits.periodMillis(radius);
        st.radius = radius;
        st.period = period;
        st.phase0 = Orbits.phaseForContinuity(currentAngle, period, epochMillis);
        st.y = st.pos.y;
        st.elementsSet = true;
    }

    // the body's live world position at the current shared instant, derived from its orbital elements.
    private static Vec3 livePosition(BodyState st)
    {
        Vec3 sun = PlanetPositions.sunPosition();
        double[] xz = Orbits.positionXZ(sun.x, sun.z, st.radius, st.phase0, OrbitClock.epochMillis());
        return new Vec3(xz[0], st.y, xz[1]);
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
            BodyState st = new BodyState(pos, claimed, ballDropped);
            // ADDITIVE schema: orbital elements are present only in saves written since the space rework. An older save
            // has none, so elementsSet stays false and the elements are derived from `pos` on the first touch
            // (ensureInitialized), making its current position the orbit start. A newer save restores them verbatim.
            if (b.contains("orbRadius"))
            {
                st.radius = b.getDouble("orbRadius");
                st.phase0 = b.getDouble("orbPhase0");
                st.period = b.getDouble("orbPeriod");
                st.y = b.getDouble("orbY");
                st.elementsSet = true;
            }
            s.bodies.put(id, st);
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
            if (st.elementsSet)
            {
                b.putDouble("orbRadius", st.radius);
                b.putDouble("orbPhase0", st.phase0);
                b.putDouble("orbPeriod", st.period);
                b.putDouble("orbY", st.y);
            }
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
        // derive orbital elements for any body that has none yet (a fresh body, or one loaded from a pre-rework save):
        // its stored position becomes the orbit start at the current shared instant, so nothing jumps on the first tick.
        long epoch = OrbitClock.epochMillis();
        for (BodyState st : bodies.values())
        {
            if (!st.elementsSet)
            {
                deriveElements(st, epoch);
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
        return st == null ? null : livePosition(st);
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
            Vec3 c = livePosition(st);
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
                out.add(Map.entry(e.getKey(), livePosition(e.getValue())));
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
                others.add(livePosition(e.getValue()));
            }
        }
        RandomSource random = RandomSource.create();
        Vec3 fresh = SuperPlanetPositions.sampleRelocation(server, others, random);
        st.pos = fresh;
        st.claimed = false;
        st.ballDropped = false;
        // the fresh spot becomes the start of a new orbit at the current instant, so the body eases into orbiting from
        // where it was placed rather than snapping to some other bearing.
        deriveElements(st, OrbitClock.epochMillis());
        setDirty();
        return livePosition(st);
    }

    /** An immutable snapshot of every body's id, position and claimed flag, for the layout sync. Seven entries. */
    public List<PacketSpaceLayoutSync.SuperBody> snapshot()
    {
        Vec3 sun = PlanetPositions.sunPosition();
        List<PacketSpaceLayoutSync.SuperBody> out = new ArrayList<>(bodies.size());
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            BodyState st = e.getValue();
            // sync the ELEMENTS, not a frozen position, so the client drives each super body's slow orbit off the same
            // shared clock the server does (OrbitClock) and both stay in step between syncs, exactly like the fixed
            // planets. The sun centre rides along so the client needs no extra lookup.
            out.add(new PacketSpaceLayoutSync.SuperBody(
                    e.getKey(), st.radius, st.phase0, st.period, sun.x, sun.z, st.y, st.claimed));
        }
        return out;
    }
}
