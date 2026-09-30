package net.shurui.shuruisutilities.client.space;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.client.planet.PlanetSkyTints;
import net.shurui.shuruisutilities.space.SurfaceDimension;

/**
 * Client sky for the space dimension: a black void with a fully procedural deep-sky backdrop. There is no sun, no moon,
 * no clouds, no horizon band and no void-fog gradient, so the planet and asteroid bodies read as lit objects against
 * empty space.
 *
 * <p>EVERYTHING IS PROCEDURAL, NO SKYBOX TEXTURE, NO IMAGE OF ANY KIND. The whole thing is generated from a fixed set of
 * seed constants, so the sky is byte-identical for every player and every session. It is packed into exactly THREE
 * static {@link VertexBuffer}s and drawn back to front in three draw calls:
 * <ol>
 *   <li>DUST (the {@code nebulaBuffer}): the faint coloured backdrop, drawn first and under everything. Two things live
 *       here. First, {@link #NEBULA_COUNT} soft clouds whose sizes vary very widely, from small dense knots to huge
 *       diffuse washes, with alpha anti-correlated to size so the big ones stay faint and the small ones read as denser
 *       structure. They are deliberately allowed to overlap and a good fraction are pulled toward the galactic-band
 *       plane, so instead of reading as separate discs they build into larger cloudy regions and a mottled dust lane
 *       along the band. Second, {@link #BAND_DUST_COUNT} elongated dust patches whose long axis runs ALONG the band, the
 *       soft glow of unresolved stars in the plane of the galaxy. Each element is a camera-facing radial gradient (a
 *       coloured centre fading to a fully transparent rim) so it reads as a soft cloud rather than a hard disc. Additive
 *       blending only ever ADDS light, so there are no truly dark dust lanes here, only brighter and dimmer glow.</li>
 *   <li>GALAXIES (the {@code galaxyBuffer}): brighter, more defined objects drawn over the dust and under the stars.
 *       {@link #GALAXY_COUNT} elliptical smudges at every tilt from edge-on to face-on, sizes now spanning a wide range
 *       so a few are large enough to read as recognisable spiral-ish smudges while most stay small and distant; the
 *       large ones get a second brighter inner core fan so they read as a disc with a bulge. A warm minority are the
 *       orange of redshifted galaxies. Also folded into this buffer, because they are the same soft-fan geometry:
 *       {@link #BRIGHT_GLOW_COUNT} small blue-white glows that give the field a handful of hazy bright stars, and
 *       {@link #COMET_COUNT} static comets (a bright head with a tapering, fading tail). The comets do NOT move; they
 *       hang in the backdrop like everything else, which is honest scenery rather than an animated gimmick.</li>
 *   <li>STARS (the {@code starBuffer}): the main event and the brightest layer, drawn last so it sits on top. Three
 *       populations share this buffer, all emitted as small camera-facing quads the way vanilla {@code LevelRenderer}
 *       builds its own star buffer. First the general field: {@link #STAR_COUNT} points scattered on the sphere, now
 *       carrying per-vertex spectral colour (blue-white through white, yellow and orange to a few red) and a wide,
 *       dim-skewed brightness range with a small {@link #BRIGHT_STAR_FRACTION} of noticeably brighter, larger standouts.
 *       Second the galactic band: {@link #BAND_STAR_COUNT} dimmer, smaller stars sampled with a gaussian falloff in
 *       angular distance from the band plane, so they thicken into a broad, slightly irregular Milky-Way-like river of
 *       haze across the sky. Third the clusters: {@link #STAR_CLUSTER_COUNT} tight globular-like knots, each a gaussian
 *       swarm of faint stars denser toward its centre.</li>
 * </ol>
 *
 * <p>THE GALACTIC BAND. The single most "this is space" element. It lies on the great circle whose plane normal is
 * {@code (1, 2, 1)} normalised (see {@link #BAND_NORMAL_X}), a plane tilted so the band sweeps across the sky at an
 * angle rather than sitting level with any axis. Both the band stars and the band dust are placed by the same normal, so
 * the denser stars and the dust lanes coincide the way they do in a real galaxy: density is highest on the plane and
 * falls off with angular distance from it.
 *
 * <p>BUILT ONCE, REUSED EVERY FRAME. Each of the three buffers is emitted the first frame that has a GL context, then
 * reused for the life of the client, which is the whole point of a static VertexBuffer. All the soft elements (nebulae,
 * dust, galaxies, glows, comet heads and tails) pack many fans and strips into their buffer as loose triangles (a GL
 * triangle-fan primitive can only hold one fan per buffer, so the fan geometry is emitted as explicit triangles), which
 * keeps each layer to one draw call. Everything now uses POSITION_COLOR with the position-colour shader: the soft
 * elements need a per-vertex alpha gradient to fall off softly, and the stars need per-vertex spectral colour and
 * brightness. Nothing is ever rebuilt per frame, including the static comets.
 *
 * <p>ONE SHELL, ONE MOTION. Every element lives on the same camera-centred shell at radius {@link #SHELL_RADIUS} and
 * shares the exact same parallax translation and slow drift yaw, applied once per frame in the pose matrix (see
 * {@link #buildSkyPose}). This is deliberate and hard-won: concentric sky geometry moving at DIFFERENT rates reads as
 * separate objects sliding past each other, so nothing here, not even a comet, gets its own motion rate. The whole sky
 * is one rigid backdrop driven by one transform.
 *
 * <p>BRIGHTNESS IS CONSTANT. Vanilla fades its stars in and out with {@code level.getStarBrightness(partialTick)}, which
 * tracks day/night. We never read the time or the star brightness at all: the star field draws through a fixed
 * {@link #STAR_BRIGHTNESS} shader multiply with per-star colour baked into the vertices, and every soft element's alpha
 * is baked into its vertices too, so no layer can flicker with an overworld day/night cycle.
 *
 * <p>CHEAP EVERY FRAME. Building the three buffers is a one-time cost. Per frame the framebuffer is already cleared to
 * black (our fog colour) and we issue exactly THREE draw calls, one per buffer (dust, then galaxies, then stars), all
 * additive with depth writes off so nothing occludes the world and overlapping geometry reads brighter. That is the
 * entire per-frame sky cost for anyone in space.
 */
public class SpaceDimensionEffects extends DimensionSpecialEffects
{
    // Radius of the camera-centred shell every element is built on. The stars have used 100 since vanilla; every other
    // layer sits on the SAME shell so the one shared pose transform moves the whole sky as a rigid backdrop. Kept as a
    // named constant now that many populations depend on it agreeing.
    protected static final double SHELL_RADIUS = 100.0D;

    // How many candidate points we scatter for the general field. Some are rejected (too near the sphere centre), so the
    // drawn count is a little lower. ~4500 reads as a rich, deep field; the band and clusters add more on top of this.
    private static final int STAR_COUNT = 4500;

    // Fixed seed so the field is identical every session and for every player (it is scenery, not something that should
    // shimmer between clients). Any constant works; this one is just not vanilla's 10842.
    private static final long STAR_SEED = 0x5C4EE7L;

    // Global brightness multiply for the whole star layer, applied as the shader colour. Per-star colour and brightness
    // are baked into the vertices; this is one knob on top of all of it. 1.0 = draw the baked colours as-is. Never
    // derived from the time of day, so the field does not pulse with a day/night cycle.
    private static final float STAR_BRIGHTNESS = 1.0F;

    // Per-star brightness range for the general field. The draw brightness is skewed toward the dim end (see the pow in
    // emitFieldStars) so most stars are faint and a few are bright, the way a real field looks. Baked into the vertex
    // rgb, not the alpha (star alpha stays 1 so the additive contribution is simply the baked colour).
    private static final float STAR_MIN_BRIGHTNESS = 0.35F;
    private static final float STAR_MAX_BRIGHTNESS = 0.95F;

    // A small fraction of field stars are standouts: drawn at full brightness and a larger quad so a handful of bright
    // foreground stars punch through the haze. Roughly three in a hundred, so they stay special.
    private static final float BRIGHT_STAR_FRACTION = 0.03F;

    // Per-star half-size on the shell. Base size plus a brightness-scaled term, so brighter stars are also a touch
    // bigger; standouts get a further multiply in emitFieldStars.
    private static final double STAR_MIN_SIZE = 0.10D;
    private static final double STAR_SIZE_RANGE = 0.14D;

