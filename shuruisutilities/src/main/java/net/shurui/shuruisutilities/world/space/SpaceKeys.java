package net.shurui.shuruisutilities.world.space;

import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The single core home for the space feature's dimension identity and its cross-tree constants.
 *
 * <p>These used to live on {@code SpaceDimension} / {@code SurfaceDimension} in the space package and on
 * {@code PlanetClash}. When Space becomes its own module those classes move out of core, but plenty of core-side code
 * (shard routing, respawn placement, the DMZ radar and dragon-ball mixins, the CustomNPCs warning filter, the region
 * seeder) only needs the raw ids, the {@code isSpace} / {@code isSurface} tests, the defender-wave render marker or the
 * inhabited-planet set. Centralising exactly those here lets that core code depend on core, not on the space package.
 *
 * <p>The values are byte-for-byte the same ones the space classes always used, so existing worlds, level.dat entries
 * and persisted markers are unaffected. {@code SpaceDimension} / {@code SurfaceDimension} / {@code PlanetClash} keep
 * their public constants as aliases of these, so nothing that already referenced them changes.
 */
public final class SpaceKeys
{
    private SpaceKeys()
    {
    }

    // --- Space dimension (a datapack void world, dimension[_type]/space.json + worldgen/biome/space.json). --------
    // In the dmz_ragnarok namespace since the dimension-rename stage; old shuruisutilities:space JSON stay as datapack
    // shims so pre-migration worlds still load. The id string is baked into level.dat, so it must never drift.
    public static final ResourceLocation SPACE_ID = new ResourceLocation("dmz_ragnarok", "space");

    public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, SPACE_ID);

    public static final ResourceKey<DimensionType> SPACE_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, SPACE_ID);

    // --- Shared generated-planet surface dimension (data/dmz_ragnarok/dimension[_type]/planet_surface.json). -------
    // Every generated planet maps to a deterministic cell in this ONE dimension; the cell geometry stays on
    // SurfaceDimension. Same namespace-rename history and same "id is baked into level.dat" invariant as space above.
    public static final ResourceLocation SURFACE_ID = new ResourceLocation("dmz_ragnarok", "planet_surface");

    public static final ResourceKey<Level> SURFACE = ResourceKey.create(Registries.DIMENSION, SURFACE_ID);

    public static final ResourceKey<DimensionType> SURFACE_TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE, SURFACE_ID);

    // --- Planet Vegeta: a standalone REAL planet dimension seeded from pre-generated region data. -----------------
    // Its key is built from the SAME namespace source as the surface dimension (not a modid-derived path), so the
    // dimension-rename stage moves both together. This is the target the region seeder copies Vegeta's .mca into.
    public static final ResourceLocation PLANET_VEGETA_ID = new ResourceLocation(SURFACE_ID.getNamespace(), "planet_vegeta");

    public static final ResourceKey<Level> PLANET_VEGETA = ResourceKey.create(Registries.DIMENSION, PLANET_VEGETA_ID);

    // --- Beerus's planet: NOT a dimension of its own. -------------------------------------------------------------
    // The authored Beerus build RESIDES in the shared surface dimension (SURFACE), in surface cell (0,0), which the
    // travel module reserves from procedural stamping. Its region files ship under regions/beerus_planet/ and are
    // seeded INTO SURFACE. This id is kept for reference only (the resource folder name), it is not a live dimension.
    public static final ResourceLocation BEERUS_PLANET_ID = new ResourceLocation(SURFACE_ID.getNamespace(), "beerus_planet");

    public static boolean isSpace(Level level)
    {
        return level != null && level.dimension().equals(SPACE);
    }

    public static boolean isSurface(Level level)
    {
        return level != null && level.dimension().equals(SURFACE);
    }

    // --- The defending planet's answering-beam render marker. ------------------------------------------------------
    // A synced technique-id sentinel stamped on the planet-clash defender wave so the client renderer skips it (the
    // fiction is the PLANET resisting, so the answering beam must not be seen). The value is never resolved as a real
    // technique, so it is inert on the server and purely a client render marker. PlanetClash aliases this.
    public static final String DEFENDER_WAVE_MARKER = "su_planet_defender";

    // --- Inhabited (neutral-garrison) planet dimensions. -----------------------------------------------------------
    // Dimension ids of the REAL planet dimensions that own a town and so spawn a NEUTRAL saiyan garrison, as opposed
    // to the generated surface cells that spawn a HOSTILE one. Adding a planet here makes its garrison neutral.
    public static final Set<String> INHABITED_PLANETS = Set.of(
            "dmz_ragnarok:planet_vegeta");
}
