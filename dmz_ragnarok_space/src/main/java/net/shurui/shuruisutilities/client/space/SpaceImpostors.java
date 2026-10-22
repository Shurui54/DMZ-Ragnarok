package net.shurui.shuruisutilities.client.space;

import java.util.Arrays;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.shurui.shuruisutilities.space.GeneratedSystems;
import net.shurui.shuruisutilities.space.Orbits;

/**
 * Distant space bodies drawn as LIT DISCS instead of glowing dots (owner, 2026-10-21: "make them actually render as
 * planets and not little dots of light in the distance", "the entire distant solar systems should be visible with real
 * orbits and such, keeping performance good"). Client only; the server sends nothing for any of this.
 *
 * <h3>What it draws</h3>
 * <ul>
 *   <li><b>Every distant system</b>: its sun as a bright limb-darkened disc with a corona, and every planet as a sphere
 *       impostor lit from that sun (a day side, a soft terminator, a night side, an atmosphere rim brightest on the lit
 *       limb), in the planet's own tint and surface style, at its TRUE orbital position (the same {@link Orbits} maths
 *       and shared clock the server lands with), plus a faint dust belt in some systems.</li>
 *   <li><b>Any nearer body too small on screen for its model</b> (the main planets across the system, a far side planet
 *       of a near system, the central sun from the outer rings), handed over by {@link SpaceBodyRenderer} so it reads as
 *       a lit world instead of a one-pixel cube. Fixed planets bring their moon.</li>
 *   <li><b>Optional orbit lines</b> ({@link SpaceVisualsConfig#orbitLines}, off by default): the main system's rings and
 *       each nearby system's planet orbits as faint lines.</li>
 * </ul>
 *
 * <h3>Lighting without a shader</h3>
 * The impostor art is a procedural atlas built ONCE on the CPU (one {@link DynamicTexture}): for each of four surface
 * styles, a sphere lit at 13 phase angles from full to new, each with its atmosphere glow baked in. A quad picks the
 * tile for its phase angle (camera, planet, star) and is rolled in the screen plane so the lit side faces the star's
 * on-screen direction. So it is lit correctly from its own star through a stock entity render type, which is why a
 * shader pack (Oculus/Iris) still draws it: nothing here needs a custom core shader.
 *
 * <h3>Cost</h3>
 * One sorted batch per frame (one draw call for every impostor, one for the orbit lines when on). Sources are rebuilt
 * on {@link SpaceBodyRenderer}'s ~1 s refresh; per frame each source costs a few trig calls for its orbit, a cone test
 * against the view frustum and, when visible, four vertices. Every per-frame array is preallocated and grown only on a
 * refresh, so a frame allocates nothing here.
 *
 * <h3>Sizes</h3>
 * Positions are honest. Sizes are honest until a body would shrink under {@link #MIN_PLANET_PX} pixels; past that a
 * distant body is drawn up to {@link #FAR_SIZE_GAIN} times its true size, never more than {@link #MAX_FAR_RADIUS} blocks
 * (well under half the gap between two planet rings, so neighbours never merge), then floored at a pixel size and dimmed
 * with distance. That is what keeps a system tens of thousands of blocks away readable as a sun with planets.
 */
final class SpaceImpostors
{
    private SpaceImpostors()
    {
    }

    // ===== atlas =====

    private static final int TILE = 64;
    private static final int PHASES = 13;                 // 0, 15, ... 180 degrees
    static final int STYLE_ROCKY = 0;
    static final int STYLE_BANDED = 1;
    static final int STYLE_OCEAN = 2;
    static final int STYLE_ICE = 3;
    private static final int STYLES = 4;
    private static final int COLS = 16;
    private static final int ROWS = 4;
    // the extra tiles after the 52 planet tiles: a star disc and a dust belt strip.
    private static final int TILE_STAR = STYLES * PHASES;
    private static final int TILE_BELT = TILE_STAR + 1;
    // the planet disc fills this fraction of a tile's half-width; the rest is atmosphere glow.
    static final float DISC_FRACTION = 0.72F;
    // the star disc fills this fraction; the rest is corona.
    private static final float STAR_DISC_FRACTION = 0.34F;

    private static final ResourceLocation ATLAS = new ResourceLocation("dmz_ragnarok", "space/impostor_atlas");
    private static boolean atlasReady = false;
    private static boolean atlasFailed = false;

    private static final int FULL_BRIGHT = 0x00F000F0;

    // ===== tuning =====

    // the smallest on-screen RADIUS, in pixels, a planet / a sun is drawn at; below their true size they hold this and dim.
    static final float MIN_PLANET_PX = 1.7F;
    private static final float MIN_SUN_PX = 2.6F;
    // a distant body is drawn up to this multiple of its true radius, capped at MAX_FAR_RADIUS blocks.
    private static final float FAR_SIZE_GAIN = 2.5F;
    private static final float MAX_FAR_RADIUS = 110.0F;
    // the honest-pixel size at which the gain is fully on (it ramps in from the model hand-over size down to this).
    private static final float GAIN_FULL_PX = 1.5F;
    // how far a held-at-floor body may dim: at most to this fraction of full brightness.
    private static final float MIN_FADE = 0.35F;
    // the sun's drawn radius over its true radius, matching SpaceBodyRenderer.STAR_DRAW_SCALE so a sun keeps its size
    // across the hand-over.
    private static final float STAR_DRAW_SCALE = 3.0F;