    // Spectral palette for the general field: blue-white, white, yellow-white, pale orange, orange, red. Weighted (see
    // STAR_PALETTE_WEIGHTS) so most stars are white or blue-white and the warm and red stars are the minority, matching
    // a real field.
    private static final float[][] STAR_PALETTE =
    {
            { 0.78F, 0.85F, 1.00F }, // blue-white
            { 1.00F, 1.00F, 1.00F }, // white
            { 1.00F, 0.96F, 0.84F }, // yellow-white
            { 1.00F, 0.86F, 0.66F }, // pale orange
            { 1.00F, 0.74F, 0.48F }, // orange
            { 1.00F, 0.56F, 0.44F }, // red
    };
    private static final float[] STAR_PALETTE_WEIGHTS = { 5.0F, 6.0F, 4.0F, 2.0F, 1.2F, 0.8F };

    // Plane normal of the band's great circle, (1, 2, 1) before normalisation. The band is the set of directions
    // perpendicular to this normal, i.e. where dir . normal is near zero; the (1, 2, 1) tilt makes it sweep diagonally
    // across the sky rather than lying level with an axis. Normalised in bandBasis.
    private static final double BAND_NORMAL_X = 1.0D;
    private static final double BAND_NORMAL_Y = 2.0D;
    private static final double BAND_NORMAL_Z = 1.0D;

    // Extra stars concentrated in the band, on top of the general field. Dimmer and smaller than the field (see
    // emitBandStars): the band reads as a broad haze of unresolved stars, not a second bright field.
    private static final int BAND_STAR_COUNT = 2600;
    private static final long BAND_STAR_SEED = 0x8B21F4L;

    // Gaussian half-width of the band in angular terms, as the standard deviation of the star's angular offset from the
    // plane, in radians. ~0.18 rad is about 10 degrees, so the visible band is a good few tens of degrees wide with a
    // soft, slightly irregular edge (each star's offset is an independent gaussian draw).
    private static final double BAND_SIGMA = 0.18D;

    // Hard clamp on that gaussian offset so a rare large draw cannot fling a "band" star to the far pole. A few sigma is
    // plenty; past this the density is negligible anyway.
    private static final double BAND_OFFSET_MAX = 0.55D;

    // Band stars run dimmer than the field so the band is haze, not a wall of bright points.
    private static final float BAND_STAR_MIN_BRIGHTNESS = 0.22F;
    private static final float BAND_STAR_MAX_BRIGHTNESS = 0.60F;

    // Band-star half-size: small, since these are meant to blur together into a river rather than resolve individually.
    private static final double BAND_STAR_MIN_SIZE = 0.09D;
    private static final double BAND_STAR_SIZE_RANGE = 0.07D;

    // Cool-leaning palette for the band: mostly white and blue-white with a little warmth, the mixed light of a spiral
    // disc.
    private static final float[][] BAND_STAR_PALETTE =
    {
            { 0.82F, 0.88F, 1.00F }, // blue-white
            { 1.00F, 1.00F, 1.00F }, // white
            { 1.00F, 0.95F, 0.82F }, // pale yellow
    };
    private static final float[] BAND_STAR_PALETTE_WEIGHTS = { 4.0F, 5.0F, 2.0F };

    // Dust patches lying in the band plane, their long axis running ALONG the band, so they read as the mottled dust
    // lanes of a spiral disc rather than round blobs. Emitted into the dust buffer with the nebulae.
    private static final int BAND_DUST_COUNT = 14;
    private static final long BAND_DUST_SEED = 0x2F9C07L;
    private static final int BAND_DUST_SEGMENTS = 22;

    // Along-band and across-band tangent half-widths of a dust patch. Long and thin, hence the streaky lane look.
    private static final double BAND_DUST_MIN_ALONG = 12.0D;
    private static final double BAND_DUST_MAX_ALONG = 30.0D;
    private static final double BAND_DUST_MIN_ACROSS = 3.5D;
    private static final double BAND_DUST_MAX_ACROSS = 8.0D;

    // Centre alpha of a band dust patch (rim always 0). Faint: it is the diffuse glow of the disc, and several overlap.
    private static final float BAND_DUST_MIN_ALPHA = 0.06F;
    private static final float BAND_DUST_MAX_ALPHA = 0.15F;

    // Warm-neutral colours for the band dust, the pale straw of massed starlight seen through dust.
    private static final float[][] BAND_DUST_PALETTE =
    {
            { 0.66F, 0.62F, 0.56F }, // warm grey
            { 0.72F, 0.66F, 0.54F }, // pale straw
            { 0.58F, 0.60F, 0.66F }, // cool grey
    };

    private static final int STAR_CLUSTER_COUNT = 7;
    private static final long STAR_CLUSTER_SEED = 0x71D3A5L;

    // Stars per cluster, varied per cluster between these bounds. Enough to read as a dense knot, not so many it becomes
    // a solid blob.
    private static final int CLUSTER_MIN_STARS = 40;
    private static final int CLUSTER_MAX_STARS = 75;

    // Gaussian angular radius of a cluster on the shell (standard deviation of a member's tangent offset, in shell
    // units). Tight, so the knot stays compact; members are drawn denser toward the centre because the offset is
    // gaussian.
    private static final double CLUSTER_SIGMA = 2.4D;

    // Cluster members are faint and small, a spray of unresolved stars.
    private static final float CLUSTER_MIN_BRIGHTNESS = 0.30F;
    private static final float CLUSTER_MAX_BRIGHTNESS = 0.70F;
    private static final double CLUSTER_MIN_SIZE = 0.09D;
    private static final double CLUSTER_SIZE_RANGE = 0.07D;

    // Enough clouds that, allowed to overlap, they build into larger cloudy regions rather than reading as separate
    // discs. Still bounded so the sky stays a backdrop and not a soup.
    private static final int NEBULA_COUNT = 20;
    private static final long NEBULA_SEED = 0x4E32B1L;
    private static final int NEBULA_SEGMENTS = 24;

    // Fraction of nebulae pulled toward the galactic-band plane rather than scattered freely, so dust concentrates along
    // the band the way it does in a real galaxy while the rest fill the wider sky.
    private static final float NEBULA_BAND_FRACTION = 0.45F;

    // Tangent-plane half-width of a cloud on the shell. A very wide range on purpose: the largest are huge diffuse
    // washes spanning a big slice of sky, the smallest are compact denser knots. Overlap between them is the point.
    private static final double NEBULA_MIN_RADIUS = 12.0D;
    private static final double NEBULA_MAX_RADIUS = 55.0D;

    // Centre alpha of a cloud (the rim is always 0), anti-correlated with size in emitNebulae: the huge diffuse clouds
    // sit near NEBULA_MIN_ALPHA so their overlaps never wash out the stars, while the small compact clouds reach
    // NEBULA_MAX_ALPHA and read as real coloured structure. This is the single most important knob for "rich but not a
    // wash": raised well above the old near-invisible band, but the big faint clouds keep the sum in check.
    private static final float NEBULA_MIN_ALPHA = 0.08F;
    private static final float NEBULA_MAX_ALPHA = 0.30F;

    // Tasteful dusty palette: muted blues, purples, a teal, a faint magenta and one warm rust. Values are already dim
    // rgb; the alpha above dims them further. rust and magenta last so warm clouds stay the minority.
    private static final float[][] NEBULA_PALETTE =
    {
            { 0.34F, 0.44F, 0.70F }, // dusty blue
            { 0.30F, 0.36F, 0.62F }, // deep indigo
            { 0.50F, 0.35F, 0.66F }, // muted purple
            { 0.42F, 0.30F, 0.58F }, // violet
            { 0.28F, 0.54F, 0.54F }, // faint teal
            { 0.58F, 0.34F, 0.56F }, // dim magenta
            { 0.60F, 0.40F, 0.32F }, // warm rust
    };

    private static final int GALAXY_COUNT = 55;
    private static final long GALAXY_SEED = 0x6A17C9L;
    private static final int GALAXY_SEGMENTS = 18;

    // Major-axis half-width on the shell. Wider range than before: most galaxies stay tiny distant smudges, but a few
    // reach GALAXY_MAX_RADIUS and read as recognisable spiral-ish objects rather than dots.
    private static final double GALAXY_MIN_RADIUS = 2.0D;
    private static final double GALAXY_MAX_RADIUS = 12.0D;

    // Minor axis as a fraction of the major, giving the elliptical silhouette. A low fraction is an almost edge-on disc,
    // a high one is nearly face-on. Wider range than before so tilts vary more.
    private static final double GALAXY_MIN_FLATTEN = 0.22D;
    private static final double GALAXY_MAX_FLATTEN = 0.85D;

    // Core alpha of a galaxy (rim always 0). Brighter than a nebula because a galaxy is a defined object, but still
    // under the full-white stars so the star field stays the brightest thing in the sky. Varied per galaxy.
    private static final float GALAXY_MIN_ALPHA = 0.28F;
    private static final float GALAXY_MAX_ALPHA = 0.60F;

