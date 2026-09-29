package net.shurui.shuruisutilities.client.dball;

import java.util.function.IntFunction;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The per-set look for the DMZ ball sets we transform: the flat shell colour, the star pip tint, and the shell radius
 * and centre height (in blocks) taken from each set's own geo bounds so the drawn sphere sits exactly where the old
 * geo did.
 *
 * <p>The radius is the ball's TRUE drawn half-extent, taken from the geo bounds INCLUDING each cube's {@code inflate}
 * (GeckoLib honours inflate at render time: a cube spans {@code (origin - inflate) .. (origin + size + inflate)}), so the
 * sphere is the same size as the faceted cube body of the same ball. Two sets inflate their geo and so draw wider than the
 * raw origin/size span: namek (inflate 2) and super (inflate 8); the rest have no inflate and their radius is the plain
 * span.
 *
 * <ul>
 *   <li>super uses {@code dball_super4x.geo.json}, inflate 8: raw x/z span +-15, but INFLATED x/z +-23, y 0..45, so
 *       radius 23/16 and centre 22.5/16 (inflate is symmetric, so it does not move the centre).</li>
 *   <li>blackstar uses DMZ's {@code dball.geo.json}, no inflate: x/z span +-3.75, y 0..6.75, so radius 3.75/16, centre
 *       3.375/16.</li>
 *   <li>cerulean uses {@code dball_cerulean_half.geo.json}, no inflate: x/z span +-1.875, y 0..3.375, so radius 1.875/16.</li>
 *   <li>earth uses DMZ's {@code dball.geo.json}, no inflate: x/z span +-3.75, y 0..6.75, so radius 3.75/16, centre 3.375/16.</li>
 *   <li>namek uses DMZ's {@code dballnamek.geo.json}, inflate 2: raw x/z span +-3.75, but INFLATED x/z +-5.75, y 0..11.25,
 *       so radius 5.75/16 and centre 5.625/16 (its geo sits higher than earth's, so the sphere centre is raised to match;
 *       the inflate widens it but does not move the centre).</li>
 * </ul>
 *
 * The base colours are the flat ball colour each texture is painted in; the pips are red for the Earth-style sets and
 * dark for Black Star, matching each ball's painted stars. earth and namek both read the orange (0xFF9E00) their placed
 * geo texture {@code block/custom/dballblock} / {@code dballnamekblock} is painted in (the two geo textures are the same
 * orange image), with red pips.
 */
public final class DragonBallSets
{
    private DragonBallSets()
    {
    }

    /**
     * @param textureHasStars whether the ball's placed BLOCK texture still carries painted stars. This is now purely
     *                        informational: BOTH styles always draw the inner star billboard for every set, so a set
     *                        whose block texture ALSO has painted stars (earth, namek) shows the star twice in CUBE
     *                        style until a starless {@code cubeTexture} is supplied below. super, blackstar and cerulean
     *                        already had their block-texture stars stripped, so they read {@code false} and never double.
     * @param cubeTexture     the SINGLE place to override which texture CUBE style samples for this set, mapped by star
     *                        count (1..7) so per-star sets resolve correctly. {@code null} means "use DMZ's own per-star
     *                        texture resolution" (the current behaviour). To remove the doubled star on a painted-texture
     *                        set, an asset task supplies a starless variant and points this at it here; no renderer
     *                        change is needed. SPHERE style ignores this entirely (it never samples the block texture).
     */
    /**
     * @param cubeRimBoxes the set's geo box table ({@link DragonBallCubeGeo}), the source of the CUBE-style rim's SINGLE
     *                     convex hull. Every set uses the same one-hull rim now (see {@link DragonBallShell#renderHullRim}
     *                     and {@link DragonBallHull}); there is no per-set rim-shape flag any more.
     */
    public record Look(float radius, float centerY, int baseRgb, float[] pip, boolean textureHasStars,
                       IntFunction<ResourceLocation> cubeTexture, float[][] cubeRimBoxes)
    {
    }

    // cubeTexture is null on super, blackstar and cerulean: their block textures already had painted stars stripped, so
    // CUBE style samples DMZ's own per-star texture and never doubles. earth and namek DO carry painted surface stars, so
    // their cubeTexture points CUBE style at SU's starless variants, leaving only the inner billboard. corrupted stays
    // null on purpose: it has no painted stars to remove (only crack detail), so it keeps DMZ's texture plus the billboard.
    // getBallType().getStars() is 1..7 (DMZ's DragonBallType ONE_STAR..SEVEN_STAR carry stars 1..7), matching the
    // dballblock_earth_starless1..7 / dballblock_namek_starless1..7 file numbering, so the star index maps straight through.
    // super: radius 23/16 is the INFLATED half-extent (inflate 8), matching the faceted cube body, not the raw 15/16
    // span. Its rim is the single convex hull of DragonBallCubeGeo.SUPER, the same one-outline treatment every set uses.
    private static final Look SUPER = new Look(23.0F / 16.0F, 22.5F / 16.0F, 0xFFDE26, DragonBallShell.PIP_RED, false, null,
            DragonBallCubeGeo.SUPER);
    private static final Look BLACKSTAR = new Look(3.75F / 16.0F, 3.375F / 16.0F, 0xFF9D00, DragonBallShell.PIP_DARK, false, null,
            DragonBallCubeGeo.EARTH_BLACKSTAR);
    private static final Look CERULEAN = new Look(1.875F / 16.0F, 1.6875F / 16.0F, 0xFF9000, DragonBallShell.PIP_RED, false, null,
            DragonBallCubeGeo.CERULEAN);
    private static final Look EARTH = new Look(3.75F / 16.0F, 3.375F / 16.0F, 0xFF9E00, DragonBallShell.PIP_RED, true,
            star -> new ResourceLocation(ShuruisUtilities.MODID, "textures/block/custom/dballblock_earth_starless" + star + ".png"),
            DragonBallCubeGeo.EARTH_BLACKSTAR);
    // namek: radius 5.75/16 is the INFLATED half-extent (inflate 2), matching its faceted cube body, not the raw 3.75/16
    // span; centre 5.625/16 is unchanged because the inflate is symmetric.
    private static final Look NAMEK = new Look(5.75F / 16.0F, 5.625F / 16.0F, 0xFF9E00, DragonBallShell.PIP_RED, true,
            star -> new ResourceLocation(ShuruisUtilities.MODID, "textures/block/custom/dballblock_namek_starless" + star + ".png"),
            DragonBallCubeGeo.NAMEK);

    /**
     * The shell look for a DMZ ball set id, or {@code null} for a set we do not transform (anything else a third addon
     * adds), which the renderer draws with DMZ's own opaque geo instead.
     */
    public static Look lookFor(String ballSetId)
    {
        if (ballSetId == null)
        {
            return null;
        }
        return switch (ballSetId)
        {
            case "super" -> SUPER;
            case "blackstar" -> BLACKSTAR;
            case "cerulean" -> CERULEAN;
            case "earth" -> EARTH;
            case "namek" -> NAMEK;
            default -> null;
        };
    }
}