    // dust belts: which systems have one, and how faint. A belt is only drawn from far away (it is scenery: there are no
    // asteroids in it to fly into), fading out as you approach the system.
    private static final double BELT_CHANCE = 0.4;
    private static final int BELT_SEGMENTS = 40;
    private static final float BELT_ALPHA = 0.10F;
    private static final double BELT_FADE_NEAR = 6000.0;
    private static final double BELT_FADE_FAR = 14000.0;
    // past this a belt is not drawn at all: from that far it only reads as a stray ring around a dot.
    private static final double BELT_MAX_DISTANCE = 60000.0;

    // orbit lines: how far a system's planet orbits are drawn, and their look.
    private static final double ORBIT_LINE_RANGE = 90000.0;
    private static final float ORBIT_LINE_ALPHA = 0.22F;

    // ===== sources (rebuilt on the renderer's ~1 s refresh) =====

    static final byte K_SUN = 0;
    static final byte K_PLANET = 1;

    private static int srcCount = 0;
    private static byte[] srcKind = new byte[0];
    // for a planet: orbit centre (its sun), ring radius, start phase, period, tilt, node. for a sun: its position.
    private static double[] srcCx = new double[0];
    private static double[] srcCy = new double[0];
    private static double[] srcCz = new double[0];
    private static double[] srcRing = new double[0];
    private static double[] srcPhase = new double[0];
    private static double[] srcPeriod = new double[0];
    private static double[] srcIncl = new double[0];
    private static double[] srcNode = new double[0];
    private static float[] srcRadius = new float[0];
    private static int[] srcTint = new int[0];
    private static byte[] srcStyle = new byte[0];
    // 1 for every system planet: drawn here only while it is OUTSIDE the renderer's near range (inside it the renderer
    // draws it as a model, or hands it back here through addBody when it is small), so nothing is drawn twice.
    private static byte[] srcNear = new byte[0];
    private static double nearRangeSq = 0.0;

    // one entry per system for belts and orbit lines: star position, tilt, node, outer ring, belt ring (0 = none),
    // and the slice of sources that are its planets.
    private static int sysCount = 0;
    private static double[] sysX = new double[0];
    private static double[] sysY = new double[0];
    private static double[] sysZ = new double[0];
    private static double[] sysIncl = new double[0];
    private static double[] sysNode = new double[0];
    private static double[] sysOuter = new double[0];
    private static double[] sysBelt = new double[0];
    private static int[] sysTint = new int[0];
    private static int[] sysFirst = new int[0];
    private static int[] sysPlanets = new int[0];

    // the main system's distinct ring radii, for orbit lines.
    private static double[] mainRings = new double[0];
    private static double[] mainRingIncl = new double[0];
    private static double[] mainRingNode = new double[0];
    private static int mainRingCount = 0;

    /** Start a rebuild of the source list. Capacity is kept from the last build and grown only if needed. */
    static void beginSources(int planetCapacity, int systemCapacity, double nearRange)
    {
        nearRangeSq = nearRange * nearRange;
        srcCount = 0;
        sysCount = 0;
        mainRingCount = 0;
        if (srcKind.length < planetCapacity)
        {
            int n = Math.max(planetCapacity, srcKind.length * 2);
            srcKind = new byte[n];
            srcCx = new double[n];
            srcCy = new double[n];
            srcCz = new double[n];
            srcRing = new double[n];
            srcPhase = new double[n];
            srcPeriod = new double[n];
            srcIncl = new double[n];
            srcNode = new double[n];
            srcRadius = new float[n];
            srcTint = new int[n];
            srcStyle = new byte[n];
            srcNear = new byte[n];
            ensureFrameCapacity(n + 512);
        }
        if (sysX.length < systemCapacity)
        {
            int n = Math.max(systemCapacity, sysX.length * 2);
            sysX = new double[n];
            sysY = new double[n];
            sysZ = new double[n];
            sysIncl = new double[n];
            sysNode = new double[n];
            sysOuter = new double[n];
            sysBelt = new double[n];
            sysTint = new int[n];
            sysFirst = new int[n];
            sysPlanets = new int[n];
        }
    }

    /**
     * Add a system, then call {@link #addSystemPlanet} for each planet. A NEAR system (its sun inside the renderer's near
     * range, where the renderer draws the sun itself) adds no sun source. Any system's planets are drawn here only while
     * they are outside the near range.
     */
    static void addSystem(Vec3 starPos, float starRadius, int starTint, double inclination, double nodeAngle,
                          double outerRing, double beltRing, boolean near)
    {
        if (srcCount >= srcKind.length || sysCount >= sysX.length)
        {
            return;
        }
        if (!near)
        {
            int i = srcCount++;
            srcKind[i] = K_SUN;
            srcCx[i] = starPos.x;
            srcCy[i] = starPos.y;
            srcCz[i] = starPos.z;
            srcRadius[i] = starRadius;
            srcTint[i] = starTint;
            srcNear[i] = 0;
        }
        int s = sysCount++;
        sysX[s] = starPos.x;
        sysY[s] = starPos.y;
        sysZ[s] = starPos.z;
        sysIncl[s] = inclination;
        sysNode[s] = nodeAngle;
        sysOuter[s] = outerRing;
        sysBelt[s] = beltRing;
        sysTint[s] = starTint;
        sysFirst[s] = srcCount;
        sysPlanets[s] = 0;
    }

    /** Add one planet of the system most recently added. */
    static void addSystemPlanet(double ring, double phase0, float radius, int tint, int style)
    {
        if (srcCount >= srcKind.length || sysCount == 0)
        {
            return;
        }
        int s = sysCount - 1;
        int i = srcCount++;
        srcKind[i] = K_PLANET;
        srcCx[i] = sysX[s];
        srcCy[i] = sysY[s];
        srcCz[i] = sysZ[s];
        srcRing[i] = ring;
        srcPhase[i] = phase0;
        srcPeriod[i] = Orbits.periodMillis(ring);
        srcIncl[i] = sysIncl[s];
        srcNode[i] = sysNode[s];
        srcRadius[i] = radius;
        srcTint[i] = tint;
        srcStyle[i] = (byte) style;
        srcNear[i] = 1;
        sysPlanets[s]++;
    }

