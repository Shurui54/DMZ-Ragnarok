package net.shurui.shuruisutilities.space;

import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * A planet's orbiting MOON promoted to a real, landable, claimable body. A moon has no state of its own: like every
 * other body in this feature (see {@link GeneratedPlanets}, {@link PlanetPositions}) everything about it is a pure
 * function of its PARENT body, recomputed every time and never stored. Its stable id, its short parent-derived name and
 * its small surface size all fall out of the parent key; its space position is a function of the parent position and
 * the world game time, because it ORBITS.
 *
 * <h3>Where a moon exists (one shared, code-driven rule, no texture alpha, no hardcoded list)</h3>
 * Every FIXED planet body has a moon; nothing else does. A fixed body is exactly an entry in
 * {@link SpaceLayout#fixedBodies}, the ONE set both sides already agree on: on the server it is the live
 * {@link PlanetRegistry#bodies}, on the client it is the snapshot {@code PacketSpaceLayoutSync} pushes. {@link #hasMoon}
 * takes the same {@code server} handle every other space derivation threads through, so it reads that identical set from
 * whichever side is asking: the server reads its live registry, the client (server == null) reads the synced snapshot.
 * The renderer (SpaceBodyRenderer.drawMoon) gates the drawn moon on the SAME {@code hasMoon(null, key)}, so the landing
 * volume and the drawn moon can never disagree about whether a moon is there. This deliberately REPLACES the old
 * texture-alpha probe: existence no longer depends on whether a sheet happens to carry moon art, so Namek, Sacred Kai
 * and every other fixed destination now get a moon even though only overworld.png has a drawn moon region (the renderer
 * falls back to a tinted placeholder moon for the arted-but-moonless sheets). Generated planets are NOT fixed bodies, so
 * they still have no moon.
 *
 * <h3>The orbit, and why the client and server agree on where the moon is</h3>
 * The moon orbits, so its world position moves every tick. The landing volume must therefore TRACK the rendered moon,
 * not sit at a fixed notional point: if the server checked landing against a fixed angle while the client drew the moon
 * elsewhere on its ring, a player would fly into the visible moon and not land (the exact bug this feature shipped once
 * before). This class OWNS the orbit: {@link #ORBIT_DEG_PER_TICK}, {@link #ORBIT_FACTOR} and {@link #position} are the
 * one definition of period, radius and formula, and the renderer reads them rather than keeping its own. The moon sits at
 * a {@code +X} step of length {@code parentRadius * ORBIT_FACTOR} rotated by {@code YP(gameTime * ORBIT_DEG_PER_TICK)} in
 * the parent's untilted body frame, which resolves to the local offset {@code (cos a, 0, -sin a) * R} added to the parent
 * position (verified against joml). The renderer applies that SAME Y-rotation and {@code +X} step through its pose stack,
 * so it draws the moon at exactly this coordinate. The only difference is the sub-tick term: the renderer feeds
 * {@code gameTime + partialTick} (via the float {@link #position(Vec3, float, float)} overload) for a smooth glide, the
 * server feeds the integer {@code gameTime}. At the orbit's crawl (a full turn takes 7200 ticks) that is well under a
 * tenth of a block per tick, far inside the multi-block landing margin, so the two never visibly diverge.
 *
 * <p>A moon's CLAIM keys on its stable id, never its position, so claiming a moving body is no different from claiming a
 * planet: the id is fixed even as the body sweeps its orbit. The surface a player lands on is a normal cell in the
 * shared {@code planet_surface} dimension, placed by the same id-hash fold every other body uses, so a moon's surface
 * can never touch another body's.
 */
public final class MoonBody
{
    private MoonBody()
    {
    }

    // Stable id namespace for a moon, distinct from a generated planet's ({@code sugen:}) and from any dimension id
    // (which fixed planets key on), so a moon id can never collide with either. The parent key is embedded verbatim so
    // the moon's parent-derived NAME can be recovered from the id alone with nothing stored (parent keys are unique, so
    // moon ids are unique too). Example: parent "minecraft:overworld" -> "sumoon:minecraft:overworld".
    public static final String ID_PREFIX = "sumoon:";

    // The surface size, in blocks per side, a moon's stamped landmass is built at. This is the moon's OWN value, its own
    // source of truth, routed straight through GeneratedPlanets.surfaceSizeForId so it bypasses the generated-planet
    // 100..500 range entirely. Config-baked (PlanetSpawnModule.setSurfaceSize) so an operator can retune it; the default
    // 400 is the user-locked "every main planet gets a 400x400 moon". volatile: written on the config thread, read on the
    // server thread (the stamp, the boundary) and never affects the moon's SPACE cube or orbit (those scale from the
    // PARENT radius, see RADIUS_FACTOR/ORBIT_FACTOR), only the terrain in the surface dimension. Kept even so size/2 splits
    // cleanly; the setter enforces that.
    public static volatile int SURFACE_SIZE = 400;

    // The size a moon was stamped at BEFORE this stage widened moons to 400. Every moon already on disk was stamped 20
    // wide, so its terrain ends at the 20 rim. This is the legacy tier GeneratedPlanets.legacySurfaceSizeForId hands the
    // stamped-size backfill for an already-stamped moon with no persisted size: it MUST record 20, not the new 400, or the
    // horizontal boundary, garrison scatter and salvage would all read 400 over 20-wide ground and walk a player off the
    // real rim into void. A moon stamped by stage 2a..2c already persisted its size (20) and is read back from the store,
    // so this only ever covers a moon stamped by a build too old to persist geometry. Do not fold this into SURFACE_SIZE;
    // it is deliberately the OLD constant, mirroring GeneratedPlanets.LEGACY_MIN_SURFACE.
    public static final int LEGACY_SURFACE_SIZE = 20;

    // set the moon surface size from config, forced even and floored at a sane minimum. Called by PlanetSpawnModule.
    public static void setSurfaceSize(int size)
    {
        int even = Math.max(2, size);
        even -= (even & 1);
        SURFACE_SIZE = even;
    }

    // Both sides read these: the server (position, moonFor) to place the landing volume, and the client renderer
    // (SpaceBodyRenderer.drawMoon) to draw the moon. The renderer no longer keeps its own copies, so the drawn moon and
    // the landing volume can never drift apart. If the orbit is ever retuned, change it HERE and both sides follow.

    // degrees the moon advances per tick. 0.05 -> a full 360 orbit every 7200 ticks (six minutes), a slow glide.
    public static final float ORBIT_DEG_PER_TICK = 0.05F;
    // orbit radius as a multiple of the parent's visual half-extent: 2.6x sits the moon clearly outside the parent body
    // and its cloud shell, and scales with the parent so a big world gets a proportionally wider orbit.
    public static final float ORBIT_FACTOR = 2.6F;
    // moon visual half-extent as a multiple of the parent half-extent: the geo Moon is 2 units against the 8-unit body.
    public static final float RADIUS_FACTOR = 0.25F;

    /**
     * A single moon reduced to what the landing/claim paths need: its stable id, its CURRENT orbit position, its visual
     * half-extent and its surface size. A pure snapshot for one game-time, never stored.
     */
    public static final class Moon
    {
        public final String id;
        public final Vec3 position;
        public final float radius;
        public final int surfaceSize;

        Moon(String id, Vec3 position, float radius, int surfaceSize)
        {
            this.id = id;
            this.position = position;
            this.radius = radius;
            this.surfaceSize = surfaceSize;
        }
    }

    // the stable id for the moon of a given parent key. The parent key is embedded verbatim (see ID_PREFIX).
    public static String idFor(String parentKey)
    {
        return ID_PREFIX + parentKey;
    }

    // true if the given id is a moon id.
    public static boolean isMoon(String id)
    {
        return id != null && id.startsWith(ID_PREFIX);
    }

    // recover the parent key embedded in a moon id, or "" if the id is not a moon id.
    public static String parentKeyOf(String moonId)
    {
        return isMoon(moonId) ? moonId.substring(ID_PREFIX.length()) : "";
    }

    /**
     * The moon of a parent body at the given game time, or null if that parent has no moon. Pure function of the parent
     * key, the parent position and the game time. {@code radius} is the parent radius scaled by {@link #RADIUS_FACTOR};
     * {@code position} is the current orbit point (see {@link #position}).
     */
    public static Moon moonFor(MinecraftServer server, String parentKey, Vec3 parentPos, float parentRadius, long gameTime)
    {
        if (!hasMoon(server, parentKey))
        {
            return null;
        }
        Vec3 pos = position(parentPos, parentRadius, gameTime);
        float radius = parentRadius * RADIUS_FACTOR;
        return new Moon(idFor(parentKey), pos, radius, SURFACE_SIZE);
    }

    /**
     * The moon's world position for a given game time. This is the orbit's single formula: the moon sits at a Y-rotation
     * of {@code YP(angle)} then a {@code +X} step of length {@code parentRadius * ORBIT_FACTOR} in the parent's untilted
     * body frame, which is the local offset {@code (cos a, 0, -sin a) * R} added to the parent position. The client
     * renderer draws with the SAME Y-rotation and {@code +X} step (see SpaceBodyRenderer.drawMoon), so this world point
     * is exactly where the moon is drawn. Double throughout: parent positions sit at thousands of blocks and a moon
     * coordinate must not lose precision to a float.
     *
     * <p>The server tick is whole, so it calls this {@code long} overload; the renderer wants a smooth sub-tick position
     * and calls {@link #position(Vec3, float, float)} with {@code gameTime + partialTick}.
     */
    public static Vec3 position(Vec3 parentPos, float parentRadius, long gameTime)
    {
        return position(parentPos, parentRadius, (float) gameTime);
    }

    /**
     * The sub-tick overload of {@link #position(Vec3, float, long)}: takes a fractional game time so the client renderer
     * gets a SMOOTH orbit position between whole server ticks. Same formula, same constants; only the time is fractional.
     */
    public static Vec3 position(Vec3 parentPos, float parentRadius, float gameTime)
    {
        double angle = Math.toRadians((double) gameTime * ORBIT_DEG_PER_TICK);
        double r = (double) parentRadius * ORBIT_FACTOR;
        double ox = Math.cos(angle) * r;
        double oz = -Math.sin(angle) * r;
        return new Vec3(parentPos.x + ox, parentPos.y, parentPos.z + oz);
    }

    // a short, parent-derived display name for a moon id: the capitalised parent path followed by " Moon"
    // (e.g. "minecraft:overworld" -> "Overworld Moon", "dragonminez:namek" -> "Namek Moon"). Pure function of the id.
    public static String nameFor(String moonId)
    {
        String parentKey = parentKeyOf(moonId);
        if (parentKey.isEmpty())
        {
            return "Moon";
        }
        int colon = parentKey.indexOf(':');
        String path = colon >= 0 ? parentKey.substring(colon + 1) : parentKey;
        if (path.isEmpty())
        {
            return "Moon";
        }
        String pretty = path.substring(0, 1).toUpperCase(Locale.ROOT) + path.substring(1);
        return pretty + " Moon";
    }

    /**
     * Whether the parent body has a moon: true exactly when the parent key is a FIXED planet body, i.e. an entry in
     * {@link SpaceLayout#fixedBodies}. This is the ONE code-driven rule both sides read, replacing the old texture-alpha
     * probe, so moon existence no longer depends on a sheet carrying moon art. It branches exactly like every other space
     * derivation:
     * <ul>
     *   <li>{@code server != null} (the server thread the caller is already on): the live {@link PlanetRegistry#bodies}
     *       list, so the landing volume is always authoritative.</li>
     *   <li>{@code server == null} (the client render thread): the last snapshot {@code PacketSpaceLayoutSync} pushed, the
     *       identical set the renderer already derives its fixed bodies from, so the drawn moon and the landing volume
     *       agree. Before the first sync the snapshot is empty, which just means the client draws no moon until it is told
     *       the fixed bodies, never a moon the server does not have.</li>
     * </ul>
     * A generated planet key is never in that set, so generated planets have no moon.
     *
     * <p>DESTROYED MOONS DO NOT EXIST. A moon can be busted like a generated planet, and a busted moon must stop drawing
     * AND stop being landable at the same instant, or it reproduces the class-doc bug (a moon drawn but not landable, or
     * landable but invisible). So existence is gated here on the ONE synced destroyed seam every generated-planet consumer
     * reads, {@link SpaceLayout#isDestroyed}: server-side it is the authoritative SavedData, client-side it is the snapshot
     * that rides the SAME {@code PacketSpaceLayoutSync} as the fixed-body set. Because every existence consumer
     * (SpaceBodyRenderer.drawMoon, SpaceTravelModule via {@link #moonFor}, and PlanetInfoTarget via {@link #moonFor}) funnels
     * through this method, they all learn a moon is gone from this single read and can never disagree. A moon's destroyed
     * flag is stored under the moon id as its own synthetic cell key (see GeneratedPlanets.forMoon), so the debris timer's
     * restore brings the SAME moon back and this returns true again.
     */
    public static boolean hasMoon(MinecraftServer server, String parentKey)
    {
        if (parentKey == null || parentKey.isEmpty())
        {
            return false;
        }
        boolean fixed = false;
        for (FixedBody fb : SpaceLayout.fixedBodies(server))
        {
            if (parentKey.equals(fb.key))
            {
                fixed = true;
                break;
            }
        }
        if (!fixed)
        {
            return false;
        }
        // a busted moon does not exist until it regenerates: read the destroyed flag from the SAME synced seam the renderer
        // and the landing volume both reach through here, so the drawn moon and the landing cube vanish together.
        return !SpaceLayout.isDestroyed(server, idFor(parentKey));
    }
}
