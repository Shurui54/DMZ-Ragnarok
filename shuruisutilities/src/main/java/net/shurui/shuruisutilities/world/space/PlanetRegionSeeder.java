package net.shurui.shuruisutilities.world.space;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.forgespi.locating.IModFile;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Seeds SU's authored Planet Vegeta and Beerus region files into the save on server start, so those
 * builds materialise for every user with no manual step. Mirrors the structure and safety of DMZ
 * 2.1.3's {@code com.dragonminez.server.world.dimension.OtherworldRegionLoader}: the .mca files ship
 * inside SU's jar under {@code data/shuruisutilities/regions/&lt;resource&gt;/} and are copied into the save's
 * {@code dimensions/shuruisutilities/&lt;targetDim&gt;/region/} folder. Vegeta is copied into its own
 * dimension; Beerus is copied into the SHARED generated-planet surface dimension
 * ({@code shuruisutilities:planet_surface}) so the god's planet lives alongside the procedural planets
 * with their sky, fog and standard boundary instead of a dimension and glass dome of its own. Both
 * target dimensions run a void/flat generator that only produces chunks NOT already on disk, so the
 * seeded files are the build. The .mca keep their authored absolute coordinates (a region file bakes its
 * chunk positions and cannot be relocated by a copy), so Beerus lands at the same world coordinates it
 * always occupied, in surface cell (0,0), which SpaceTravelModule reserves from procedural stamping.
 *
 * <p>Design guarantees:
 * <ul>
 *   <li><b>Copy if absent only.</b> An existing region file is never overwritten. A player may have
 *       built inside Beerus or Vegeta, and clobbering their region would destroy that work. Missing
 *       files only.</li>
 *   <li><b>Size gate.</b> Each copy lands in a temp file first, is validated against a minimum valid
 *       size and (when the classpath entry length is known) an exact byte-count match, then atomically
 *       moved into place. A truncated or partial copy is detected and discarded rather than left behind
 *       as a corrupt region.</li>
 *   <li><b>Fail soft.</b> Any {@link Throwable} is logged on one line and startup continues. A failed
 *       seed must never prevent the server from starting.</li>
 *   <li><b>Idempotent.</b> After the first boot every file exists, so subsequent boots are a no-op.</li>
 * </ul>
 *
 * <p><b>KNOWN ONE-WAY BEHAVIOUR:</b> because seeding is copy-if-absent, shipping a REVISED region build
 * in a later SU version will NOT replace the chunks in a world that was already seeded once. The old
 * chunks stay on disk forever. If a versioned reseed is ever wanted it must be added deliberately (for
 * example a stamped marker file that triggers a backup-then-replace), and that is out of scope here.
 *
 * <p>Server side only. Runs from {@code ServerAboutToStartEvent} (see {@code ShuruisUtilities}),
 * matching DMZ's loader: that is the last lifecycle point before any dimension does chunk I/O, so the
 * seeded files are on disk before the generator could produce fresh ones.
 */
public final class PlanetRegionSeeder
{
    private PlanetRegionSeeder()
    {
    }

    // classpath root the shipped .mca files live under, mirroring DMZ's data/<ns>/regions/ layout.
    private static final String RESOURCE_ROOT = "/data/" + ShuruisUtilities.MODID + "/regions";

    // absolute floor for a plausibly-valid region file. A .mca has a 4 KiB location table plus a 4 KiB
    // timestamp table, so any real region is at least two sectors (8192 bytes). Anything smaller is a
    // truncated copy. The exact-length check below is the stronger gate; this is the backstop for when
    // the classpath entry length is not reported.
    private static final long MIN_VALID_REGION_BYTES = 8192L;

    // SRG name of MinecraftServer.storageSource (LevelStorageSource.LevelStorageAccess). Same field the
    // MultiworldManager reflects to resolve per-dimension folders.
    private static final String SRG_STORAGE_SOURCE = "f_129744_";

    // each shipped planet: the classpath folder its .mca ship under (resourceName) and the dimension folder they are
    // seeded INTO (targetDim). resourceName and targetDim differ only for Beerus: its authored build now RESIDES in the
    // shared generated-planet surface dimension (shuruisutilities:planet_surface), not in a dimension of its own, so its
    // region files ship under regions/beerus_planet/ but are copied into dimensions/shuruisutilities/planet_surface/region/.
    // The .mca keep their authored absolute coordinates (a region file bakes its chunk positions, so it cannot be
    // relocated by a copy), so the build lands at the SAME world coordinates it always occupied, just in the surface
    // dimension where the generated planets already live. Beerus's footprint sits in surface cell (0,0); SpaceTravelModule
    // reserves that cell so no procedural planet ever stamps over the authored build.
    //
    // The set of region files is NOT listed here on purpose: it is enumerated from the jar at runtime (see
    // listShippedRegionFiles) so the seed list can never drift from what actually ships. Only the folder-to-dimension
    // mapping, which is not derivable from a file name, stays explicit.
    // targetDim is the dimension's OWN ResourceKey. SpaceKeys.SURFACE is the canonical planet_surface key, and
    // SpaceKeys.PLANET_VEGETA is built from the SAME dimension namespace source as it, so the later dimension-rename
    // stage moves both targets by moving that one source. Beerus's authored build RESIDES in the shared surface
    // dimension (its .mca ship under regions/beerus_planet/ but are copied into planet_surface), so its target is
    // SURFACE, not a dimension of its own.
    private static final Planet[] PLANETS = {
        new Planet("planet_vegeta", SpaceKeys.PLANET_VEGETA),
        new Planet("beerus_planet", SpaceKeys.SURFACE),
    };