    /** Record one of the main system's rings (radius and tilted plane, deduplicated by radius), for the orbit lines. */
    static void addMainRing(double radius, double inclination, double nodeAngle)
    {
        for (int i = 0; i < mainRingCount; ++i)
        {
            if (Math.abs(mainRings[i] - radius) < 1.0)
            {
                return;
            }
        }
        if (mainRingCount >= mainRings.length)
        {
            int n = Math.max(8, mainRings.length * 2);
            mainRings = Arrays.copyOf(mainRings, n);
            mainRingIncl = Arrays.copyOf(mainRingIncl, n);
            mainRingNode = Arrays.copyOf(mainRingNode, n);
        }
        mainRingIncl[mainRingCount] = inclination;
        mainRingNode[mainRingCount] = nodeAngle;
        mainRings[mainRingCount++] = radius;
    }

    /** Whether a system should show a dust belt, and at which ring radius (0 = none). Pure function of the star key. */
    static double beltRingFor(String starKey, double innerRing, double ringStep, int planetCount)
    {
        if (planetCount < 3)
        {
            return 0.0;
        }
        long h = mixHash(starKey.hashCode());
        if (((h >>> 8) & 0xFFFF) / 65536.0 >= BELT_CHANCE)
        {
            return 0.0;
        }
        int gap = (int) Math.floorMod(h >>> 24, (long) (planetCount - 1));
        return innerRing + (gap + 0.5) * ringStep;
    }

    // ===== per-frame state =====

    private static int frameCount = 0;
    private static float[] fX = new float[0];      // camera-relative drawn position (shell scaled)
    private static float[] fY = new float[0];
    private static float[] fZ = new float[0];
    private static float[] fR = new float[0];      // drawn quad half-size (shell units)
    private static float[] fLx = new float[0];     // world light direction (toward its star), unit
    private static float[] fLy = new float[0];
    private static float[] fLz = new float[0];
    private static float[] fCol = new float[0];    // brightness multiply (fade)
    private static int[] fTint = new int[0];
    private static byte[] fKind = new byte[0];
    private static byte[] fStyle = new byte[0];
    private static long[] fOrder = new long[0];

    private static void ensureFrameCapacity(int n)
    {
        if (fX.length >= n)
        {
            return;
        }
        fX = new float[n];
        fY = new float[n];
        fZ = new float[n];
        fR = new float[n];
        fLx = new float[n];
        fLy = new float[n];
        fLz = new float[n];
        fCol = new float[n];
        fTint = new int[n];
        fKind = new byte[n];
        fStyle = new byte[n];
        fOrder = new long[n];
    }

    // frame constants, set by beginFrame
    private static double camX;
    private static double camY;
    private static double camZ;
    private static float lookX;
    private static float lookY;
    private static float lookZ;
    private static double cosHalfFov;
    private static double pxScale;     // screen pixels per (world unit / distance)

    /** Start a frame: camera, view cone and pixel scale. Call before {@link #addBody} and {@link #draw}. */
    static void beginFrame(Camera camera, Matrix4f projection)
    {
        frameCount = 0;
        ensureFrameCapacity(srcCount + 512);
        Vec3 cam = camera.getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        Vector3f look = camera.getLookVector();
        lookX = look.x();
        lookY = look.y();
        lookZ = look.z();
        double tx = 1.0 / Math.max(1.0E-3, projection.m00());
        double ty = 1.0 / Math.max(1.0E-3, projection.m11());
        double halfDiag = Math.atan(Math.sqrt(tx * tx + ty * ty));
        cosHalfFov = Math.cos(Math.min(Math.PI * 0.5, halfDiag + 0.05));
        int h = Minecraft.getInstance().getWindow().getHeight();
        pxScale = 0.5 * Math.max(1, h) * projection.m11();
    }

    /** The radius in pixels a body of {@code radius} at {@code dist} covers on screen this frame. */
    static double pixels(double radius, double dist)
    {
        return dist <= 1.0E-6 ? Double.MAX_VALUE : radius / dist * pxScale;
    }