    // A galaxy whose major axis exceeds this gets a second, smaller, brighter inner fan (the bulge over the disc), so
    // the large ones read as a disc with a glowing core rather than a flat ellipse.
    private static final double GALAXY_CORE_THRESHOLD = 6.5D;
    private static final double GALAXY_CORE_SCALE = 0.42D;
    private static final float GALAXY_CORE_ALPHA_BOOST = 0.35F;

    // Fraction of galaxies drawn from the warm palette rather than the cool one. Roughly one in five, echoing the orange
    // redshifted galaxies in real deep-field images.
    private static final float GALAXY_WARM_FRACTION = 0.20F;

    private static final float[][] GALAXY_COOL_PALETTE =
    {
            { 0.72F, 0.78F, 0.92F }, // cool white
            { 0.66F, 0.74F, 0.90F }, // pale blue
            { 0.82F, 0.84F, 0.92F }, // near white
            { 0.70F, 0.80F, 0.86F }, // blue-teal
    };

    private static final float[][] GALAXY_WARM_PALETTE =
    {
            { 0.82F, 0.56F, 0.36F }, // warm orange
            { 0.80F, 0.68F, 0.48F }, // pale gold
            { 0.80F, 0.54F, 0.58F }, // dusty rose
    };

    // A handful of small blue-white glows folded into the galaxy buffer, sitting under bright field stars to give a few
    // of them a soft hazy halo. Round soft fans, so unlike a boxy quad glow they read cleanly.
    private static final int BRIGHT_GLOW_COUNT = 12;
    private static final long BRIGHT_GLOW_SEED = 0x3E6B8DL;
    private static final int BRIGHT_GLOW_SEGMENTS = 16;
    private static final double BRIGHT_GLOW_MIN_RADIUS = 1.2D;
    private static final double BRIGHT_GLOW_MAX_RADIUS = 2.2D;
    private static final float BRIGHT_GLOW_MIN_ALPHA = 0.22F;
    private static final float BRIGHT_GLOW_MAX_ALPHA = 0.40F;
    private static final float[] BRIGHT_GLOW_COLOUR = { 0.80F, 0.88F, 1.00F };

    // A couple of STATIC comets: a bright round head with a tapering, fading tail. They do not move (the whole sky is a
    // rigid backdrop), so they read as comets caught mid-sky, not an animated streak. Cheap: one head fan plus a short
    // triangle strip each, folded into the galaxy buffer, no extra draw call.
    private static final int COMET_COUNT = 2;
    private static final long COMET_SEED = 0x0C4472L;
    private static final int COMET_HEAD_SEGMENTS = 14;
    private static final int COMET_TAIL_SEGMENTS = 8;
    private static final double COMET_HEAD_MIN_RADIUS = 1.4D;
    private static final double COMET_HEAD_MAX_RADIUS = 2.0D;
    private static final double COMET_TAIL_MIN_LENGTH = 16.0D;
    private static final double COMET_TAIL_MAX_LENGTH = 24.0D;
    private static final float COMET_HEAD_ALPHA = 0.50F;
    private static final float[] COMET_COLOUR = { 0.78F, 0.86F, 1.00F };

    // The sky sphere is built at radius 100 around the camera. To feel like travel, we drift the whole sphere while the
    // player is moving. Rather than translate by a fraction of the ABSOLUTE world position (which has to be wrapped, and
    // any wrap is a modulo that teleports a field of distinct points by a full period the instant it crosses a seam),
    // we accumulate the drift from the per-frame position DELTA and ease it back toward zero. Real stars are effectively
    // infinitely far and would not parallax at all, but this is a game and the point is to feel motion: while you fly the
    // field slides, and when you stop it settles. This factor scales the world delta into the accumulated offset; kept
    // tiny relative to the 100 radius so the camera never approaches the shell.
    private static final double PARALLAX_FACTOR = 0.006D;

    // The accumulated offset decays toward zero at this rate (fraction remaining per second). At a steady flight speed
    // the drift the delta feeds in each second balances the fraction the decay removes, so the offset converges to a
    // finite equilibrium and never grows without bound. Larger = the field coasts longer after you stop; this settles
    // over a couple of seconds. Applied per-frame but scaled by real elapsed time (see drawStars) so the feel is the
    // same at any frame rate.
    private static final double PARALLAX_DECAY_PER_SECOND = 0.35D;

    // Hard cap on the accumulated offset magnitude per axis. The decay already bounds it at normal speeds; this is a
    // belt-and-suspenders clamp so that no combination of speed and factor can ever carry the offset far from the
    // camera. 8 blocks << the 100 radius, so the shell edge can never come into view no matter what. The clamp is a
    // saturating limit, not a wrap, so it introduces no seam: once at the cap the offset simply stops growing.
    private static final double PARALLAX_MAX = 8.0D;

    // A per-frame position delta larger than this (per axis) is treated as a teleport, not travel, and contributes
    // nothing to the drift. This is what keeps a dimension change, a respawn, an /tp or a relog from flinging the field:
    // those all present as one huge one-frame jump in camera position, which we simply ignore. Normal flight moves far
    // less than this in a single frame.
    private static final double PARALLAX_TELEPORT_DELTA = 32.0D;

    // A very slow constant yaw so space is not dead even when hovering perfectly still. Subtle: 0.075 degrees per second
    // is a near-imperceptible turn that keeps the field alive without reading as spin. Independent of time of day, and
    // wrapped by the trig itself so it never accumulates error.
    private static final float DRIFT_DEG_PER_SECOND = 0.075F;

    // All three built lazily on the first renderSky call (each needs a live GL context) and then reused for the life of
    // the client. Never rebuilt per frame. Null until the first frame draws them.
    // protected so the surface subclass (PlanetSurfaceEffects) can reuse the same three prebuilt buffers rather than
    // owning a second copy of the whole deep-sky field.
    protected VertexBuffer starBuffer;
    protected VertexBuffer nebulaBuffer;
    protected VertexBuffer galaxyBuffer;

    // The accumulated parallax offset the sky is currently translated by, per axis. Fed by the per-frame camera delta
    // and eased back toward zero by the decay. This is a continuous accumulator: it only ever changes by small bounded
    // increments frame to frame, so there is no seam anywhere.
    private double parallaxX;
    private double parallaxY;
    private double parallaxZ;

    // Last camera position we saw, used to derive the per-frame delta. NaN until the first frame, which forces that
    // first frame to contribute no delta (we have nothing to diff against yet).
    private double lastCamX = Double.NaN;
    private double lastCamY = Double.NaN;
    private double lastCamZ = Double.NaN;

    // Render-time of the previous frame in seconds, for frame-rate-independent decay. NaN until the first frame.
    private double lastFrameSeconds = Double.NaN;

    // true for the shared PLANET SURFACE dimension, false for open SPACE. Both share the identical procedural sky (the
    // dust with its nebulae and band lanes, the galaxies with their glows and comets, and the star field with its
    // galactic band and clusters) and the identical buffer implementation (this class), so the surface reads as standing
    // on a rock in the same space, not a second, different sky. The only difference is fog: on the surface we return
    // isFoggyAt = true so vanilla lays dense black fog over the void immediately around a small planet, making the
    // horizon read as space rather than an abrupt terrain edge. Fog is NOT what hides neighbouring planets, though:
    // Distant Horizons ignores fog, so the 65,536-block cell spacing (SurfaceDimension) is what keeps neighbours out
    // of range. In open space we keep fog off so distant bodies and the star field stay visible out to render distance.
    private final boolean denseFog;

    //
    // Planets no longer each get their own dimension: Beerus's authored build and every generated planet share ONE
    // surface dimension (shuruisutilities:planet_surface), dimension type and effects instance, so nothing at the
    // dimension or dimension_type level can single a planet out. The only thing that varies per planet at runtime is
    // which BIOME the player is standing in, so the custom sky and the daytime brightness are driven by a biome lookup at
    // the player position mapped through PlanetSkyTints (a table parallel to NamekBlockTints). Beerus -> violet sky,
    // Vegeta -> green sky, every other biome -> null, which falls through to the normal black-void, ambient-lit look, so
    // no procedural planet is affected. The biome sky_color field cannot do this because renderSky paints a fully
    // procedural sky and never reads it.
    //
    // FAIL SOFT on Beerus: its authored region files fold to surface cell (0, 0), so if the baked biome id there is not
    // shuruisutilities:beerus for any reason, resolveSky still recognises the cell and keeps Beerus purple.

    // The active surface sky is resolved from the player's biome once per player block position and cached, since
    // adjustLightmapColors is called once per lightmap cell (256 per rebuild) and re-sampling the biome that often is
    // wasteful. cachedSkyValid is false until the first resolve; cachedSkyPos is the block position the cache was taken
    // at, so a move to a new block re-resolves. Touched only on the client thread.
    private PlanetSkyTints.Sky cachedSky;
    private long cachedSkyPos;
    private boolean cachedSkyValid;

