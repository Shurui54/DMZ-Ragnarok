package net.shurui.shuruisutilities.client.space;

import org.joml.Vector3f;

import net.shurui.shuruisutilities.space.SurfaceStamp;

/**
 * The per-THEME atmosphere table for the generated-planet surface sky. A planet's {@link SurfaceStamp.Theme} (the
 * material family its ground is built from, STONY / OVERWORLD / NAMEK / NETHER / END / KAIO / OTHERWORLD) picks the
 * colour of the sky dome, the fog and how readily stars show through the atmosphere. This is the sky counterpart to the
 * theme's ground palette in {@link SurfaceStamp}, so a green NAMEK world reads under a green sky, a NETHER world under a
 * smoky red one, and so on, without any per-planet data: the theme is the whole input.
 *
 * <p>Each {@link Sky} carries a DAY and a NIGHT colour for both the zenith (top of the dome) and the horizon, plus the
 * fog colour and a base star alpha. {@link PlanetSurfaceEffects} interpolates between day and night by its synthetic
 * day/night phase (the surface dimension is fixed_time, so the cycle is a client visual driven by the world game time,
 * never a change to any world data), and adds the base star alpha so a thin-atmosphere world (STONY, END) shows a
 * scatter of stars even in daylight while a thick one (OVERWORLD, NAMEK) does not.
 *
 * <p>Colours are dim-to-mid RGB on purpose: the star field is drawn additively over the dome, so an over-bright dome
 * would wash it out at night. Retune a theme's sky by editing ONE row here.
 */
public final class PlanetSkyPalette
{
    private PlanetSkyPalette()
    {
    }

    /** Immutable per-theme sky: day/night zenith and horizon colours, fog colour, and how bright the stars sit. */
    public static final class Sky
    {
        public final Vector3f dayZenith;
        public final Vector3f dayHorizon;
        public final Vector3f nightZenith;
        public final Vector3f nightHorizon;
        public final Vector3f fogDay;
        public final Vector3f fogNight;
        // 0..1 star alpha floor that shows even at full daylight (thin atmosphere). Night always reaches ~1 on top of it.
        public final float baseStarAlpha;
        // 0..1 how far to lift the surface lightmap toward daytime white at full day (matches PlanetSkyTints.daylightMix).
        public final float daylightMix;

        Sky(Vector3f dayZenith, Vector3f dayHorizon, Vector3f nightZenith, Vector3f nightHorizon,
            Vector3f fogDay, Vector3f fogNight, float baseStarAlpha, float daylightMix)
        {
            this.dayZenith = dayZenith;
            this.dayHorizon = dayHorizon;
            this.nightZenith = nightZenith;
            this.nightHorizon = nightHorizon;
            this.fogDay = fogDay;
            this.fogNight = fogNight;
            this.baseStarAlpha = baseStarAlpha;
            this.daylightMix = daylightMix;
        }
    }

    private static Vector3f rgb(float r, float g, float b)
    {
        return new Vector3f(r, g, b);
    }

    // STONY: bare airless rock, a thin dusty grey-blue atmosphere. Stars show plainly even by day (thin air).
    private static final Sky STONY = new Sky(
            rgb(0.18F, 0.20F, 0.28F), rgb(0.42F, 0.44F, 0.50F),
            rgb(0.02F, 0.02F, 0.05F), rgb(0.06F, 0.07F, 0.11F),
            rgb(0.30F, 0.32F, 0.38F), rgb(0.03F, 0.03F, 0.06F),
            0.45F, 0.55F);

    // OVERWORLD: an Earth-like blue day sky over a warm horizon, a thick atmosphere that hides the stars by day.
    private static final Sky OVERWORLD = new Sky(
            rgb(0.30F, 0.52F, 0.86F), rgb(0.66F, 0.78F, 0.92F),
            rgb(0.02F, 0.03F, 0.09F), rgb(0.05F, 0.07F, 0.16F),
            rgb(0.55F, 0.68F, 0.88F), rgb(0.03F, 0.04F, 0.10F),
            0.05F, 0.80F);

    // NAMEK: DMZ's green world, a green day sky matching its foliage. Thick air, so few daytime stars.
    private static final Sky NAMEK = new Sky(
            rgb(0.26F, 0.52F, 0.30F), rgb(0.52F, 0.74F, 0.48F),
            rgb(0.03F, 0.08F, 0.04F), rgb(0.06F, 0.14F, 0.07F),
            rgb(0.40F, 0.62F, 0.38F), rgb(0.04F, 0.09F, 0.05F),
            0.08F, 0.80F);

    // NETHER: a smoky, sullen red haze with almost no true day. Some embers of stars read through the murk.
    private static final Sky NETHER = new Sky(
            rgb(0.34F, 0.10F, 0.07F), rgb(0.56F, 0.20F, 0.12F),
            rgb(0.12F, 0.03F, 0.02F), rgb(0.22F, 0.06F, 0.04F),
            rgb(0.40F, 0.13F, 0.09F), rgb(0.10F, 0.03F, 0.02F),
            0.25F, 0.55F);

    // END: a deep void-violet sky, a very thin atmosphere so the star field reads strongly at all times.
    private static final Sky END = new Sky(
            rgb(0.14F, 0.10F, 0.20F), rgb(0.26F, 0.20F, 0.34F),
            rgb(0.03F, 0.02F, 0.06F), rgb(0.08F, 0.06F, 0.13F),
            rgb(0.18F, 0.13F, 0.24F), rgb(0.03F, 0.02F, 0.06F),
            0.55F, 0.45F);

    // KAIO: King Kai's sacred world, a bright clear cyan-green day. Thick clean air, so daytime stars are hidden.
    private static final Sky KAIO = new Sky(
            rgb(0.30F, 0.60F, 0.62F), rgb(0.62F, 0.82F, 0.80F),
            rgb(0.03F, 0.07F, 0.08F), rgb(0.07F, 0.14F, 0.15F),
            rgb(0.46F, 0.70F, 0.70F), rgb(0.04F, 0.08F, 0.09F),
            0.10F, 0.80F);

    // OTHERWORLD: DMZ's cloud plain, a pale warm-gold overcast. Never rolled onto a random planet, but a dungeon stamp
    // can select it, so it gets a sky too.
    private static final Sky OTHERWORLD = new Sky(
            rgb(0.60F, 0.56F, 0.40F), rgb(0.80F, 0.76F, 0.60F),
            rgb(0.10F, 0.09F, 0.07F), rgb(0.18F, 0.16F, 0.12F),
            rgb(0.62F, 0.58F, 0.44F), rgb(0.09F, 0.08F, 0.06F),
            0.08F, 0.75F);

    // A neutral default for any theme the table somehow does not name (defence in depth; every enum value is covered).
    private static final Sky DEFAULT = STONY;

    /** The sky for a surface theme. Never null. */
    public static Sky forTheme(SurfaceStamp.Theme theme)
    {
        if (theme == null)
        {
            return DEFAULT;
        }
        switch (theme)
        {
            case STONY: return STONY;
            case OVERWORLD: return OVERWORLD;
            case NAMEK: return NAMEK;
            case NETHER: return NETHER;
            case END: return END;
            case KAIO: return KAIO;
            case OTHERWORLD: return OTHERWORLD;
            default: return DEFAULT;
        }
    }
}