    private record Planet(String resourceName, ResourceKey<Level> targetDim)
    {
    }

    /**
     * Seeds every shipped planet. Never throws: any failure is logged and swallowed so the server keeps
     * starting.
     */
    public static void seedAll(MinecraftServer server)
    {
        try
        {
            LevelStorageSource.LevelStorageAccess levelSave =
                ObfuscationReflectionHelper.getPrivateValue(MinecraftServer.class, server, SRG_STORAGE_SOURCE);
            if (levelSave == null)
            {
                LoggingHandler.sulog.warn("[PlanetSeeder] could not resolve the save storage source; skipping planet region seeding");
                return;
            }

            for (Planet planet : PLANETS)
                seedPlanet(levelSave, planet);
        }
        catch (Throwable t)
        {
            // fail soft: seeding must never block startup.
            LoggingHandler.sulog.error("[PlanetSeeder] planet region seeding failed; continuing startup: {}", t.toString());
        }
    }

    private static void seedPlanet(LevelStorageSource.LevelStorageAccess levelSave, Planet planet)
    {
        try
        {
            // seed INTO the target dimension folder (Beerus now lands in planet_surface, not a dimension of its own),
            // while reading the .mca from the classpath folder they ship under (resourceName). The folder is derived
            // from the dimension's OWN key (its real "shuruisutilities" namespace), NOT from ShuruisUtilities.MODID:
            // the dimensions were not renamed in the merge, so a modid-derived path pointed at
            // world/dimensions/dmz_ragnarok/, which nothing reads, and the shipped builds silently never reached the
            // world.
            Path regionDir = levelSave.getDimensionPath(planet.targetDim()).resolve("region");

            List<String> regionFiles = listShippedRegionFiles(planet.resourceName());
            if (regionFiles.isEmpty())
            {
                LoggingHandler.sulog.warn("[PlanetSeeder] {}: no shipped region files found under {}/{}; nothing to seed",
                    planet.resourceName(), RESOURCE_ROOT, planet.resourceName());
                return;
            }

            Files.createDirectories(regionDir);

            int seeded = 0;
            int skipped = 0;
            for (String fileName : regionFiles)
            {
                if (copyIfAbsent(planet.resourceName(), fileName, regionDir))
                {
                    seeded++;
                    recordFreshRegion(planet.targetDim(), fileName);
                }
                else
                    skipped++;
            }

            LoggingHandler.sulog.info("[PlanetSeeder] {} -> {}: seeded {} new region file(s), {} already present at {}",
                planet.resourceName(), planet.targetDim().location(), seeded, skipped, regionDir);
        }
        catch (Throwable t)
        {
            // per-planet fail soft: a broken planet does not stop the other, nor the server.
            LoggingHandler.sulog.error("[PlanetSeeder] {}: seeding failed; continuing: {}", planet.resourceName(), t.toString());
        }
    }