    /**
     * Queue one body for the impostor batch. World position, true radius, tint, style, and the world position of the star
     * lighting it (for a sun pass its own position; kind tells them apart). Culled against the view cone here.
     */
    static void addBody(byte kind, double x, double y, double z, float radius, int tint, int style,
                        double starX, double starY, double starZ)
    {
        if (frameCount >= fX.length)
        {
            return;
        }
        double dx = x - camX;
        double dy = y - camY;
        double dz = z - camZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0)
        {
            return;
        }
        boolean sun = kind == K_SUN;
        double trueR = sun ? radius * STAR_DRAW_SCALE : radius;
        double honestPx = trueR / dist * pxScale;
        double drawR = trueR;
        if (!sun && honestPx < SpaceBodyRenderer.IMPOSTOR_HANDOVER_PX)
        {
            // ramp the far gain in as the body shrinks from the hand-over size to GAIN_FULL_PX.
            double t = (SpaceBodyRenderer.IMPOSTOR_HANDOVER_PX - honestPx)
                    / (SpaceBodyRenderer.IMPOSTOR_HANDOVER_PX - GAIN_FULL_PX);
            t = Math.max(0.0, Math.min(1.0, t));
            double gain = 1.0 + (FAR_SIZE_GAIN - 1.0) * t;
            drawR = Math.max(trueR, Math.min(trueR * gain, MAX_FAR_RADIUS));
        }
        double drawPx = drawR / dist * pxScale;
        float minPx = sun ? MIN_SUN_PX : MIN_PLANET_PX;
        float fade = 1.0F;
        if (drawPx < minPx)
        {
            fade = (float) (MIN_FADE + (1.0 - MIN_FADE) * (drawPx / minPx));
            drawR = minPx * dist / pxScale;
        }
        // the quad is larger than the disc (atmosphere / corona), so cull on that.
        double quadR = drawR / (sun ? STAR_DISC_FRACTION : DISC_FRACTION);
        double dot = dx * lookX + dy * lookY + dz * lookZ;
        if (dot + quadR < dist * cosHalfFov)
        {
            return;   // outside the view cone.
        }
        double scale = SpaceBodyRenderer.shellScale(dist);
        int i = frameCount++;
        fX[i] = (float) (dx * scale);
        fY[i] = (float) (dy * scale);
        fZ[i] = (float) (dz * scale);
        fR[i] = (float) (quadR * scale);
        fKind[i] = kind;
        fStyle[i] = (byte) style;
        fTint[i] = tint;
        fCol[i] = fade;
        if (!sun)
        {
            double lx = starX - x;
            double ly = starY - y;
            double lz = starZ - z;
            double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (ll < 1.0E-6)
            {
                lx = 1.0;
                ly = 0.0;
                lz = 0.0;
                ll = 1.0;
            }
            fLx[i] = (float) (lx / ll);
            fLy[i] = (float) (ly / ll);
            fLz[i] = (float) (lz / ll);
        }
        // sort key: distance (float bits are monotonic for positive floats) high, index low.
        fOrder[i] = ((long) Float.floatToIntBits((float) dist) << 32) | i;
    }

    /** Queue every distant-system source at its live orbital position for this frame. */
    static void addSources(long epochMillis)
    {
        for (int i = 0; i < srcCount; ++i)
        {
            if (srcKind[i] == K_SUN)
            {
                addBody(K_SUN, srcCx[i], srcCy[i], srcCz[i], srcRadius[i], srcTint[i], 0, 0.0, 0.0, 0.0);
                continue;
            }
            // Orbits.inclinedPosition, inlined so the frame allocates nothing. Keep it identical to that method.
            double revs = epochMillis / srcPeriod[i];
            double u = srcPhase[i] + Math.PI * 2.0 * (revs - Math.floor(revs));
            double ci = Math.cos(srcIncl[i]);
            double si = Math.sin(srcIncl[i]);
            double cu = Math.cos(u);
            double su = Math.sin(u);
            double node = srcNode[i];
            double cN = Math.cos(node);
            double sN = Math.sin(node);
            double cuN = Math.cos(u - node);
            double snU = Math.sin(u - node);
            double k = (1.0 - ci) * cuN;
            double r = srcRing[i];
            double x = srcCx[i] + r * (cu * ci + cN * k);
            double y = srcCy[i] + r * (snU * si);
            double z = srcCz[i] + r * (su * ci + sN * k);
            if (srcNear[i] != 0)
            {
                double ex = x - camX;
                double ey = y - camY;
                double ez = z - camZ;
                if (ex * ex + ey * ey + ez * ez <= nearRangeSq)
                {
                    continue;   // inside the near range: the renderer owns it.
                }
            }
            addBody(K_PLANET, x, y, z, srcRadius[i], srcTint[i], srcStyle[i], srcCx[i], srcCy[i], srcCz[i]);
        }
    }

    // ===== drawing =====

    /** Draw the queued impostors (far to near, one batch) and, when enabled, the orbit lines and belts beneath them. */
    static void draw(PoseStack poseStack, MultiBufferSource.BufferSource buffer, Camera camera, double sunX, double sunY,
                     double sunZ, long epochMillis)
    {
        if (!ensureAtlas())
        {
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        if (SpaceVisualsConfig.get().orbitLines)
        {
            drawOrbitLines(pose, buffer, sunX, sunY, sunZ);
        }
        VertexConsumer vc = buffer.getBuffer(Types.impostor(ATLAS));
        drawBelts(pose, vc, camera);
        if (frameCount > 0)
        {
            Arrays.sort(fOrder, 0, frameCount);
            Vector3f left = camera.getLeftVector();
            Vector3f up = camera.getUpVector();
            float rx = -left.x();
            float ry = -left.y();
            float rz = -left.z();
            float ux = up.x();
            float uy = up.y();
            float uz = up.z();
            for (int n = frameCount - 1; n >= 0; --n)
            {
                emit(pose, vc, (int) (fOrder[n] & 0xFFFFFFFFL), rx, ry, rz, ux, uy, uz);
            }
        }
        buffer.endBatch(Types.impostor(ATLAS));
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer vc, int i, float rx, float ry, float rz, float ux,
                             float uy, float uz)
    {
        float r = ((fTint[i] >> 16) & 0xFF) / 255.0F;
        float g = ((fTint[i] >> 8) & 0xFF) / 255.0F;
        float b = (fTint[i] & 0xFF) / 255.0F;
        int tile;
        float cosT = 1.0F;
        float sinT = 0.0F;
        if (fKind[i] == K_SUN)
        {
            tile = TILE_STAR;
            // a sun stays bright; lift it toward white so its core reads hot.
            r = 0.55F + 0.45F * r;
            g = 0.55F + 0.45F * g;
            b = 0.55F + 0.45F * b;
        }
        else
        {
            // light direction relative to the view: its component across the billboard (right/up) rolls the quad so the
            // tile's lit side (+u) faces it, and its component toward the camera picks the phase.
            float lx = fLx[i];
            float ly = fLy[i];
            float lz = fLz[i];
            float lr = lx * rx + ly * ry + lz * rz;
            float lu = lx * ux + ly * uy + lz * uz;
            // toward-camera unit vector is minus the (shell scaled) position direction.
            float px = fX[i];
            float py = fY[i];
            float pz = fZ[i];
            float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
            float toward = pl < 1.0E-6F ? 1.0F : -(lx * px + ly * py + lz * pz) / pl;
            float phase = (float) Math.acos(Math.max(-1.0F, Math.min(1.0F, toward)));
            int p = Math.round(phase / (float) Math.PI * (PHASES - 1));
            tile = fStyle[i] * PHASES + Math.max(0, Math.min(PHASES - 1, p));
            float across = (float) Math.sqrt(lr * lr + lu * lu);
            if (across > 1.0E-4F)
            {
                cosT = lr / across;
                sinT = lu / across;
            }
        }
        float a = fCol[i];
        r *= a;
        g *= a;
        b *= a;
        float s = fR[i];
        // the rolled billboard axes: e1 points to the lit side, e2 is perpendicular in the screen plane.
        float e1x = (rx * cosT + ux * sinT) * s;
        float e1y = (ry * cosT + uy * sinT) * s;
        float e1z = (rz * cosT + uz * sinT) * s;
        float e2x = (ux * cosT - rx * sinT) * s;
        float e2y = (uy * cosT - ry * sinT) * s;
        float e2z = (uz * cosT - rz * sinT) * s;
        float u0 = (tile % COLS) / (float) COLS;
        float v0 = (tile / COLS) / (float) ROWS;
        float u1 = u0 + 1.0F / COLS;
        float v1 = v0 + 1.0F / ROWS;
        float cx = fX[i];
        float cy = fY[i];
        float cz = fZ[i];
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        vertex(vc, m, nm, cx - e1x - e2x, cy - e1y - e2y, cz - e1z - e2z, r, g, b, 1.0F, u0, v1);
        vertex(vc, m, nm, cx + e1x - e2x, cy + e1y - e2y, cz + e1z - e2z, r, g, b, 1.0F, u1, v1);
        vertex(vc, m, nm, cx + e1x + e2x, cy + e1y + e2y, cz + e1z + e2z, r, g, b, 1.0F, u1, v0);
        vertex(vc, m, nm, cx - e1x + e2x, cy - e1y + e2y, cz - e1z + e2z, r, g, b, 1.0F, u0, v0);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f nm, float x, float y, float z, float r, float g,
                               float b, float a, float u, float v)
    {
        vc.vertex(m, x, y, z).color(r, g, b, a).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT)
                .normal(nm, 0.0F, 0.0F, 1.0F).endVertex();
    }

    // a point on a system's tilted orbital plane at angle u and radius r around (cx, cy, cz): the same rotation as
    // Orbits.inclinedPosition, written out so nothing is allocated. Result in outX/outY/outZ (render thread only).
    private static double outX;
    private static double outY;
    private static double outZ;

    private static void planePoint(double cx, double cy, double cz, double r, double u, double incl, double node)
    {
        double ci = Math.cos(incl);
        double si = Math.sin(incl);
        double cN = Math.cos(node);
        double sN = Math.sin(node);
        double k = (1.0 - ci) * Math.cos(u - node);
        outX = cx + r * (Math.cos(u) * ci + cN * k);
        outY = cy + r * (Math.sin(u - node) * si);
        outZ = cz + r * (Math.sin(u) * ci + sN * k);
    }

    private static void drawBelts(PoseStack.Pose pose, VertexConsumer vc, Camera camera)
    {
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        float u0 = (TILE_BELT % COLS) / (float) COLS;
        float v0 = (TILE_BELT / COLS) / (float) ROWS;
        float u1 = u0 + 1.0F / COLS;
        float v1 = v0 + 1.0F / ROWS;
        for (int s = 0; s < sysCount; ++s)
        {
            double ring = sysBelt[s];
            if (ring <= 0.0)
            {
                continue;
            }
            double dx = sysX[s] - camX;
            double dy = sysY[s] - camY;
            double dz = sysZ[s] - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < BELT_FADE_NEAR || dist > BELT_MAX_DISTANCE)
            {
                continue;
            }
            double reach = ring * 1.2;
            if (dx * lookX + dy * lookY + dz * lookZ + reach < dist * cosHalfFov)
            {
                continue;
            }
            float fade = (float) Math.min(1.0, (dist - BELT_FADE_NEAR) / (BELT_FADE_FAR - BELT_FADE_NEAR));
            // a belt too small to see is skipped.
            if (pixels(ring, dist) < 3.0)
            {
                continue;
            }
            float tr = ((sysTint[s] >> 16) & 0xFF) / 255.0F;
            float tg = ((sysTint[s] >> 8) & 0xFF) / 255.0F;
            float tb = (sysTint[s] & 0xFF) / 255.0F;
            float a = BELT_ALPHA * fade;
            float cr = (0.55F + 0.25F * tr) * a;
            float cg = (0.50F + 0.25F * tg) * a;
            float cb = (0.45F + 0.25F * tb) * a;
            double half = GeneratedSystems.ringStep() * 0.32;
            for (int k = 0; k < BELT_SEGMENTS; ++k)
            {
                double ua = Math.PI * 2.0 * k / BELT_SEGMENTS;
                double ub = Math.PI * 2.0 * (k + 1) / BELT_SEGMENTS;
                planePoint(sysX[s], sysY[s], sysZ[s], ring - half, ua, sysIncl[s], sysNode[s]);
                double ax = outX, ay = outY, az = outZ;
                planePoint(sysX[s], sysY[s], sysZ[s], ring + half, ua, sysIncl[s], sysNode[s]);
                double bx = outX, by = outY, bz = outZ;
                planePoint(sysX[s], sysY[s], sysZ[s], ring + half, ub, sysIncl[s], sysNode[s]);
                double cx = outX, cy = outY, cz = outZ;
                planePoint(sysX[s], sysY[s], sysZ[s], ring - half, ub, sysIncl[s], sysNode[s]);
                double ex = outX, ey = outY, ez = outZ;
                float ku0 = u0 + (u1 - u0) * (k % 4) / 4.0F;
                float ku1 = ku0 + (u1 - u0) / 4.0F;
                shellVertex(vc, m, nm, ax, ay, az, cr, cg, cb, ku0, v0);
                shellVertex(vc, m, nm, bx, by, bz, cr, cg, cb, ku0, v1);
                shellVertex(vc, m, nm, cx, cy, cz, cr, cg, cb, ku1, v1);
                shellVertex(vc, m, nm, ex, ey, ez, cr, cg, cb, ku1, v0);
            }
        }
    }

    // a world point drawn through the shell map (its own distance), so its screen position is exact and its depth sorts
    // with the bodies.
    private static void shellVertex(VertexConsumer vc, Matrix4f m, Matrix3f nm, double wx, double wy, double wz,
                                    float r, float g, float b, float u, float v)
    {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double s = SpaceBodyRenderer.shellScale(d);
        vertex(vc, m, nm, (float) (dx * s), (float) (dy * s), (float) (dz * s), r, g, b, 1.0F, u, v);
    }

    private static void drawOrbitLines(PoseStack.Pose pose, MultiBufferSource.BufferSource buffer, double sunX,
                                       double sunY, double sunZ)
    {
        VertexConsumer vc = buffer.getBuffer(Types.orbitLines());
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        // the main system's rings around the central sun.
        for (int i = 0; i < mainRingCount; ++i)
        {
            ring(vc, m, nm, sunX, sunY, sunZ, mainRings[i], mainRingIncl[i], mainRingNode[i], 0.62F, 0.78F, 1.0F,
                    ORBIT_LINE_ALPHA);
        }
        // nearby systems' planet orbits.
        for (int s = 0; s < sysCount; ++s)
        {
            double dx = sysX[s] - camX;
            double dy = sysY[s] - camY;
            double dz = sysZ[s] - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > ORBIT_LINE_RANGE || sysPlanets[s] == 0)
            {
                continue;
            }
            double reach = sysOuter[s] + 200.0;
            if (dx * lookX + dy * lookY + dz * lookZ + reach < dist * cosHalfFov)
            {
                continue;
            }
            float fade = (float) Math.min(1.0, (ORBIT_LINE_RANGE - dist) / (ORBIT_LINE_RANGE * 0.25));
            for (int p = sysFirst[s]; p < sysFirst[s] + sysPlanets[s]; ++p)
            {
                ring(vc, m, nm, sysX[s], sysY[s], sysZ[s], srcRing[p], srcIncl[p], srcNode[p], 0.75F, 0.8F, 0.95F,
                        ORBIT_LINE_ALPHA * 0.8F * fade);
            }
        }
        buffer.endBatch(Types.orbitLines());
    }

    // one orbit ring as a closed line strip, its segment count scaled to its on-screen size.
    private static void ring(VertexConsumer vc, Matrix4f m, Matrix3f nm, double cx, double cy, double cz, double r,
                             double incl, double node, float red, float green, float blue, float alpha)
    {
        double dx = cx - camX;
        double dy = cy - camY;
        double dz = cz - camZ;
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - r);
        int segs = (int) Math.max(24, Math.min(160, pixels(r, dist) / 6.0));
        planePoint(cx, cy, cz, r, 0.0, incl, node);
        double px = outX, py = outY, pz = outZ;
        for (int k = 1; k <= segs; ++k)
        {
            planePoint(cx, cy, cz, r, Math.PI * 2.0 * k / segs, incl, node);
            lineVertex(vc, m, nm, px, py, pz, outX, outY, outZ, red, green, blue, alpha);
            lineVertex(vc, m, nm, outX, outY, outZ, px, py, pz, red, green, blue, alpha);
            px = outX;
            py = outY;
            pz = outZ;
        }
    }

    private static void lineVertex(VertexConsumer vc, Matrix4f m, Matrix3f nm, double wx, double wy, double wz,
                                   double ox, double oy, double oz, float r, float g, float b, float a)
    {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;
        double s = SpaceBodyRenderer.shellScale(Math.sqrt(dx * dx + dy * dy + dz * dz));
        float x = (float) (dx * s);
        float y = (float) (dy * s);
        float z = (float) (dz * s);
        double ex = ox - camX;
        double ey = oy - camY;
        double ez = oz - camZ;
        double so = SpaceBodyRenderer.shellScale(Math.sqrt(ex * ex + ey * ey + ez * ez));
        float nx = (float) (ex * so) - x;
        float ny = (float) (ey * so) - y;
        float nz = (float) (ez * so) - z;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0E-7F)
        {
            nx = 1.0F;
            nl = 1.0F;
        }
        vc.vertex(m, x, y, z).color(r, g, b, a).normal(nm, nx / nl, ny / nl, nz / nl).endVertex();
    }

    // ===== atlas generation (once, on the render thread) =====

    private static boolean ensureAtlas()
    {
        if (atlasReady)
        {
            return true;
        }
        if (atlasFailed)
        {
            return false;
        }
        try
        {
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, COLS * TILE, ROWS * TILE, true);
            for (int s = 0; s < STYLES; ++s)
            {
                for (int p = 0; p < PHASES; ++p)
                {
                    paintPlanet(img, s * PHASES + p, s, Math.PI * p / (PHASES - 1));
                }
            }
            paintStar(img, TILE_STAR);
            paintBelt(img, TILE_BELT);
            DynamicTexture tex = new DynamicTexture(img);
            Minecraft.getInstance().getTextureManager().register(ATLAS, tex);
            atlasReady = true;
            return true;
        }
        catch (Throwable t)
        {
            atlasFailed = true;
            return false;
        }
    }

    private static void put(NativeImage img, int tile, int px, int py, float r, float g, float b, float a)
    {
        int x = (tile % COLS) * TILE + px;
        int y = (tile / COLS) * TILE + py;
        int ir = clamp255(r);
        int ig = clamp255(g);
        int ib = clamp255(b);
        int ia = clamp255(a);
        // NativeImage pixels are ABGR.
        img.setPixelRGBA(x, y, (ia << 24) | (ib << 16) | (ig << 8) | ir);
    }

    private static int clamp255(float v)
    {
        return Math.max(0, Math.min(255, Math.round(v * 255.0F)));
    }

    // one planet tile: a sphere lit from the +u side at the given phase angle (0 = full, pi = new), with its surface
    // style as luminance and an atmosphere rim. Tinted later by the vertex colour.
    private static void paintPlanet(NativeImage img, int tile, int style, double phase)
    {
        double lx = Math.sin(phase);
        double lz = Math.cos(phase);
        for (int py = 0; py < TILE; ++py)
        {
            for (int px = 0; px < TILE; ++px)
            {
                double x = ((px + 0.5) / TILE) * 2.0 - 1.0;
                double y = 1.0 - ((py + 0.5) / TILE) * 2.0;
                double rr = Math.sqrt(x * x + y * y);
                double rd = rr / DISC_FRACTION;
                if (rd <= 1.0)
                {
                    double nx = x / DISC_FRACTION;
                    double ny = y / DISC_FRACTION;
                    double nz = Math.sqrt(Math.max(0.0, 1.0 - nx * nx - ny * ny));
                    double d = nx * lx + nz * lz;
                    double day = smooth(-0.10, 0.30, d) * (0.45 + 0.55 * Math.max(0.0, d));
                    double surface = surface(style, nx, ny, nz);
                    double limb = 0.78 + 0.22 * nz;
                    double fres = Math.pow(1.0 - nz, 3.0) * 0.35 * smooth(-0.2, 0.4, d);
                    double v = 0.035 + day * surface * limb + fres;
                    // anti-alias the disc edge into the glow.
                    float a = (float) Math.min(1.0, (1.0 - rd) * TILE * DISC_FRACTION * 0.5 + 0.5);
                    float glowA = (float) glowAlpha(x, y, rr, lx, lz);
                    float outA = a + glowA * (1.0F - a);
                    float c = (float) (outA <= 0.0F ? 0.0 : (v * a + glowA * (1.0F - a)) / outA);
                    put(img, tile, px, py, c, c, c, outA);
                }
                else
                {
                    float glowA = (float) glowAlpha(x, y, rr, lx, lz);
                    put(img, tile, px, py, 1.0F, 1.0F, 1.0F, glowA);
                }
            }
        }
    }

    // the atmosphere glow just outside the disc: fades out to the tile edge, brightest on the lit limb.
    private static double glowAlpha(double x, double y, double rr, double lx, double lz)
    {
        if (rr < DISC_FRACTION * 0.92 || rr >= 1.0)
        {
            return 0.0;
        }
        double t = (rr - DISC_FRACTION * 0.92) / (1.0 - DISC_FRACTION * 0.92);
        double fall = Math.pow(1.0 - t, 2.4);
        double limbLight = (rr > 1.0E-6 ? x / rr : 0.0) * lx + 0.25 * lz;
        double lit = smooth(-0.35, 0.6, limbLight);
        return 0.5 * fall * (0.03 + 0.97 * lit);
    }

    // luminance of a surface style at a point on the unit sphere (0..1).
    private static double surface(int style, double x, double y, double z)
    {
        switch (style)
        {
            case STYLE_BANDED:
            {
                double warp = fbm(x * 2.0, y * 2.0, z * 2.0, 11) - 0.5;
                return 0.78 + 0.18 * Math.sin(y * 11.0 + warp * 3.0) + 0.06 * (fbm(x * 6, y * 18, z * 6, 12) - 0.5);
            }
            case STYLE_OCEAN:
            {
                double n = fbm(x * 2.2, y * 2.2, z * 2.2, 21);
                double land = smooth(0.50, 0.56, n);
                double v = 0.55 + 0.4 * land + 0.08 * (fbm(x * 8, y * 8, z * 8, 22) - 0.5);
                double cap = smooth(0.80, 0.90, Math.abs(y));
                double cloud = smooth(0.58, 0.72, fbm(x * 3.5 + 7, y * 3.5, z * 3.5, 23)) * 0.35;
                return Math.min(1.0, v + cap * 0.4 + cloud);
            }
            case STYLE_ICE:
                return 0.86 + 0.14 * (fbm(x * 3.0, y * 3.0, z * 3.0, 31) - 0.5) * 2.0;
            case STYLE_ROCKY:
            default:
            {
                double n = fbm(x * 3.0, y * 3.0, z * 3.0, 41);
                double craters = smooth(0.62, 0.70, fbm(x * 7.0, y * 7.0, z * 7.0, 42));
                return 0.70 + 0.45 * (n - 0.5) - 0.18 * craters;
            }
        }
    }

    // the sun tile: a limb-darkened disc and a soft corona.
    private static void paintStar(NativeImage img, int tile)
    {
        for (int py = 0; py < TILE; ++py)
        {
            for (int px = 0; px < TILE; ++px)
            {
                double x = ((px + 0.5) / TILE) * 2.0 - 1.0;
                double y = 1.0 - ((py + 0.5) / TILE) * 2.0;
                double rr = Math.sqrt(x * x + y * y);
                double rd = rr / STAR_DISC_FRACTION;
                double corona = rr >= 1.0 ? 0.0 : Math.pow(1.0 - rr, 2.6) * 0.75;
                if (rd <= 1.0)
                {
                    double nz = Math.sqrt(Math.max(0.0, 1.0 - rd * rd));
                    float c = (float) (0.80 + 0.20 * Math.sqrt(nz));
                    float a = (float) Math.min(1.0, (1.0 - rd) * TILE * STAR_DISC_FRACTION * 0.5 + 0.5);
                    float outA = a + (float) corona * (1.0F - a);
                    put(img, tile, px, py, c, c, c, outA);
                }
                else
                {
                    put(img, tile, px, py, 1.0F, 1.0F, 1.0F, (float) corona);
                }
            }
        }
    }

    // the belt strip: grainy dust, denser in the middle of the strip (v), alpha only.
    private static void paintBelt(NativeImage img, int tile)
    {
        for (int py = 0; py < TILE; ++py)
        {
            double v = (py + 0.5) / TILE * 2.0 - 1.0;
            double across = Math.pow(Math.max(0.0, 1.0 - v * v), 1.5);
            for (int px = 0; px < TILE; ++px)
            {
                double grain = fbm(px * 0.35, py * 0.35, 3.0, 51);
                double specks = ((mixHash(px * 73856093L ^ py * 19349663L) >>> 20) & 0xFF) / 255.0;
                double a = across * (0.35 + 0.65 * grain) * (specks > 0.86 ? 1.0 : 0.55);
                put(img, tile, px, py, 1.0F, 1.0F, 1.0F, (float) a);
            }
        }
    }

    private static double smooth(double e0, double e1, double v)
    {
        double t = Math.max(0.0, Math.min(1.0, (v - e0) / (e1 - e0)));
        return t * t * (3.0 - 2.0 * t);
    }

    // 3 octave value noise in [0,1].
    private static double fbm(double x, double y, double z, int seed)
    {
        double sum = 0.0;
        double amp = 0.5;
        double norm = 0.0;
        for (int o = 0; o < 3; ++o)
        {
            sum += amp * valueNoise(x, y, z, seed + o * 101);
            norm += amp;
            x *= 2.03;
            y *= 2.03;
            z *= 2.03;
            amp *= 0.5;
        }
        return sum / norm;
    }

    private static double valueNoise(double x, double y, double z, int seed)
    {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        double fx = x - ix;
        double fy = y - iy;
        double fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double c000 = lattice(ix, iy, iz, seed);
        double c100 = lattice(ix + 1, iy, iz, seed);
        double c010 = lattice(ix, iy + 1, iz, seed);
        double c110 = lattice(ix + 1, iy + 1, iz, seed);
        double c001 = lattice(ix, iy, iz + 1, seed);
        double c101 = lattice(ix + 1, iy, iz + 1, seed);
        double c011 = lattice(ix, iy + 1, iz + 1, seed);
        double c111 = lattice(ix + 1, iy + 1, iz + 1, seed);
        double x00 = c000 + (c100 - c000) * fx;
        double x10 = c010 + (c110 - c010) * fx;
        double x01 = c001 + (c101 - c001) * fx;
        double x11 = c011 + (c111 - c011) * fx;
        double y0 = x00 + (x10 - x00) * fy;
        double y1 = x01 + (x11 - x01) * fy;
        return y0 + (y1 - y0) * fz;
    }

    private static double lattice(int x, int y, int z, int seed)
    {
        long h = mixHash(x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ seed);
        return ((h >>> 11) & 0xFFFFF) / (double) 0xFFFFF;
    }

    static long mixHash(long z)
    {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ===== render types =====

    // The impostor batch: a stock entity shader (so shader packs map it like any entity), translucent blend, no cull,
    // colour writes only, LEQUAL against the bodies' committed depth so a nearer opaque body still hides what is behind it.
    // The orbit lines: the stock lines shader, translucent, no depth writes, LEQUAL.
    private static final class Types extends RenderType
    {
        private static RenderType impostorType;
        private static RenderType linesType;

        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean affectsCrumbling,
                      boolean sortOnUpload, Runnable setup, Runnable clear)
        {
            super(name, format, mode, size, affectsCrumbling, sortOnUpload, setup, clear);
            throw new UnsupportedOperationException();
        }

        static RenderType impostor(ResourceLocation texture)
        {
            if (impostorType == null)
            {
                CompositeState state = CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                        .setTextureState(new TextureStateShard(texture, true, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .createCompositeState(false);
                impostorType = create("dmzr_space_impostor", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                        262144, false, false, state);
            }
            return impostorType;
        }

        static RenderType orbitLines()
        {
            if (linesType == null)
            {
                CompositeState state = CompositeState.builder()
                        .setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new LineStateShard(java.util.OptionalDouble.of(1.0)))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .createCompositeState(false);
                linesType = create("dmzr_space_orbit_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                        VertexFormat.Mode.LINES, 65536, false, false, state);
            }
            return linesType;
        }
    }
}
