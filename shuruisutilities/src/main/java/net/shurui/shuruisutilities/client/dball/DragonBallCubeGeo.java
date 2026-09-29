package net.shurui.shuruisutilities.client.dball;

/**
 * The per-set BOX TABLES the CUBE render style builds its outer rim from, one axis-aligned box per cube of each set's own
 * GeckoLib geo. In CUBE style the body is DMZ's / SU's real faceted geo drawn through {@code defaultRender}, so the rim
 * must follow THAT geometry, not the sphere hull {@link DragonBallShell#renderRim} draws (a round outline around a cube
 * body was the reported bug). {@link DragonBallHull} builds the SINGLE convex hull of a set's corner points from these
 * tables, and {@link DragonBallShell#renderHullRim} draws it as one silhouette-following outline. This is the twin of
 * {@code SpaceBodyRenderer.SUPER_GEO}, which is hulled the same way for the space super rim.
 *
 * <h3>How these tables were derived</h3>
 * Each row is {@code {x0,y0,z0, x1,y1,z1}} in BLOCK units, in the block-local frame GeckoLib renders a block geo in
 * ({@code translate(0.5,0,0.5)} then the cubes at their raw model coordinates). For one cube of a geo that is
 * {@code origin=[ox,oy,oz]}, {@code size=[sx,sy,sz]}, {@code inflate=f}, GeckoLib emits vertices from
 * {@code (origin - f)/16} to {@code (origin + size + f)/16} (see {@code BakedModelFactory.VertexSet}), so a row is
 * {@code x0=(ox-f)/16 .. x1=(ox+sx+f)/16} and likewise for y and z. The inflate is INCLUDED because GeckoLib honours it,
 * and for namek (inflate 2) and super (inflate 8) it makes the drawn body markedly wider than the set's sphere radius,
 * so a hull built from the sphere radius would sit INSIDE the cube body and never show. GeckoLib also mirrors x
 * ({@code -(ox+sx)/16}); every set's cubes are x-symmetric as a set, so the mirror leaves the union of boxes unchanged
 * and the rows keep the unmirrored coordinates.
 *
 * <p>The tables were generated straight from the geo files (not hand-transcribed), so they trace back cube-for-cube to:
 * <ul>
 *   <li>{@link #EARTH_BLACKSTAR}: {@code dragonminez:geo/block/dball.geo.json} (12 cubes, no inflate). Used by both the
 *       earth and blackstar sets, which share DMZ's dball geo. Bounds x/z +-3.75, y 0..6.75 (matches the verified
 *       earth/blackstar bounds).</li>
 *   <li>{@link #NAMEK}: {@code dragonminez:geo/block/dballnamek.geo.json} (13 cubes, inflate 2). Bounds x/z +-5.75,
 *       y 0..11.25 (the inflate widens it past the +-3.75 sphere radius; centre y 5.625 is unchanged because the inflate
 *       is symmetric).</li>
 *   <li>{@link #SUPER}: {@code shuruisutilities:geo/block/dball_super4x.geo.json} (13 cubes, inflate 8). Bounds x/z +-23,
 *       y 0..45 (centre y 22.5). Same source geo as SUPER_GEO in the space renderer, but kept at true block size here
 *       rather than normalised to a half-extent of 1.</li>
 *   <li>{@link #CERULEAN}: {@code shuruisutilities:geo/block/dball_cerulean_half.geo.json} (12 cubes, no inflate).
 *       Bounds x/z +-1.875, y 0..3.375. This half-set only ever has stars 1 and 2; the hull is the same for any star.</li>
 *   <li>{@link #CORRUPTED}: {@code shuruisutilities:geo/block/corrupted_dball.geo.json} (12 cubes, no inflate). Same
 *       bounds as earth (x/z +-3.75, y 0..6.75); the geo is DMZ's dball shape with only crack detail added.</li>
 * </ul>
 * A static table is used rather than parsing the geo at runtime because it is deterministic, needs no resource-manager
 * timing, and exactly follows the {@code SUPER_GEO} precedent; the header above keeps each table traceable to its file.
 */
