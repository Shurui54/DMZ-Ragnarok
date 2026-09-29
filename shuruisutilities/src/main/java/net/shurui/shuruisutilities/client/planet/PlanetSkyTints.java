package net.shurui.shuruisutilities.client.planet;

import org.joml.Vector3f;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * The per-planet SKY table, a client-only companion to {@link NamekBlockTints}. The shared generated-planet surface
 * dimension ({@code shuruisutilities:planet_surface}) paints a fully procedural sky (see
 * {@link net.shurui.shuruisutilities.client.space.SpaceDimensionEffects}) and never reads the biome's {@code sky_color}
 * field, so a planet whose sky should differ from the default black void has to be singled out in code. It cannot be
 * singled out by dimension or dimension type either, because every generated planet shares one of each; the only thing
 * that varies at runtime is which biome the player is standing in. This table maps a biome id to the sky it should show,
 * exactly parallel to how {@link NamekBlockTints} maps a biome id to a block tint.
 *
 * <p>Two entries today: Beerus reads as a bright violet-skied world, Vegeta as a bright green-skied one. Every other
 * biome (the generic {@code planet_surface} of a procedural planet, and anything off the surface dimension) returns
 * null, which the effects class treats as "keep the black-void, ambient-lit default", so no other planet is touched.
 *
 * <p>The {@link Sky#fog} is the colour the framebuffer clears to (the sky backdrop) and the colour the dense surface fog
 * tints the horizon, so together they make the whole sky read that colour under the additive star field. The
 * {@link Sky#daylight}/{@link Sky#daylightMix} lift every lightmap cell toward a bright daytime white so the surface is
 * lit like an overcast noon instead of the dim ambient floor the shared dimension type otherwise imposes. Retune a
 * planet's sky by editing ONE entry here.
 */
public final class PlanetSkyTints
{
    /** Immutable per-planet sky: the backdrop/horizon fog colour, and the daytime lightmap target and how far to lift toward it. */
    public static final class Sky
    {
        public final Vec3 fog;
        public final Vector3f daylight;
        public final float daylightMix;

        Sky(Vec3 fog, Vector3f daylight, float daylightMix)
        {
            this.fog = fog;
            this.daylight = daylight;
            this.daylightMix = daylightMix;
        }
    }

    // Beerus: a deep saturated violet backdrop and horizon (rather than a pale lilac, so it stays clearly purple once the
    // additive star field draws over it), lit like a faintly violet noon. These are the exact values the old cell-gated
    // Beerus path used, moved here unchanged.
    private static final Sky BEERUS =
            new Sky(new Vec3(0.40D, 0.13D, 0.52D), new Vector3f(1.0F, 0.97F, 1.0F), 0.85F);

    // Vegeta: a bright green daytime sky over a green-lit surface, matching the green grass and foliage tints. A muted
    // green rather than a neon one so the star field still reads over it.
    private static final Sky VEGETA =
            new Sky(new Vec3(0.28D, 0.50D, 0.26D), new Vector3f(0.96F, 1.0F, 0.92F), 0.80F);

    // Biome namespace to tint. Moved to dmz_ragnarok with the biomes in the dimension/biome rename stage, so this
    // gates on the live (migrated) biome id. A pre-migration world still carrying old shuruisutilities biomes would
    // fall through to the default sky until it is migrated with world-tools/ns-rename.
    private static final String NS = "dmz_ragnarok";

    private PlanetSkyTints()
    {
    }

    // The sky for a biome id, or null when the biome has no custom sky (keep the default black-void look). null or
    // foreign-namespace biomes fall through to null.
    public static Sky sky(ResourceLocation biomeId)
    {
        if (biomeId == null || !NS.equals(biomeId.getNamespace()))
        {
            return null;
        }
        String path = biomeId.getPath();
        if ("beerus".equals(path))
        {
            return BEERUS;
        }
        if (path.equals("vegeta") || path.startsWith("vegeta_"))
        {
            return VEGETA;
        }
        return null;
    }

    // The Beerus sky, for the fail-soft cell fallback in SpaceDimensionEffects: Beerus's authored region files fold to
    // surface cell (0, 0), so that cell is a stable stand-in for "on Beerus" even if its baked biome id is not
    // shuruisutilities:beerus.
    public static Sky beerus()
    {
        return BEERUS;
    }
}
