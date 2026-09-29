package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * A lazily-cached index of every GENERATED planet the server would derive within {@link
 * PlanetSpawnModule#generatedSpawnRange()} of the space origin, for the admin {@code /planet search} and the
 * destroy/restore tab-completion. It reuses the EXACT live derivation ({@link GeneratedPlanets#generatedNear}), so an id
 * listed here is byte-for-byte the id {@code /planet destroy} accepts: generation, destroyed-suppression and the
 * cross-cell overlap contest all flow through the same code the game draws from. Nothing here reimplements the hash.
 *
 * <h3>Why enumerating is cheap</h3>
 * Generated planets sit in {@link GeneratedPlanets#sectorSize}-block cells (default 2048). The spawn range is a config
 * bound (default 12000), so the walk covers roughly {@code (2 * 12000 / 2048) ~= 12} cells per axis. The vertical band
 * ({@link GeneratedPlanets#minY}..{@link GeneratedPlanets#maxY}, ~200..1600) is under one cell tall, so nearly every
 * off-band cell rejects in its first branch. The whole walk is on the order of a couple of thousand cell derivations,
 * well under a few milliseconds, so it runs on the server thread with no worker hand-off. The result is still CACHED and
 * only rebuilt when {@link GeneratedPlanetClaims#searchEpoch()} moves (a destroy, a debris generation bump or a restore)
 * or a config bake changes the sector size or range, so repeated tab-completion keystrokes never re-walk.
 *
 * <h3>What is NOT cached here</h3>
 * Only the pure geometry (existing generated planet bodies) is cached. A planet's claim and destroyed STATE, and every
 * MOON (which orbits, so its position moves every tick), are read live at call time: moons are a handful of fixed bodies,
 * and claim/destroyed reads are tiny map lookups, so nothing is gained by caching them and a stale claim would mislead.
 */
public final class GeneratedPlanetIndex
{
    private GeneratedPlanetIndex()
    {
    }

    // the cached snapshot and the inputs it was built for. Guarded by the class monitor; a command and a suggestion can
    // both touch it, both on the server thread, but synchronise anyway so a future off-thread caller cannot tear it.
    private static long cachedEpoch = -1L;
    private static int cachedSectorSize = -1;
    private static double cachedRange = -1.0;
    private static List<GeneratedPlanets.Generated> cachedPlanets = new ArrayList<>();

    /**
     * Every existing (live, not destroyed) generated planet within the spawn range of the space origin, from the cached
     * snapshot, rebuilt only when stale. Centred on the middle of the vertical body band so the sphere the range describes
     * covers the band symmetrically rather than clipping its top. Never mutate the returned list.
     */
    public static synchronized List<GeneratedPlanets.Generated> existingPlanets(MinecraftServer server)
    {
        long epoch = GeneratedPlanetClaims.get(server).searchEpoch();
        int sector = GeneratedPlanets.sectorSize();
        double range = PlanetSpawnModule.generatedSpawnRange();
        if (epoch != cachedEpoch || sector != cachedSectorSize || range != cachedRange)
        {
            double bandCentreY = (GeneratedPlanets.minY + GeneratedPlanets.maxY) / 2.0;
            cachedPlanets = GeneratedPlanets.generatedNear(server, new Vec3(0.0, bandCentreY, 0.0), range);
            cachedEpoch = epoch;
            cachedSectorSize = sector;
            cachedRange = range;
        }
        return cachedPlanets;
    }

    /**
     * Every moon that currently EXISTS: one per fixed body that has a live (not destroyed) moon, at its position for the
     * given game time. Derived live (a few fixed bodies), never cached, because a moon orbits. Existence and position both
     * come from {@link MoonBody}, the one authority the renderer and landing volume also read.
     */
    public static List<MoonBody.Moon> existingMoons(MinecraftServer server, long gameTime)
    {
        List<MoonBody.Moon> out = new ArrayList<>();
        for (FixedBody fb : SpaceLayout.fixedBodies(server))
        {
            MoonBody.Moon moon = MoonBody.moonFor(server, fb.key, fb.position, fb.radius, gameTime);
            if (moon != null)
            {
                out.add(moon);
            }
        }
        return out;
    }

    /**
     * The ids {@code /planet destroy} can act on: every existing generated planet plus every existing moon (both are
     * destructible, see {@link GeneratedPlanets#isDestructible}). For the destroy suggestion provider. Cheap: the planets
     * come from the cache and the moons are a handful of live derivations.
     */
    public static List<String> destructibleIds(MinecraftServer server, long gameTime)
    {
        List<String> out = new ArrayList<>();
        for (GeneratedPlanets.Generated g : existingPlanets(server))
        {
            out.add(g.id);
        }
        for (MoonBody.Moon m : existingMoons(server, gameTime))
        {
            out.add(m.id);
        }
        return out;
    }

    /**
     * The ids {@code /planet restore} can act on: every currently-destroyed planet or moon. For the restore suggestion
     * provider. Reads only the tiny destroyed set, so it needs no cache.
     */
    public static List<String> destroyedIds(MinecraftServer server)
    {
        return new ArrayList<>(GeneratedPlanetClaims.get(server).destroyedPlanetIds());
    }
}
