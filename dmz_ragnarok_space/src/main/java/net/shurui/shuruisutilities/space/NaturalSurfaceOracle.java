package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Answers "was this block originally generated?" for a stamped planet's surface, so the planet-destroy salvage
 * ({@link PlanetSalvage}) can recover only what a guild actually BUILT and stored, never the wholesale natural terrain
 * the {@link SurfaceStamp} laid down. It reconstructs the deterministic natural surface from the planet id and the
 * geometry the terrain was stamped at, and exposes three cheap position tests the salvage scan applies per block.
 *
 * <p>Constructed from {@code (server, planetId)} at the moment of destruction, BEFORE the stamped geometry is cleared,
 * so it captures the exact size, theme and the full stamp snapshot the ground was built with. It then drives the SAME
 * shared helpers in {@link SurfaceStamp} the stamp itself uses ({@link SurfaceStamp.SurfaceParams} and the static
 * basin/vegetation/structure resolvers), so there is one implementation of the terrain maths, not a second copy that
 * merely agrees today.
 *
 * <h2>Three tests</h2>
 * <ul>
 *   <li>{@link #expectedTerrain} returns the block the stamp would have placed at a column offset and height (the solid
 *       surface/subsurface/deep stack, or a basin's carved floor and its water fill), or null where the natural surface
 *       is air/void. A world block that EQUALS this is originally generated; one that differs was placed by a player.</li>
 *   <li>{@link #inVegetationFootprint} and {@link #inStructureFootprint} cover the vegetation, rock decor, huts and
 *       villages the stamp dressed on TOP of the terrain. Those blocks sit above the natural cap, so a pure terrain diff
 *       would wrongly flag them as player-placed; excluding their footprints keeps natural dressing out of the salvage.
 *       They reuse the stamp's exact site occupancy/jitter/placement gates, so a footprint marks exactly (and a little
 *       around) where the stamp really placed something.</li>
 * </ul>
 *
 * <h2>Pre-snapshot planets</h2>
 * A planet stamped by a build that predates the persisted stamp snapshot has only its size and theme stored. The oracle
 * then falls back to the LIVE config for the rest (deep depth, sea level, the frequencies and enable flags). If the
 * operator changed those since that stamp, the recompute can diverge from the ground: the terrain diff then flags a few
 * natural blocks as builds, or misses a few. Neither deletes anything (salvage is a read-only copy), so this is an
 * acceptable best effort. {@link #hadSnapshot()} reports whether the exact snapshot was available.
 *
 * <p>Not thread-safe: it holds reusable scratch arrays and is meant to be driven from the server thread (the salvage
 * scan runs there), one query at a time.
 */
public final class NaturalSurfaceOracle
{
    private final SurfaceStamp.SurfaceParams params;
    private final boolean hadSnapshot;
    // reusable scratch for the basin lookups inside every query, and a separate out array the footprint tests resolve a
    // site into (kept distinct so the basin lookup inside a site resolution never clobbers the site result).
    private final int[] scratch = new int[4];
    private final int[] out = new int[4];

    public NaturalSurfaceOracle(MinecraftServer server, String planetId)
    {
        long seed = GeneratedPlanets.hashOf(planetId + "#terrain");
        int half = GeneratedPlanetClaims.stampedSizeForId(server, planetId) / 2;
        int baseY = (int) Math.floor(SurfaceDimension.SURFACE_Y);
        SurfaceStamp.Theme theme = GeneratedPlanetClaims.stampedThemeForId(server, planetId);
        GeneratedPlanetClaims.StampParams sp = GeneratedPlanetClaims.stampedParamsForId(server, planetId);

        int deepDepth;
        int seaLevelOffset;
        double basinFreq;
        double vegDensity;
        double villageFreq;
        double hutFreq;
        boolean waterOn;
        boolean vegOn;
        boolean structOn;
        int generatorVersion;
        if (sp != null)
        {
            deepDepth = sp.deepDepth;
            seaLevelOffset = sp.seaLevelOffset;
            basinFreq = sp.basinFrequency;
            vegDensity = sp.vegetationDensity;
            villageFreq = sp.villageFrequency;
            hutFreq = sp.hutFrequency;
            waterOn = sp.waterEnabled;
            vegOn = sp.vegetationEnabled;
            structOn = sp.structuresEnabled;
            // 0 (a build predating the field) means the legacy generator, so an old planet's salvage reads its original
            // disc terrain.
            generatorVersion = sp.generatorVersion <= 0 ? SurfaceStamp.GEN_VERSION_LEGACY : sp.generatorVersion;
        }
        else
        {
            // no persisted snapshot (stamped by an older build): best-effort recompute from the live config, and the
            // legacy generator, since only pre-B4 builds stamped without a snapshot.
            deepDepth = PlanetSpawnModule.surfaceColumnDepth();
            seaLevelOffset = SurfaceStamp.seaLevelOffset();
            basinFreq = SurfaceStamp.basinFrequency();
            vegDensity = SurfaceStamp.vegetationDensity();
            villageFreq = SurfaceStamp.villageFrequency();
            hutFreq = SurfaceStamp.hutFrequency();
            waterOn = SurfaceStamp.waterEnabled();
            vegOn = SurfaceStamp.vegetationEnabled();
            structOn = SurfaceStamp.structuresEnabled();
            generatorVersion = SurfaceStamp.GEN_VERSION_LEGACY;
        }

        this.params = new SurfaceStamp.SurfaceParams(seed, half, baseY, theme, deepDepth, waterOn, seaLevelOffset,
                basinFreq, vegOn, vegDensity, structOn, villageFreq, hutFreq, generatorVersion);
        this.hadSnapshot = sp != null;
    }

    /** The stamped half-side (radius) of the planet's disc, so the caller can bound its column sweep to it. */
    public int half()
    {
        return params.half;
    }

    /**
     * The half-extent actually stamped, including the v3 wrap margin (equals {@link #half()} on v1/v2). The block-identity
     * self-test sweeps to this so it checks the margin columns too; the salvage scan bounds to {@link #half()} instead,
     * because a player can only build inside the real square (the margin is protected).
     */
    public int stampExtent()
    {
        return params.stampExtent;
    }

    /** The v3 sea surface Y (only meaningful when {@link #hasSea()}), for the self-test's dry-landing and sea checks. */
    public int seaY()
    {
        return params.seaY;
    }

    /** Whether this planet floods a v3 sea (v3 generator, a sea-bearing theme, water on). */
    public boolean hasSea()
    {
        return params.hasSea;
    }

    /** The lowest solid block Y every column bottoms out on (the bedrock floor), so the self-test can bound its scan. */
    public int bottomY()
    {
        return params.bottomY;
    }

    /** Whether the exact stamp snapshot was available (false = the live-config fallback was used). */
    public boolean hadSnapshot()
    {
        return hadSnapshot;
    }

    /** Whether the column at offset (dx, dz) is inside the disc and its wobbled rim, so the scan can skip void columns. */
    public boolean columnInside(int dx, int dz)
    {
        return SurfaceStamp.columnInside(params, dx, dz);
    }

    /** The block the stamp would have placed at (dx, dz, y), or null where the natural surface is air/void. */
    public BlockState expectedTerrain(int dx, int dz, int y)
    {
        return SurfaceStamp.expectedTerrain(params, dx, dz, y, scratch);
    }

    /** Whether (dx, dz, y) is inside the footprint of natural vegetation or rock decor the stamp placed. */
    public boolean inVegetationFootprint(int dx, int dz, int y)
    {
        return SurfaceStamp.inVegetationFootprint(params, dx, dz, y, out, scratch);
    }

    /** Whether (dx, dz, y) is inside the footprint of a natural hut or village (including its flattened pad). */
    public boolean inStructureFootprint(int dx, int dz, int y)
    {
        return SurfaceStamp.inStructureFootprint(params, dx, dz, y, out, scratch);
    }
}