    // cloudLevel = Float.NaN disables cloud rendering; hasGround = false (floorless void); SkyType.NONE so vanilla
    // draws no sky of its own under ours (we render the whole sky in renderSky); no forced bright lightmap and no
    // constant ambient light so the dimension's own low ambient reads through.
    public SpaceDimensionEffects()
    {
        this(false);
    }

    public SpaceDimensionEffects(boolean denseFog)
    {
        super(Float.NaN, false, SkyType.NONE, false, false);
        this.denseFog = denseFog;
    }

    // keep the fog essentially black regardless of view brightness so bodies read against empty space, not a tint. The
    // exception is a surface biome with a custom sky (Beerus violet, Vegeta green): there the void and the dense horizon
    // fog take on that biome's sky colour so the sky reads that colour. Scoped by the biome lookup, so every other planet
    // keeps the black-void look.
    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness)
    {
        PlanetSkyTints.Sky sky = currentSky();
        if (sky != null)
        {
            return sky.fog;
        }
        return Vec3.ZERO;
    }

    // Lightmap hook (Forge IForgeDimensionSpecialEffects): called once per lightmap cell each time the lightmap rebuilds.
    // On a surface biome with a custom sky we lift every cell toward that sky's daylight colour so the surface is lit like
    // daytime instead of the dim ambient-0.1 floor the shared dimension type otherwise imposes. This is the ONLY
    // per-planet route: the dimension type, the effects instance and (bar the biome) everything else are shared with every
    // generated planet, so brightness can only be singled out by the player's biome. currentSky does exactly that, so no
    // other generated planet is brightened. The colours entering here can sit slightly above 1.0; the lerp keeps them
    // bounded and LightTexture clamps afterward (our forceBrightLightmap is false, so its post-clamp path runs), so no
    // manual clamp is needed.
    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker,
                                     float skyLight, int pixelX, int pixelY, Vector3f colors)
    {
        PlanetSkyTints.Sky sky = currentSky();
        if (sky != null)
        {
            colors.lerp(sky.daylight, sky.daylightMix);
        }
    }

    // The custom sky for the biome the local player is standing in, or null for the ordinary black-void look. Only the
    // shared surface dimension (denseFog) has custom skies; open space never does. Resolved from the biome and cached per
    // player block position, because adjustLightmapColors calls this once per lightmap cell.
    private PlanetSkyTints.Sky currentSky()
    {
        if (!this.denseFog)
        {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !SurfaceDimension.isSurface(mc.level))
        {
            this.cachedSkyValid = false;
            return null;
        }
        long key = mc.player.blockPosition().asLong();
        if (this.cachedSkyValid && key == this.cachedSkyPos)
        {
            return this.cachedSky;
        }
        this.cachedSky = resolveSky(mc);
        this.cachedSkyPos = key;
        this.cachedSkyValid = true;
        return this.cachedSky;
    }

    // Map the player's biome to a sky through PlanetSkyTints. Fail soft: if the biome cannot be read or carries no custom
    // sky, but the player is standing in Beerus's reserved surface cell (0, 0) (where the authored Beerus footprint
    // folds), keep Beerus's violet sky anyway. Any fault reading the biome falls through to the cell check and then null.
    private static PlanetSkyTints.Sky resolveSky(Minecraft mc)
    {
        ResourceLocation biomeId = null;
        try
        {
            BlockPos bp = mc.player.blockPosition();
            biomeId = mc.level.getBiome(bp).unwrapKey().map(k -> k.location()).orElse(null);
        }
        catch (Throwable t)
        {
            biomeId = null;
        }
        PlanetSkyTints.Sky sky = PlanetSkyTints.sky(biomeId);
        if (sky != null)
        {
            return sky;
        }
        Vec3 pos = mc.player.position();
        if (SurfaceDimension.cellIndexOf(pos.x) == 0L && SurfaceDimension.cellIndexOf(pos.z) == 0L)
        {
            return PlanetSkyTints.beerus();
        }
        return null;
    }

    // open space: no thick fog, everything visible to render distance. Surface: dense fog so the empty void right
    // around a small planet reads as space, not a hard edge (neighbours are handled by spacing, not fog).
    @Override
    public boolean isFoggyAt(int x, int y)
    {
        return denseFog;
    }

    /**
     * Draws the whole space sky over the already-black framebuffer: dust (nebulae and band lanes), then galaxies (with
     * their glows and comets), then stars (field, band and clusters), each from its prebuilt buffer. Returning true
     * suppresses vanilla's sky (sun/moon/clouds/horizon) entirely.
     *
     * <p>The three buffers are built on the first call and cached; every later frame reuses them. All three share one
     * sky pose ({@link #buildSkyPose}) so they move as a single backdrop, are drawn with no fog and never fade with time
     * of day, and carry no time-of-day rotation, so the sky is a fixed backdrop with only the slow drift and the travel
     * parallax moving it.
     */
    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog)
    {
        ensureBuffers();

        // The framebuffer was already cleared to our fog colour (black, from getBrightnessDependentFogColor) before
        // this call, so the backdrop is a flat black void. We only need to lay the sky over it. Depth mask off so the
        // far-plane geometry never occludes the world, additive blend so overlapping quads and fans read brighter.
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        FogRenderer.setupNoFog();

        // Build the shared sky transform ONCE this frame (this also advances the parallax accumulator, which must run
        // exactly once per frame), then draw all three buffers with it so they move as one rigid backdrop. Order is
        // back to front: the faint dust first, the brighter galaxies over it, the stars on top. Additive blending makes
        // the order visually irrelevant, but back to front keeps the intent clear.
        Matrix4f skyPose = buildSkyPose(poseStack, camera, ticks, partialTick);
        drawNebulae(skyPose, projectionMatrix);
        drawGalaxies(skyPose, projectionMatrix);
        drawStars(skyPose, projectionMatrix);

        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        // returning true suppresses vanilla's sun/moon/clouds/horizon entirely.
        return true;
    }

    // Build the three static sky buffers on the first call that has a GL context, then reuse them for the life of the
    // client. Extracted from renderSky so the surface subclass can share the identical field without a second copy.
    protected void ensureBuffers()
    {
        if (this.nebulaBuffer == null)
        {
            this.nebulaBuffer = buildNebulaBuffer();
        }
        if (this.galaxyBuffer == null)
        {
            this.galaxyBuffer = buildGalaxyBuffer();
        }
        if (this.starBuffer == null)
        {
            this.starBuffer = buildStarBuffer();
        }
    }

    // Build the shared sky pose for this frame: the camera view rotation, then the slow drift yaw, then the accumulated
    // travel parallax translation. Advancing the parallax accumulator has a per-frame side effect, so this is called
    // exactly once per frame and the resulting matrix is reused for every layer. Post-multiplying the translate onto
    // the camera view (the incoming pose) lands it in a world-aligned frame, keeping the parallax direction consistent
    // as the player turns. We build into a copy so nothing downstream sees our transform.
    protected Matrix4f buildSkyPose(PoseStack poseStack, Camera camera, int ticks, float partialTick)
    {
        Vec3 pos = camera.getPosition();
        updateParallax(pos, (ticks + partialTick) / 20.0D);

        float driftDegrees = (ticks + partialTick) / 20.0F * DRIFT_DEG_PER_SECOND;

        PoseStack skyPose = new PoseStack();
        skyPose.last().pose().set(poseStack.last().pose());
        skyPose.mulPose(Axis.YP.rotationDegrees(driftDegrees));
        skyPose.translate(this.parallaxX, this.parallaxY, this.parallaxZ);
        return skyPose.last().pose();
    }

    // Build the static star VertexBuffer once. Three populations share it, all as small billboarded quads in one
    // POSITION_COLOR buffer (moved off vanilla's flat POSITION so each star can carry its own spectral colour and
    // brightness): the general field, the galactic band, and the clusters. One buffer, one draw call.
    private static VertexBuffer buildStarBuffer()
    {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        emitFieldStars(builder);
        emitBandStars(builder);
        emitClusterStars(builder);

        return upload(builder.end());
    }

    // The general field: scatter STAR_COUNT points on the unit sphere, reject those too near the centre (where the
    // direction is unstable) exactly as vanilla does, and emit a billboarded quad per point. Each star draws in a
    // spectral colour at a dim-skewed brightness, with a small fraction of full-brightness larger standouts.
    private static void emitFieldStars(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(STAR_SEED);
        double[] dir = new double[3];

        for (int i = 0; i < STAR_COUNT; ++i)
        {
            double x = random.nextFloat() * 2.0F - 1.0F;
            double y = random.nextFloat() * 2.0F - 1.0F;
            double z = random.nextFloat() * 2.0F - 1.0F;
            double lenSq = x * x + y * y + z * z;
            if (lenSq >= 1.0D || lenSq <= 0.01D)
            {
                continue;
            }
            double inv = 1.0D / Math.sqrt(lenSq);
            dir[0] = x * inv;
            dir[1] = y * inv;
            dir[2] = z * inv;

            // dim-skewed brightness (pow pushes the mass toward the low end), then a small fraction become full-bright,
            // larger standouts so a handful of foreground stars punch through.
            float t = (float) Math.pow(random.nextFloat(), 2.2D);
            float brightness = STAR_MIN_BRIGHTNESS + t * (STAR_MAX_BRIGHTNESS - STAR_MIN_BRIGHTNESS);
            boolean standout = random.nextFloat() < BRIGHT_STAR_FRACTION;
            if (standout)
            {
                brightness = 1.0F;
            }
            float[] colour = pickWeighted(random, STAR_PALETTE, STAR_PALETTE_WEIGHTS);
            double size = STAR_MIN_SIZE + brightness * STAR_SIZE_RANGE;
            if (standout)
            {
                size *= 1.7D;
            }
            double roll = random.nextDouble() * Math.PI * 2.0D;
            emitStarQuad(builder, dir[0], dir[1], dir[2], size, roll,
                    colour[0] * brightness, colour[1] * brightness, colour[2] * brightness);
        }
    }

    // The galactic band: BAND_STAR_COUNT dimmer, smaller stars placed by a gaussian offset from the band plane, so the
    // density peaks on the plane and falls off with angular distance from it, thickening into a broad Milky-Way-like
    // river. Sampled directly (choose an angle around the band, then a gaussian offset across it) rather than by
    // rejection, so the count is exact and the build is bounded.
    private static void emitBandStars(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(BAND_STAR_SEED);
        double[] n = new double[3];
        double[] e1 = new double[3];
        double[] e2 = new double[3];
        bandBasis(n, e1, e2);

        for (int i = 0; i < BAND_STAR_COUNT; ++i)
        {
            double phi = random.nextDouble() * Math.PI * 2.0D;
            double off = clampBand(random.nextGaussian() * BAND_SIGMA);
            double cosO = Math.cos(off);
            double sinO = Math.sin(off);
            double cosP = Math.cos(phi);
            double sinP = Math.sin(phi);
            // in-plane unit direction at angle phi, then tilted out of the plane by the gaussian offset toward the
            // normal. Both parts are unit and orthogonal, so the result is already unit.
            double dx = cosO * (cosP * e1[0] + sinP * e2[0]) + sinO * n[0];
            double dy = cosO * (cosP * e1[1] + sinP * e2[1]) + sinO * n[1];
            double dz = cosO * (cosP * e1[2] + sinP * e2[2]) + sinO * n[2];

            float brightness = BAND_STAR_MIN_BRIGHTNESS
                    + random.nextFloat() * (BAND_STAR_MAX_BRIGHTNESS - BAND_STAR_MIN_BRIGHTNESS);
            float[] colour = pickWeighted(random, BAND_STAR_PALETTE, BAND_STAR_PALETTE_WEIGHTS);
            double size = BAND_STAR_MIN_SIZE + random.nextDouble() * BAND_STAR_SIZE_RANGE;
            double roll = random.nextDouble() * Math.PI * 2.0D;
            emitStarQuad(builder, dx, dy, dz, size, roll,
                    colour[0] * brightness, colour[1] * brightness, colour[2] * brightness);
        }
    }

    // The clusters: STAR_CLUSTER_COUNT tight knots, each a gaussian swarm of faint stars in the tangent plane at a
    // random direction, denser toward its centre. Distinct from the general field because the members share a small
    // region, so a knot reads as a globular cluster.
    private static void emitClusterStars(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(STAR_CLUSTER_SEED);
        double[] centre = new double[3];
        double[] axisA = new double[3];
        double[] axisB = new double[3];

        for (int c = 0; c < STAR_CLUSTER_COUNT; ++c)
        {
            nextDirection(random, centre);
            tangentAxes(centre[0], centre[1], centre[2], 0.0D, axisA, axisB);
            int members = CLUSTER_MIN_STARS + random.nextInt(CLUSTER_MAX_STARS - CLUSTER_MIN_STARS + 1);

            for (int s = 0; s < members; ++s)
            {
                double ga = random.nextGaussian() * CLUSTER_SIGMA;
                double gb = random.nextGaussian() * CLUSTER_SIGMA;
                // offset the centre direction within its tangent plane, then renormalise back onto the shell.
                double dx = centre[0] * SHELL_RADIUS + axisA[0] * ga + axisB[0] * gb;
                double dy = centre[1] * SHELL_RADIUS + axisA[1] * ga + axisB[1] * gb;
                double dz = centre[2] * SHELL_RADIUS + axisA[2] * ga + axisB[2] * gb;
                double inv = 1.0D / Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx *= inv;
                dy *= inv;
                dz *= inv;

                float brightness = CLUSTER_MIN_BRIGHTNESS
                        + random.nextFloat() * (CLUSTER_MAX_BRIGHTNESS - CLUSTER_MIN_BRIGHTNESS);
                double size = CLUSTER_MIN_SIZE + random.nextDouble() * CLUSTER_SIZE_RANGE;
                double roll = random.nextDouble() * Math.PI * 2.0D;
                // cluster members lean faintly warm-white; scale a near-white by brightness.
                emitStarQuad(builder, dx, dy, dz, size, roll,
                        1.00F * brightness, 0.97F * brightness, 0.90F * brightness);
            }
        }
    }

    // Emit one billboarded star quad. Straight port of vanilla's geometry (point on the shell, per-star random roll so
    // the quads do not all align), just carrying a per-vertex colour now. Alpha is fixed at 1 so the additive
    // contribution is simply the passed colour; overall layer brightness is the STAR_BRIGHTNESS shader multiply.
    private static void emitStarQuad(BufferBuilder builder, double ux, double uy, double uz,
                                     double size, double roll, float r, float g, float b)
    {
        double px = ux * SHELL_RADIUS;
        double py = uy * SHELL_RADIUS;
        double pz = uz * SHELL_RADIUS;
        double azimuth = Math.atan2(ux, uz);
        double sinA = Math.sin(azimuth);
        double cosA = Math.cos(azimuth);
        double polar = Math.atan2(Math.sqrt(ux * ux + uz * uz), uy);
        double sinP = Math.sin(polar);
        double cosP = Math.cos(polar);
        double sinR = Math.sin(roll);
        double cosR = Math.cos(roll);

        for (int j = 0; j < 4; ++j)
        {
            double cornerX = (double) ((j & 2) - 1) * size;
            double cornerY = (double) ((j + 1 & 2) - 1) * size;
            double rx = cornerX * cosR - cornerY * sinR;
            double ry = cornerY * cosR + cornerX * sinR;
            double tz = rx * sinP;
            double ty = -rx * cosP;
            double ox = ty * sinA - ry * cosA;
            double oz = ry * sinA + ty * cosA;
            builder.vertex(px + ox, py + tz, pz + oz).color(r, g, b, 1.0F).endVertex();
        }
    }

    // Draw the prebuilt star buffer under the shared sky pose. STAR_BRIGHTNESS is the one global brightness multiply for
    // the whole layer; per-star colour and brightness ride in the vertices. No time-of-day term. The buffer itself is
    // NEVER rebuilt here: all motion lives in the pose (parallax translate plus drift yaw, built in buildSkyPose), so
    // the cached geometry stays untouched frame to frame. POSITION_COLOR now, so the stars share the position-colour
    // shader with the dust and galaxies.
    void drawStars(Matrix4f skyPose, Matrix4f projectionMatrix)
    {
        drawStars(skyPose, projectionMatrix, STAR_BRIGHTNESS);
    }

    // Same star draw, but with an explicit brightness multiply so the surface subclass can FADE the field in and out
    // with its synthetic day/night cycle and its atmosphere thickness (a thick daytime sky washes the stars out, a thin
    // or night sky lets them through). brightnessMul <= 0 skips the draw entirely.
    protected void drawStars(Matrix4f skyPose, Matrix4f projectionMatrix, float brightnessMul)
    {
        if (brightnessMul <= 0.0F || this.starBuffer == null)
        {
            return;
        }
        RenderSystem.setShaderColor(brightnessMul, brightnessMul, brightnessMul, 1.0F);
        this.starBuffer.bind();
        this.starBuffer.drawWithShader(skyPose, projectionMatrix, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // Draw the prebuilt dust fan buffer (nebulae plus band lanes) under the shared sky pose. POSITION_COLOR carries the
    // per-vertex colour and the centre-to-rim alpha gradient, so the shader colour is left at full white and does
    // nothing but pass the vertices through. Same additive blend and depth-off state as the stars (set once in
    // renderSky).
    protected void drawNebulae(Matrix4f skyPose, Matrix4f projectionMatrix)
    {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        this.nebulaBuffer.bind();
        this.nebulaBuffer.drawWithShader(skyPose, projectionMatrix, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
    }

    // Draw the prebuilt galaxy fan buffer (galaxies, bright-star glows and comets) under the shared sky pose, exactly
    // like the dust. Drawn after the dust and before the stars so these brighter objects sit over the faint clouds but
    // under the pinpoint stars.
    protected void drawGalaxies(Matrix4f skyPose, Matrix4f projectionMatrix)
    {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        this.galaxyBuffer.bind();
        this.galaxyBuffer.drawWithShader(skyPose, projectionMatrix, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
    }

    // Build the static dust buffer once: NEBULA_COUNT soft clouds plus BAND_DUST_COUNT elongated band lanes, all packed
    // as loose triangles into one POSITION_COLOR buffer.
    private static VertexBuffer buildNebulaBuffer()
    {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        emitNebulae(builder);
        emitBandDust(builder);

        return upload(builder.end());
    }

    // Scatter the nebulae. A fraction (NEBULA_BAND_FRACTION) are pulled toward the band plane so dust concentrates along
    // the band; the rest fill the wider sky. Size varies very widely and alpha is anti-correlated with size, so the huge
    // clouds stay faint (their overlaps never wash out the stars) while the small ones read as denser structure.
    private static void emitNebulae(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(NEBULA_SEED);
        double[] n = new double[3];
        double[] e1 = new double[3];
        double[] e2 = new double[3];
        bandBasis(n, e1, e2);
        double[] dir = new double[3];

        for (int i = 0; i < NEBULA_COUNT; ++i)
        {
            if (random.nextFloat() < NEBULA_BAND_FRACTION)
            {
                // near the band plane, with a wider gaussian than the band stars so the dust regions are broad.
                double phi = random.nextDouble() * Math.PI * 2.0D;
                double off = clampBand(random.nextGaussian() * BAND_SIGMA * 1.6D);
                double cosO = Math.cos(off);
                double sinO = Math.sin(off);
                double cosP = Math.cos(phi);
                double sinP = Math.sin(phi);
                dir[0] = cosO * (cosP * e1[0] + sinP * e2[0]) + sinO * n[0];
                dir[1] = cosO * (cosP * e1[1] + sinP * e2[1]) + sinO * n[1];
                dir[2] = cosO * (cosP * e1[2] + sinP * e2[2]) + sinO * n[2];
            }
            else
            {
                nextDirection(random, dir);
            }

            double radius = NEBULA_MIN_RADIUS + random.nextDouble() * (NEBULA_MAX_RADIUS - NEBULA_MIN_RADIUS);
            // anti-correlate alpha with size: sizeFrac 0 (smallest) -> MAX alpha, 1 (largest) -> MIN alpha, with a small
            // jitter so the correlation is not mechanical.
            double sizeFrac = (radius - NEBULA_MIN_RADIUS) / (NEBULA_MAX_RADIUS - NEBULA_MIN_RADIUS);
            float alpha = (float) (NEBULA_MAX_ALPHA - sizeFrac * (NEBULA_MAX_ALPHA - NEBULA_MIN_ALPHA));
            alpha += (random.nextFloat() - 0.5F) * 0.03F;
            float[] colour = NEBULA_PALETTE[random.nextInt(NEBULA_PALETTE.length)];
            // a slight squash so a cloud is not a perfect circle; still soft and near-round. random roll orients it.
            double squash = 0.70D + random.nextDouble() * 0.30D;
            double roll = random.nextDouble() * Math.PI * 2.0D;
            emitSoftFan(builder, dir[0], dir[1], dir[2], radius, radius * squash, roll, NEBULA_SEGMENTS,
                    colour[0], colour[1], colour[2], alpha);
        }
    }

    // Emit the band dust lanes: elongated fans lying in the band plane with their long axis running ALONG the band
    // (the tangent to the great circle at that point), so they streak the way real dust lanes do rather than reading as
    // round blobs. Placed by the same band basis and gaussian offset as the band stars, so lanes and stars coincide.
    private static void emitBandDust(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(BAND_DUST_SEED);
        double[] n = new double[3];
        double[] e1 = new double[3];
        double[] e2 = new double[3];
        bandBasis(n, e1, e2);
        double[] axisAlong = new double[3];
        double[] axisAcross = new double[3];

        for (int i = 0; i < BAND_DUST_COUNT; ++i)
        {
            double phi = random.nextDouble() * Math.PI * 2.0D;
            double off = clampBand(random.nextGaussian() * BAND_SIGMA);
            double cosO = Math.cos(off);
            double sinO = Math.sin(off);
            double cosP = Math.cos(phi);
            double sinP = Math.sin(phi);
            double dx = cosO * (cosP * e1[0] + sinP * e2[0]) + sinO * n[0];
            double dy = cosO * (cosP * e1[1] + sinP * e2[1]) + sinO * n[1];
            double dz = cosO * (cosP * e1[2] + sinP * e2[2]) + sinO * n[2];

            // along-band tangent is the derivative of the in-plane direction with respect to phi; across-band is the
            // remaining perpendicular in the tangent plane. Both unit.
            axisAlong[0] = -sinP * e1[0] + cosP * e2[0];
            axisAlong[1] = -sinP * e1[1] + cosP * e2[1];
            axisAlong[2] = -sinP * e1[2] + cosP * e2[2];
            crossUnit(dx, dy, dz, axisAlong[0], axisAlong[1], axisAlong[2], axisAcross);

            double along = BAND_DUST_MIN_ALONG + random.nextDouble() * (BAND_DUST_MAX_ALONG - BAND_DUST_MIN_ALONG);
            double across = BAND_DUST_MIN_ACROSS + random.nextDouble() * (BAND_DUST_MAX_ACROSS - BAND_DUST_MIN_ACROSS);
            float alpha = BAND_DUST_MIN_ALPHA + random.nextFloat() * (BAND_DUST_MAX_ALPHA - BAND_DUST_MIN_ALPHA);
            float[] colour = BAND_DUST_PALETTE[random.nextInt(BAND_DUST_PALETTE.length)];
            emitSoftFanBasis(builder, dx * SHELL_RADIUS, dy * SHELL_RADIUS, dz * SHELL_RADIUS,
                    axisAlong, axisAcross, along, across, BAND_DUST_SEGMENTS,
                    colour[0], colour[1], colour[2], alpha);
        }
    }

    // Build the static galaxy buffer once: GALAXY_COUNT elliptical smudges plus BRIGHT_GLOW_COUNT small glows plus
    // COMET_COUNT static comets, all packed as loose triangles into one POSITION_COLOR buffer.
    private static VertexBuffer buildGalaxyBuffer()
    {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        emitGalaxies(builder);
        emitBrightGlows(builder);
        emitComets(builder);

        return upload(builder.end());
    }

    // Scatter the galaxies at random tilts, sizes spanning a wide range so a few read as large spiral-ish smudges while
    // most stay small and distant. The large ones get a second smaller, brighter core fan (the bulge) so they read as a
    // disc with a glowing centre. A warm minority are the orange of distant redshifted galaxies.
    private static void emitGalaxies(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(GALAXY_SEED);
        double[] dir = new double[3];
        double[] axisA = new double[3];
        double[] axisB = new double[3];

        for (int i = 0; i < GALAXY_COUNT; ++i)
        {
            nextDirection(random, dir);
            double semiA = GALAXY_MIN_RADIUS + random.nextDouble() * (GALAXY_MAX_RADIUS - GALAXY_MIN_RADIUS);
            double semiB = semiA * (GALAXY_MIN_FLATTEN + random.nextDouble() * (GALAXY_MAX_FLATTEN - GALAXY_MIN_FLATTEN));
            double roll = random.nextDouble() * Math.PI * 2.0D;
            tangentAxes(dir[0], dir[1], dir[2], roll, axisA, axisB);

            float[] colour = random.nextFloat() < GALAXY_WARM_FRACTION
                    ? GALAXY_WARM_PALETTE[random.nextInt(GALAXY_WARM_PALETTE.length)]
                    : GALAXY_COOL_PALETTE[random.nextInt(GALAXY_COOL_PALETTE.length)];
            float alpha = GALAXY_MIN_ALPHA + random.nextFloat() * (GALAXY_MAX_ALPHA - GALAXY_MIN_ALPHA);

            double cx = dir[0] * SHELL_RADIUS;
            double cy = dir[1] * SHELL_RADIUS;
            double cz = dir[2] * SHELL_RADIUS;
            emitSoftFanBasis(builder, cx, cy, cz, axisA, axisB, semiA, semiB, GALAXY_SEGMENTS,
                    colour[0], colour[1], colour[2], alpha);
            // a bright inner bulge for the large ones, same orientation, scaled down and brightened.
            if (semiA > GALAXY_CORE_THRESHOLD)
            {
                float coreAlpha = Math.min(1.0F, alpha + GALAXY_CORE_ALPHA_BOOST);
                emitSoftFanBasis(builder, cx, cy, cz, axisA, axisB, semiA * GALAXY_CORE_SCALE,
                        semiB * GALAXY_CORE_SCALE, GALAXY_SEGMENTS, colour[0], colour[1], colour[2], coreAlpha);
            }
        }
    }

    // A handful of small round blue-white glows, so a few field stars pick up a soft hazy halo. Round soft fans read
    // cleaner here than a low-alpha quad would.
    private static void emitBrightGlows(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(BRIGHT_GLOW_SEED);
        double[] dir = new double[3];
        for (int i = 0; i < BRIGHT_GLOW_COUNT; ++i)
        {
            nextDirection(random, dir);
            double radius = BRIGHT_GLOW_MIN_RADIUS + random.nextDouble() * (BRIGHT_GLOW_MAX_RADIUS - BRIGHT_GLOW_MIN_RADIUS);
            float alpha = BRIGHT_GLOW_MIN_ALPHA + random.nextFloat() * (BRIGHT_GLOW_MAX_ALPHA - BRIGHT_GLOW_MIN_ALPHA);
            emitSoftFan(builder, dir[0], dir[1], dir[2], radius, radius, 0.0D, BRIGHT_GLOW_SEGMENTS,
                    BRIGHT_GLOW_COLOUR[0], BRIGHT_GLOW_COLOUR[1], BRIGHT_GLOW_COLOUR[2], alpha);
        }
    }

    // A couple of STATIC comets: a bright round head plus a tapering, fading tail laid out in the tangent plane. They do
    // not move; they are fixed backdrop objects like the galaxies, so there is no per-frame work and no separate motion
    // rate to break the rigid-backdrop rule.
    private static void emitComets(BufferBuilder builder)
    {
        RandomSource random = RandomSource.create(COMET_SEED);
        double[] dir = new double[3];
        double[] axisA = new double[3];
        double[] axisB = new double[3];

        for (int i = 0; i < COMET_COUNT; ++i)
        {
            nextDirection(random, dir);
            double roll = random.nextDouble() * Math.PI * 2.0D;
            tangentAxes(dir[0], dir[1], dir[2], roll, axisA, axisB);
            double headRadius = COMET_HEAD_MIN_RADIUS + random.nextDouble() * (COMET_HEAD_MAX_RADIUS - COMET_HEAD_MIN_RADIUS);
            double length = COMET_TAIL_MIN_LENGTH + random.nextDouble() * (COMET_TAIL_MAX_LENGTH - COMET_TAIL_MIN_LENGTH);

            double hx = dir[0] * SHELL_RADIUS;
            double hy = dir[1] * SHELL_RADIUS;
            double hz = dir[2] * SHELL_RADIUS;
            // bright round head.
            emitSoftFanBasis(builder, hx, hy, hz, axisA, axisB, headRadius, headRadius, COMET_HEAD_SEGMENTS,
                    COMET_COLOUR[0], COMET_COLOUR[1], COMET_COLOUR[2], COMET_HEAD_ALPHA);

            // tail: a short triangle strip extending along -axisA, narrowing and fading to nothing at the tip. axisB is
            // the across direction that gives the tail its width.
            double width = headRadius * 0.8D;
            for (int k = 0; k < COMET_TAIL_SEGMENTS; ++k)
            {
                double t0 = (double) k / COMET_TAIL_SEGMENTS;
                double t1 = (double) (k + 1) / COMET_TAIL_SEGMENTS;
                double c0x = hx - axisA[0] * length * t0;
                double c0y = hy - axisA[1] * length * t0;
                double c0z = hz - axisA[2] * length * t0;
                double c1x = hx - axisA[0] * length * t1;
                double c1y = hy - axisA[1] * length * t1;
                double c1z = hz - axisA[2] * length * t1;
                double w0 = width * (1.0D - t0);
                double w1 = width * (1.0D - t1);
                float a0 = (float) (COMET_HEAD_ALPHA * (1.0D - t0));
                float a1 = (float) (COMET_HEAD_ALPHA * (1.0D - t1));
                float r = COMET_COLOUR[0];
                float g = COMET_COLOUR[1];
                float b = COMET_COLOUR[2];
                // two triangles per segment forming the tapered ribbon; the tail edges carry alpha 0 nowhere, the taper
                // and fade come from w and a shrinking together.
                builder.vertex(c0x + axisB[0] * w0, c0y + axisB[1] * w0, c0z + axisB[2] * w0).color(r, g, b, a0).endVertex();
                builder.vertex(c0x - axisB[0] * w0, c0y - axisB[1] * w0, c0z - axisB[2] * w0).color(r, g, b, a0).endVertex();
                builder.vertex(c1x + axisB[0] * w1, c1y + axisB[1] * w1, c1z + axisB[2] * w1).color(r, g, b, a1).endVertex();
                builder.vertex(c1x + axisB[0] * w1, c1y + axisB[1] * w1, c1z + axisB[2] * w1).color(r, g, b, a1).endVertex();
                builder.vertex(c0x - axisB[0] * w0, c0y - axisB[1] * w0, c0z - axisB[2] * w0).color(r, g, b, a0).endVertex();
                builder.vertex(c1x - axisB[0] * w1, c1y - axisB[1] * w1, c1z - axisB[2] * w1).color(r, g, b, a1).endVertex();
            }
        }
    }

    // Upload a finished builder into a fresh static VertexBuffer, mirroring the old buildStarBuffer bind/upload/unbind
    // dance.
    private static VertexBuffer upload(BufferBuilder.RenderedBuffer built)
    {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(built);
        VertexBuffer.unbind();
        return buffer;
    }

    // Pick a random direction on the unit sphere, rejecting points near the centre where the direction is unstable,
    // exactly the way the star scatter does. Written into the passed 3-array to avoid a per-element allocation.
    private static void nextDirection(RandomSource random, double[] out)
    {
        while (true)
        {
            double x = random.nextFloat() * 2.0F - 1.0F;
            double y = random.nextFloat() * 2.0F - 1.0F;
            double z = random.nextFloat() * 2.0F - 1.0F;
            double lenSq = x * x + y * y + z * z;
            if (lenSq < 1.0D && lenSq > 0.01D)
            {
                double inv = 1.0D / Math.sqrt(lenSq);
                out[0] = x * inv;
                out[1] = y * inv;
                out[2] = z * inv;
                return;
            }
        }
    }

    // Weighted palette pick: choose an entry with probability proportional to its weight. Used so the spectral star
    // colours favour white and blue-white with warm and red as the minority. Sums the weights each call, which is fine
    // at build time.
    private static float[] pickWeighted(RandomSource random, float[][] palette, float[] weights)
    {
        float total = 0.0F;
        for (float w : weights)
        {
            total += w;
        }
        float pick = random.nextFloat() * total;
        for (int i = 0; i < weights.length; ++i)
        {
            pick -= weights[i];
            if (pick <= 0.0F)
            {
                return palette[i];
            }
        }
        return palette[palette.length - 1];
    }

    // The band's orthonormal frame: normalise the (1, 2, 1) plane normal into n, then build two unit vectors e1 and e2
    // spanning the plane perpendicular to it. Any orthonormal pair in the plane works; we pick a helper axis not
    // parallel to n and cross to get one. Written into the passed 3-arrays.
    private static void bandBasis(double[] n, double[] e1, double[] e2)
    {
        double len = 1.0D / Math.sqrt(BAND_NORMAL_X * BAND_NORMAL_X + BAND_NORMAL_Y * BAND_NORMAL_Y
                + BAND_NORMAL_Z * BAND_NORMAL_Z);
        n[0] = BAND_NORMAL_X * len;
        n[1] = BAND_NORMAL_Y * len;
        n[2] = BAND_NORMAL_Z * len;
        double hx = Math.abs(n[1]) < 0.99D ? 0.0D : 1.0D;
        double hy = Math.abs(n[1]) < 0.99D ? 1.0D : 0.0D;
        crossUnit(hx, hy, 0.0D, n[0], n[1], n[2], e1);
        crossUnit(n[0], n[1], n[2], e1[0], e1[1], e1[2], e2);
    }

    // Unit cross product a x b written into out. Used for tangent frames, so the inputs are never parallel.
    private static void crossUnit(double ax, double ay, double az, double bx, double by, double bz, double[] out)
    {
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        double inv = 1.0D / Math.sqrt(cx * cx + cy * cy + cz * cz);
        out[0] = cx * inv;
        out[1] = cy * inv;
        out[2] = cz * inv;
    }

    // Clamp a band offset (the gaussian angular distance from the band plane) so a rare large draw cannot fling a star
    // to the far side of the sky where the density should be zero.
    private static double clampBand(double off)
    {
        if (off > BAND_OFFSET_MAX)
        {
            return BAND_OFFSET_MAX;
        }
        if (off < -BAND_OFFSET_MAX)
        {
            return -BAND_OFFSET_MAX;
        }
        return off;
    }

    // Compute the tangent-plane major/minor axis directions at a shell point, spun by roll. u and v span the plane
    // perpendicular to the view direction (so a disc built on them faces the camera at the sphere centre), then roll
    // rotates them so element orientations vary. axisA is the rotated u (major-axis direction), axisB the rotated v.
    // Written into the passed 3-arrays.
    private static void tangentAxes(double dx, double dy, double dz, double roll, double[] axisA, double[] axisB)
    {
        double hx = Math.abs(dy) < 0.99D ? 0.0D : 1.0D;
        double hy = Math.abs(dy) < 0.99D ? 1.0D : 0.0D;
        double ux = hy * dz - 0.0D * dy;
        double uy = 0.0D * dx - hx * dz;
        double uz = hx * dy - hy * dx;
        double ul = 1.0D / Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux *= ul;
        uy *= ul;
        uz *= ul;
        double vx = dy * uz - dz * uy;
        double vy = dz * ux - dx * uz;
        double vz = dx * uy - dy * ux;

        double cr = Math.cos(roll);
        double sr = Math.sin(roll);
        axisA[0] = ux * cr + vx * sr;
        axisA[1] = uy * cr + vy * sr;
        axisA[2] = uz * cr + vz * sr;
        axisB[0] = -ux * sr + vx * cr;
        axisB[1] = -uy * sr + vy * cr;
        axisB[2] = -uz * sr + vz * cr;
    }

    // Emit one soft radial element as loose triangles, computing its own tangent basis and roll. Convenience wrapper
    // over emitSoftFanBasis for the round/elliptical elements (nebulae, glows) that do not need a custom axis. dx/dy/dz
    // is the unit direction of the element's centre on the shell.
    private static void emitSoftFan(BufferBuilder builder, double dx, double dy, double dz,
                                    double semiA, double semiB, double roll, int segments,
                                    float r, float g, float b, float centreAlpha)
    {
        double[] axisA = new double[3];
        double[] axisB = new double[3];
        tangentAxes(dx, dy, dz, roll, axisA, axisB);
        emitSoftFanBasis(builder, dx * SHELL_RADIUS, dy * SHELL_RADIUS, dz * SHELL_RADIUS,
                axisA, axisB, semiA, semiB, segments, r, g, b, centreAlpha);
    }

    // Emit one soft radial element as loose triangles given an explicit centre and tangent axes. It is fan geometry (a
    // shared centre vertex with a ring of rim vertices), but a GL triangle-fan primitive can only hold a single fan per
    // buffer, so we emit each wedge as its own triangle sharing the centre; that lets many elements pack into one buffer
    // and one draw call. The centre carries the colour at centreAlpha and every rim vertex carries the same rgb at alpha
    // 0, so additive blending paints a smooth radial falloff rather than a hard polygon. axisA and axisB are the unit
    // major and minor directions in the tangent plane; semiA and semiB are the half-widths along them (equal for a round
    // element, unequal for an elliptical one). Callers pass a custom pair (band lanes along the band, galaxy cores
    // sharing the disc orientation) or let emitSoftFan compute the default.
    private static void emitSoftFanBasis(BufferBuilder builder, double cx, double cy, double cz,
                                         double[] axisA, double[] axisB, double semiA, double semiB, int segments,
                                         float r, float g, float b, float centreAlpha)
    {
        for (int i = 0; i < segments; ++i)
        {
            double t0 = (double) i / segments * Math.PI * 2.0D;
            double t1 = (double) (i + 1) / segments * Math.PI * 2.0D;
            double c0 = Math.cos(t0) * semiA;
            double s0 = Math.sin(t0) * semiB;
            double c1 = Math.cos(t1) * semiA;
            double s1 = Math.sin(t1) * semiB;
            double p0x = cx + axisA[0] * c0 + axisB[0] * s0;
            double p0y = cy + axisA[1] * c0 + axisB[1] * s0;
            double p0z = cz + axisA[2] * c0 + axisB[2] * s0;
            double p1x = cx + axisA[0] * c1 + axisB[0] * s1;
            double p1y = cy + axisA[1] * c1 + axisB[1] * s1;
            double p1z = cz + axisA[2] * c1 + axisB[2] * s1;
            builder.vertex(cx, cy, cz).color(r, g, b, centreAlpha).endVertex();
            builder.vertex(p0x, p0y, p0z).color(r, g, b, 0.0F).endVertex();
            builder.vertex(p1x, p1y, p1z).color(r, g, b, 0.0F).endVertex();
        }
    }

    // Advance the parallax accumulator for one frame. The offset is fed by the per-frame camera delta (scaled by
    // PARALLAX_FACTOR) and eased back toward zero by an exponential decay, then saturated at PARALLAX_MAX per axis.
    // Nothing here can produce a discontinuity:
    //   - The offset changes only by (small bounded delta contribution) minus (a fraction of itself), so consecutive
    //     frames differ by a small amount. There is no modulo and thus no seam.
    //   - A teleport (dimension change, respawn, /tp, relog) shows up as one huge one-frame delta; any axis whose delta
    //     exceeds PARALLAX_TELEPORT_DELTA contributes zero that frame, so the jump feeds NO impulse into the offset. The
    //     offset simply keeps decaying, which is continuous.
    //   - The very first frame (lastCam is NaN) also contributes no delta, so a fresh join or relog starts from rest.
    // The decay is scaled by real elapsed seconds so the feel is frame-rate independent.
    private void updateParallax(Vec3 pos, double nowSeconds)
    {
        // Elapsed real time since the previous frame, used for frame-rate-independent decay. Clamp to a sane range so a
        // long stall (paused, first frame) neither divides by a huge dt nor snaps the offset to zero in one step.
        double dt = Double.isNaN(this.lastFrameSeconds) ? 0.0D : nowSeconds - this.lastFrameSeconds;
        if (dt < 0.0D || dt > 0.25D)
        {
            dt = 0.0D;
        }
        this.lastFrameSeconds = nowSeconds;

        // Per-frame world delta. On the first frame, or when a delta reads as a teleport, contribute nothing.
        if (!Double.isNaN(this.lastCamX))
        {
            this.parallaxX += travelContribution(pos.x - this.lastCamX);
            this.parallaxY += travelContribution(pos.y - this.lastCamY);
            this.parallaxZ += travelContribution(pos.z - this.lastCamZ);
        }
        this.lastCamX = pos.x;
        this.lastCamY = pos.y;
        this.lastCamZ = pos.z;

        // Exponential decay toward zero: keep (decayPerSecond ^ dt) of the offset this frame. dt == 0 leaves it
        // untouched, which is exactly right for the first frame and any stalled frame.
        double keep = Math.pow(PARALLAX_DECAY_PER_SECOND, dt);
        this.parallaxX = clampOffset(this.parallaxX * keep);
        this.parallaxY = clampOffset(this.parallaxY * keep);
        this.parallaxZ = clampOffset(this.parallaxZ * keep);
    }

    // Turn a per-frame world delta on one axis into its contribution to the parallax offset. A delta larger than the
    // teleport threshold is ignored (returns 0) so jumps never feed the accumulator; a normal travel delta is scaled by
    // the parallax factor.
    private static double travelContribution(double delta)
    {
        if (Math.abs(delta) > PARALLAX_TELEPORT_DELTA)
        {
            return 0.0D;
        }
        return delta * PARALLAX_FACTOR;
    }

    // Saturating clamp into [-PARALLAX_MAX, PARALLAX_MAX]. This is a limit, not a wrap: at the cap the value stops
    // growing rather than jumping to the far side, so it can never introduce a seam.
    private static double clampOffset(double v)
    {
        if (v > PARALLAX_MAX)
        {
            return PARALLAX_MAX;
        }
        if (v < -PARALLAX_MAX)
        {
            return -PARALLAX_MAX;
        }
        return v;
    }
}