public final class DragonBallCubeGeo
{
    private DragonBallCubeGeo()
    {
    }

    // dragonminez:geo/block/dball.geo.json (12 cubes, no inflate). x/z +-3.75, y 0..6.75. Shared by earth and blackstar.
    public static final float[][] EARTH_BLACKSTAR = {
            {-0.171875F, 0.015625F, -0.171875F, 0.171875F, 0.062500F, 0.171875F},
            {-0.171875F, 0.359375F, -0.171875F, 0.171875F, 0.390625F, 0.171875F},
            {-0.112500F, 0.000000F, -0.112500F, 0.112500F, 0.015625F, 0.112500F},
            {-0.115625F, 0.390625F, -0.115625F, 0.115625F, 0.421875F, 0.115625F},
            {-0.115625F, 0.106250F, -0.234375F, 0.112500F, 0.296875F, -0.203125F},
            {-0.171875F, 0.046875F, 0.156250F, 0.171875F, 0.359375F, 0.203125F},
            {-0.109375F, 0.093750F, 0.203125F, 0.109375F, 0.312500F, 0.234375F},
            {-0.171875F, 0.046875F, -0.203125F, 0.171875F, 0.359375F, -0.156250F},
            {0.140625F, 0.046875F, -0.171875F, 0.203125F, 0.359375F, 0.171875F},
            {-0.203125F, 0.046875F, -0.171875F, -0.140625F, 0.359375F, 0.171875F},
            {-0.234375F, 0.093750F, -0.109375F, -0.203125F, 0.312500F, 0.109375F},
            {0.203125F, 0.093750F, -0.109375F, 0.234375F, 0.312500F, 0.109375F}
    };

    // dragonminez:geo/block/dballnamek.geo.json (13 cubes, inflate 2). x/z +-5.75, y 0..11.25, centre y 5.625.
    public static final float[][] NAMEK = {
            {-0.296875F, 0.015625F, -0.296875F, 0.296875F, 0.312500F, 0.296875F},
            {-0.296875F, 0.359375F, -0.296875F, 0.296875F, 0.640625F, 0.296875F},
            {-0.237500F, 0.000000F, -0.237500F, 0.237500F, 0.265625F, 0.237500F},
            {-0.240625F, 0.390625F, -0.240625F, 0.240625F, 0.671875F, 0.240625F},
            {-0.178125F, 0.453125F, -0.209375F, 0.178125F, 0.703125F, 0.209375F},
            {-0.240625F, 0.106250F, -0.359375F, 0.237500F, 0.546875F, -0.078125F},
            {-0.296875F, 0.046875F, 0.031250F, 0.296875F, 0.609375F, 0.328125F},
            {-0.234375F, 0.093750F, 0.078125F, 0.234375F, 0.562500F, 0.359375F},
            {-0.296875F, 0.046875F, -0.328125F, 0.296875F, 0.609375F, -0.031250F},
            {0.015625F, 0.046875F, -0.296875F, 0.328125F, 0.609375F, 0.296875F},
            {-0.328125F, 0.046875F, -0.296875F, -0.015625F, 0.609375F, 0.296875F},
            {-0.359375F, 0.093750F, -0.234375F, -0.078125F, 0.562500F, 0.234375F},
            {0.078125F, 0.093750F, -0.234375F, 0.359375F, 0.562500F, 0.234375F}
    };