    /**
     * Enumerates the shipped .mca region files for a planet directly from the mod jar at runtime, so the seed set can
     * never drift from what actually ships (the exact drift that a hardcoded list caused). Forge mounts every mod jar on
     * a walkable filesystem, so {@link IModFile#findResource} returns a {@link Path} that {@link Files#list} can iterate
     * whether the mod runs from a packaged jar or the exploded dev classpath. Returns an empty list if the folder is
     * absent or cannot be read; the caller treats that as "nothing to seed" and moves on.
     */
    private static List<String> listShippedRegionFiles(String resourceName)
    {
        try
        {
            // Look up the mod FILE (the jar) by its LOADED container id: since the suite merge SU ships inside the
            // single dmz_ragnarok jar, so getModFileById("shuruisutilities") is now null and seeding would silently
            // stop. Resolve the hosting container id from SU's cached active container (survives a future rename).
            // The resource PATH below stays "shuruisutilities": the region files ship under data/shuruisutilities/, a
            // registry/asset namespace that the merge deliberately kept.
            IModFile modFile = ModList.get().getModFileById(ShuruisUtilities.MOD_CONTAINER.getModId()).getFile();
            Path dir = modFile.findResource("data", ShuruisUtilities.MODID, "regions", resourceName);
            if (!Files.isDirectory(dir))
                return List.of();

            try (Stream<Path> entries = Files.list(dir))
            {
                return entries
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("r.") && name.endsWith(".mca"))
                    .sorted()
                    .toList();
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetSeeder] {}: could not enumerate shipped region files: {}", resourceName, t.toString());
            return List.of();
        }
    }

    /**
     * The chunk rectangle covered by the region files copied in THIS boot, per target dimension.
     *
     * <p>Seeded chunks arrive as raw region data: their blocks were never placed through the world, so no shape or
     * neighbour update has ever run on them and anything that connects (fences, panes, walls, stairs, redstone,
     * leaves) sits in whatever state was authored. {@code ShuruisUtilities.serverStarted} drains this and hands each
     * rectangle to a one-time background block-update sweep, which is the only thing that will ever link them up.
     *
     * <p>Only FRESH copies are recorded. A boot that seeds nothing leaves this empty and costs nothing, which is
     * every boot after the first.
     */
    private static final java.util.Map<ResourceKey<Level>, int[]> FRESH_REGION_BOUNDS = new java.util.HashMap<>();

    // widen this dimension's pending rectangle to include the region file just written. Region coordinates come
    // straight from the "r.X.Z.mca" name, and one region is 32x32 chunks.
    private static void recordFreshRegion(ResourceKey<Level> dim, String fileName)
    {
        try
        {
            String[] parts = fileName.split("\\.");
            if (parts.length < 4)
                return;
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            int minCX = rx << 5;
            int minCZ = rz << 5;
            int maxCX = minCX + 31;
            int maxCZ = minCZ + 31;
            FRESH_REGION_BOUNDS.merge(dim, new int[] { minCX, minCZ, maxCX, maxCZ }, (a, b) -> new int[] {
                    Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3]) });
        }
        catch (RuntimeException e)
        {
            // an unparseable name is not worth failing a seed over; it only means that region is not auto-swept.
            LoggingHandler.sulog.warn("[PlanetSeeder] could not derive chunk bounds from region file {}", fileName);
        }
    }

    /**
     * Drains the chunk rectangles seeded this boot, so the caller can sweep them exactly once.
     *
     * @return dimension to {@code {minChunkX, minChunkZ, maxChunkX, maxChunkZ}}, empty on every boot after the first
     */
    public static java.util.Map<ResourceKey<Level>, int[]> drainFreshlySeededBounds()
    {
        java.util.Map<ResourceKey<Level>, int[]> copy = new java.util.HashMap<>(FRESH_REGION_BOUNDS);
        FRESH_REGION_BOUNDS.clear();
        return copy;
    }

    /**
     * Copies one shipped region file into {@code regionDir} only when it is absent. Returns true when a
     * new file was written, false when an existing file was kept or the copy was rejected by the size
     * gate. Never overwrites an existing region.
     */
    private static boolean copyIfAbsent(String resourceName, String fileName, Path regionDir)
    {
        Path dest = regionDir.resolve(fileName);

        // LOAD BEARING: never overwrite. A present file may hold player-built chunks.
        if (Files.exists(dest))
            return false;

        String resource = RESOURCE_ROOT + "/" + resourceName + "/" + fileName;
        URL url = PlanetRegionSeeder.class.getResource(resource);
        if (url == null)
        {
            LoggingHandler.sulog.warn("[PlanetSeeder] {}: shipped region resource missing from jar: {}", resourceName, resource);
            return false;
        }

        long expected = -1L;
        try
        {
            URLConnection conn = url.openConnection();
            conn.setUseCaches(false);
            expected = conn.getContentLengthLong();
        }
        catch (Throwable ignored)
        {
            // expected length is a best-effort strengthening of the gate; the byte floor still applies.
        }

        // copy into a temp file in the SAME directory so the final atomic move stays on one filesystem
        // and a partial write never appears as a real region file.
        Path tmp = regionDir.resolve(fileName + ".seedtmp");
        try
        {
            long written;
            try (InputStream in = PlanetRegionSeeder.class.getResourceAsStream(resource))
            {
                if (in == null)
                {
                    LoggingHandler.sulog.warn("[PlanetSeeder] {}: could not open region resource stream: {}", resourceName, resource);
                    return false;
                }
                written = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            // size gate: reject a truncated or partial copy instead of leaving a corrupt region.
            if (written < MIN_VALID_REGION_BYTES || (expected >= 0 && written != expected))
            {
                LoggingHandler.sulog.warn("[PlanetSeeder] {}: rejected truncated copy of {} ({} bytes, expected {}); discarding",
                    resourceName, fileName, written, expected >= 0 ? String.valueOf(expected) : "unknown");
                Files.deleteIfExists(tmp);
                return false;
            }

            moveIntoPlace(tmp, dest);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[PlanetSeeder] {}: failed to seed {}: {}", resourceName, fileName, t.toString());
            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (Throwable ignored)
            {
            }
            return false;
        }
    }

    // atomic move keeps the region file from ever being observed half-written. ATOMIC_MOVE also refuses
    // to clobber an existing target, reinforcing copy-if-absent; fall back to a plain move only when the
    // filesystem does not support atomic moves.
    private static void moveIntoPlace(Path tmp, Path dest) throws java.io.IOException
    {
        try
        {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(tmp, dest);
        }
    }
}
