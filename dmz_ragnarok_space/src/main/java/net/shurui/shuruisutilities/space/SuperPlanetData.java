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

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// orbital maths + the shared cross-shard clock: a super body orbits the central sun on its own random orbit, its position
// derived from stored elements at the shared instant so every shard and client agree without syncing a position per tick.

/**
 * Overworld-attached saved data for the seven SUPER dragon-ball bodies: the ONE authoritative home for their progress.
 * Mirrors {@link GeneratedPlanetClaims} and {@link PlanetGarrisonData} (persisted under a fixed name on the overworld
 * data storage, keyed by the body's STABLE id {@code susuper:<star>}).
 *
 * <p>A super body's ORBIT lives here rather than being a pure function, because a body gets a new random orbit when its
 * ball is lost (point 5). Each body carries:
 * <ul>
 *   <li>its orbit elements around the central sun (radius, start phase, period, plane height), chosen by {@link
 *       SuperPlanetPositions#chooseOrbit} inside a radius band no other body uses. Synced to clients (see {@link
 *       PacketSpaceLayoutSync}), which derive the live position at the shared clock exactly as the server does, so the
 *       drawn body sits exactly where the server lands. {@code pos} is kept as where the orbit put the body when it was
 *       chosen (written to the save for older builds and for the log).</li>
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

    // Version of the orbit scheme a body's elements were written under. 2 = its own random orbit around the sun inside a
    // reserved band (2026-10-21). A save without it (or with 1) holds the old scheme: elements derived from a spot seeded
    // around Earth, which is migrated on first start (see ensureInitialized).
    private static final int ORBIT_VERSION = 2;

    // one body's persisted state. A super body ORBITS the central sun on the shared cross-shard clock, so its
    // authoritative position is DERIVED from orbital ELEMENTS (radius from the sun, a start phase, a period, a plane
    // height) rather than a frozen xyz, which would diverge per shard as each shard's clock advances. The element keys
    // (orbRadius, orbPhase0, orbPeriod, orbY) are the same ones the previous scheme wrote, so an older build that loads a
    // newer save still reads a valid orbit; orbVer marks which scheme wrote them.
    static final class BodyState
    {
        Vec3 pos;
        boolean claimed;
        boolean ballDropped;

        // orbital elements around the sun. y is the body's fixed plane height. elementsSet is false until assigned.
        double radius;
        double phase0;
        double period;
        double y;
        boolean elementsSet;
        int orbitVersion;

        BodyState(Vec3 pos, boolean claimed, boolean ballDropped)
        {
            this.pos = pos;
            this.claimed = claimed;
            this.ballDropped = ballDropped;
        }
    }

    // install a chosen orbit on a body. pos becomes where the orbit puts it right now.
    private static void applyOrbit(BodyState st, SuperPlanetPositions.Orbit orbit, long epochMillis)
    {
        st.radius = orbit.radius();
        st.phase0 = orbit.phase0();
        st.period = orbit.period();
        st.y = orbit.y();
        st.elementsSet = true;
        st.orbitVersion = ORBIT_VERSION;
        st.pos = orbit.positionAt(epochMillis);
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
        data.ensureInitialized(server);
        return data;
    }

    /**
     * The seven live positions as a flat {x0,y0,z0,x1,...} array, or an empty array if the overworld (which holds this
     * data) is not loaded yet. For {@link SuperPlanetPositions#overlapsLive}, which the hazard fields call per cell.
     */
    static double[] livePositionsOrEmpty(MinecraftServer server)
    {
        if (server.getLevel(Level.OVERWORLD) == null)
        {
            return new double[0];
        }
        SuperPlanetData data = get(server);
        double[] out = new double[data.bodies.size() * 3];
        int i = 0;
        for (BodyState st : data.bodies.values())
        {
            Vec3 p = livePosition(st);
            out[i++] = p.x;
            out[i++] = p.y;
            out[i++] = p.z;
        }
        return out;
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
            // ADDITIVE schema: orbital elements are present only in saves written since the space rework, and orbVer only
            // in saves written since the sun-centred random orbits (2026-10-21). A body without a current-scheme orbit is
            // given one on the first touch (ensureInitialized) and logged; a current save restores it verbatim.
            if (b.contains("orbRadius"))
            {
                st.radius = b.getDouble("orbRadius");
                st.phase0 = b.getDouble("orbPhase0");
                st.period = b.getDouble("orbPeriod");
                st.y = b.getDouble("orbY");
                st.elementsSet = true;
                st.orbitVersion = b.getInt("orbVer");   // 0 when absent: the pre-2026-10-21 Earth-anchored scheme.
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
                b.putInt("orbVer", st.orbitVersion);
            }
            list.add(b);
        }
        tag.put("bodies", list);
        return tag;
    }

    // Give every body a valid orbit the first time this data is touched. Idempotent: a body already on a current-scheme
    // orbit inside its own band is left alone. Three cases get a fresh random orbit (claimed / ballDropped and the star
    // number are always kept, only the orbit changes):
    //   - a missing star (a fresh world, or a future COUNT bump);
    //   - MIGRATION: a body from an older save (no elements, or elements of the old Earth-anchored scheme), logged per body
    //     with where it was and where its new orbit is, so the owner can see the move;
    //   - a body whose radius no longer falls in a band, or shares a band with another, which only happens if the fixed
    //     ring config (fixedRingRadius / fixedPlanetMinSeparation) changed, since the bands sit between those rings.
    // the ring geometry the bodies were last validated against. get() runs this every call (several times a tick), so the
    // band checks only run again when that geometry changes (a config bake) or a body is missing.
    private double validatedRings = Double.NaN;

    private void ensureInitialized(MinecraftServer server)
    {
        double rings = PlanetPositions.ringRadiusOfIndex(0) * 1.0E6 + PlanetPositions.ringRadiusOfIndex(1);
        if (rings == validatedRings && bodies.size() >= SuperPlanetPositions.COUNT)
        {
            return;
        }
        validatedRings = rings;
        boolean dirty = false;
        for (int star = 1; star <= SuperPlanetPositions.COUNT; ++star)
        {
            String id = SuperPlanetPositions.keyFor(star);
            if (!bodies.containsKey(id))
            {
                bodies.put(id, new BodyState(Vec3.ZERO, false, false));
            }
        }
        long epoch = OrbitClock.epochMillis();
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        List<String> needOrbit = new ArrayList<>();
        // stable order (star 1..7) so which body keeps a contested band is deterministic.
        for (int star = 1; star <= SuperPlanetPositions.COUNT; ++star)
        {
            String id = SuperPlanetPositions.keyFor(star);
            BodyState st = bodies.get(id);
            int band = st.elementsSet && st.orbitVersion >= ORBIT_VERSION ? SuperPlanetPositions.bandOf(st.radius) : -1;
            if (band < 0 || !taken.add(band))
            {
                needOrbit.add(id);
            }
        }
        if (needOrbit.isEmpty())
        {
            return;
        }
        RandomSource random = RandomSource.create();
        for (String id : needOrbit)
        {
            BodyState st = bodies.get(id);
            int band = SuperPlanetPositions.pickFreeBand(taken, random);
            if (band < 0)
            {
                continue;   // cannot happen (bandCount() > COUNT); leave the body as it was rather than throw.
            }
            taken.add(band);
            String why = !st.elementsSet ? (st.pos == Vec3.ZERO ? "new" : "migrated (no orbit)")
                    : st.orbitVersion < ORBIT_VERSION ? "migrated" : "ring config changed";
            Vec3 before = st.elementsSet ? livePosition(st) : st.pos;
            double beforeSun = st.elementsSet ? st.radius : -1.0;
            SuperPlanetPositions.Orbit orbit = SuperPlanetPositions.chooseOrbit(server, band, random);
            applyOrbit(st, orbit, epoch);
            LoggingHandler.sulog.info("[SuperPlanet] {} star {} {}: was at ({}, {}, {}){}, now orbits the sun at radius {} "
                            + "(band {}: {}..{}), plane y {}, period {} h, phase {} deg; state {}; crosses {} system(s).",
                    id, SuperPlanetPositions.starOf(id), why, Math.round(before.x), Math.round(before.y),
                    Math.round(before.z), beforeSun < 0 ? "" : " on radius " + Math.round(beforeSun),
                    Math.round(orbit.radius()), band, Math.round(SuperPlanetPositions.bandLow(band)),
                    Math.round(SuperPlanetPositions.bandHigh(band)), Math.round(orbit.y()),
                    String.format(java.util.Locale.ROOT, "%.1f", orbit.period() / 3600000.0),
                    Math.round(Math.toDegrees(orbit.phase0())),
                    st.claimed ? "claimed" : st.ballDropped ? "ball dropped" : "grey", orbit.systemsCrossed());
            dirty = true;
        }
        if (dirty)
        {
            SuperPlanetPositions.invalidateLive();
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
     * Relocate a body: give it a fresh random orbit around the sun, in a random band none of the other six uses (its own
     * old band or the spare one), and clear BOTH its claimed and its dropped-but-uncollected flags, so its render reverts
     * to stone-grey, it becomes landable again and its god re-spawns on the next visit. Returns the new live position.
     * Runs on the server thread.
     */
    public Vec3 relocate(MinecraftServer server, String superId)
    {
        BodyState st = bodies.get(superId);
        if (st == null)
        {
            return null;
        }
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (Map.Entry<String, BodyState> e : bodies.entrySet())
        {
            if (!e.getKey().equals(superId) && e.getValue().elementsSet)
            {
                int band = SuperPlanetPositions.bandOf(e.getValue().radius);
                if (band >= 0)
                {
                    taken.add(band);
                }
            }
        }
        RandomSource random = RandomSource.create();
        int band = SuperPlanetPositions.pickFreeBand(taken, random);
        st.claimed = false;
        st.ballDropped = false;
        if (band >= 0)
        {
            SuperPlanetPositions.Orbit orbit = SuperPlanetPositions.chooseOrbit(server, band, random);
            applyOrbit(st, orbit, OrbitClock.epochMillis());
            LoggingHandler.sulog.info("[SuperPlanet] {} relocated to a new orbit: radius {} (band {}), plane y {}, period "
                            + "{} h; crosses {} system(s).", superId, Math.round(orbit.radius()), band,
                    Math.round(orbit.y()), String.format(java.util.Locale.ROOT, "%.1f", orbit.period() / 3600000.0),
                    orbit.systemsCrossed());
        }
        SuperPlanetPositions.invalidateLive();
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