    // shuruisutilities:geo/block/dball_super4x.geo.json (13 cubes, inflate 8). x/z +-23, y 0..45, centre y 22.5.
    public static final float[][] SUPER = {
            {-1.187500F, 0.062500F, -1.187500F, 1.187500F, 1.250000F, 1.187500F},
            {-1.187500F, 1.437500F, -1.187500F, 1.187500F, 2.562500F, 1.187500F},
            {-0.950000F, 0.000000F, -0.950000F, 0.950000F, 1.062500F, 0.950000F},
            {-0.962500F, 1.562500F, -0.962500F, 0.962500F, 2.687500F, 0.962500F},
            {-0.712500F, 1.812500F, -0.837500F, 0.712500F, 2.812500F, 0.837500F},
            {-0.962500F, 0.425000F, -1.437500F, 0.950000F, 2.187500F, -0.312500F},
            {-1.187500F, 0.187500F, 0.125000F, 1.187500F, 2.437500F, 1.312500F},
            {-0.937500F, 0.375000F, 0.312500F, 0.937500F, 2.250000F, 1.437500F},
            {-1.187500F, 0.187500F, -1.312500F, 1.187500F, 2.437500F, -0.125000F},
            {0.062500F, 0.187500F, -1.187500F, 1.312500F, 2.437500F, 1.187500F},
            {-1.312500F, 0.187500F, -1.187500F, -0.062500F, 2.437500F, 1.187500F},
            {-1.437500F, 0.375000F, -0.937500F, -0.312500F, 2.250000F, 0.937500F},
            {0.312500F, 0.375000F, -0.937500F, 1.437500F, 2.250000F, 0.937500F}
    };

    // shuruisutilities:geo/block/dball_cerulean_half.geo.json (12 cubes, no inflate). x/z +-1.875, y 0..3.375.
    public static final float[][] CERULEAN = {
            {-0.085938F, 0.007812F, -0.085938F, 0.085938F, 0.031250F, 0.085938F},
            {-0.085938F, 0.179688F, -0.085938F, 0.085938F, 0.195312F, 0.085938F},
            {-0.056250F, 0.000000F, -0.056250F, 0.056250F, 0.007812F, 0.056250F},
            {-0.057813F, 0.195312F, -0.057813F, 0.057813F, 0.210938F, 0.057813F},
            {-0.057813F, 0.053125F, -0.117188F, 0.056250F, 0.148438F, -0.101562F},
            {-0.085938F, 0.023438F, 0.078125F, 0.085938F, 0.179688F, 0.101562F},
            {-0.054688F, 0.046875F, 0.101562F, 0.054688F, 0.156250F, 0.117188F},
            {-0.085938F, 0.023438F, -0.101562F, 0.085938F, 0.179688F, -0.078125F},
            {0.070312F, 0.023438F, -0.085938F, 0.101562F, 0.179688F, 0.085938F},
            {-0.101562F, 0.023438F, -0.085938F, -0.070312F, 0.179688F, 0.085938F},
            {-0.117188F, 0.046875F, -0.054688F, -0.101562F, 0.156250F, 0.054688F},
            {0.101562F, 0.046875F, -0.054688F, 0.117188F, 0.156250F, 0.054688F}
    };

    // shuruisutilities:geo/block/corrupted_dball.geo.json (12 cubes, no inflate). x/z +-3.75, y 0..6.75.
    public static final float[][] CORRUPTED = {
            {-0.171875F, 0.015625F, -0.171875F, 0.171875F, 0.062500F, 0.171875F},
            {-0.171875F, 0.359375F, -0.171875F, 0.171875F, 0.390625F, 0.171875F},
            {-0.112500F, 0.000000F, -0.112500F, 0.112500F, 0.015625F, 0.112500F},
            {-0.115625F, 0.390625F, -0.115625F, 0.115625F, 0.421875F, 0.115625F},
            {-0.115625F, 0.106250F, -0.234375F, 0.112500F, 0.296875F, -0.203125F},
            {-0.171875F, 0.046875F, 0.156250F, 0.171875F, 0.359375F, 0.203125F},
            {-0.109375F, 0.093750F, 0.203125F, 0.109375F, 0.312500F, 0.234375F},
            {-0.171875F, 0.046875F, -0.203125F, 0.171875F, 0.359375F, -0.156250F},
            {0.140625F, 0.046875F, -0.171875F, 0.203125F, 0.359375F, 0.171875F},
            {-0.203125F, 0.046875F, -0.171875F, -0.140625F, 0.359375F, 0.171875F},
            {-0.234375F, 0.093750F, -0.109375F, -0.203125F, 0.312500F, 0.109375F},
            {0.203125F, 0.093750F, -0.109375F, 0.234375F, 0.312500F, 0.109375F}
    };
}
