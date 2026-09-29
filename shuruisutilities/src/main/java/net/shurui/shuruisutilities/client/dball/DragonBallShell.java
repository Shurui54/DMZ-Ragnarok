package net.shurui.shuruisutilities.client.dball;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Shared drawing for a placed dragon ball rendered as ONE translucent glass sphere with its stars floating on a
 * camera-facing billboard inside it, instead of the opaque multi-cube geo with stars painted on the surface. This is
 * the block-scale twin of {@code SpaceBodyRenderer.drawSuper}: the space side proved the pattern on the planet-sized
 * super body, and every ball geo (DMZ dball 12 cubes, dball_super4x 13, dball_cerulean_half 12, corrupted_dball 12)
 * hit the same wall when drawn translucent, so every ball is drawn the same way here.
 *
 * <p>WHY a sphere and not the real geo. Each geo is a shell of thin panel cubes; opaque they read as one rounded
 * ball, but translucent every panel contributes a front AND a back face and the overlaps stack into layer upon layer
 * of glass (the "incredibly buggy" look the user rejected on the planet body). That artifact is a property of the
 * GEOMETRY, not the world size, so it is no better at a 0.5 block marble than at a 160 block planet, and the player
 * is usually CLOSER to a placed ball. A single closed UV sphere has exactly one surface, so a culling translucent
 * type leaves one clean blended layer.
 *
 * <p>Draw order. Stars are drawn first, the buffer is flushed, then the shell is drawn over them, so the translucent
 * shell blends in front of the stars and they read as sitting inside the glass. A {@code BufferSource} flushes by
 * render type rather than submission order, so the explicit flush is what forces the shell to paint after the stars
 * (the same trick {@code SpaceBodyRenderer} uses per body).
 */
public final class DragonBallShell
{
    private DragonBallShell()
    {
    }

    // the classic red pips of an Earth/Super style dragon ball, and the dark pips of the Black Star / corrupted balls.
    // Drawn emissive so they keep a fixed brightness through the translucent shell whatever the block light.
    public static final float[] PIP_RED = { 0.86F, 0.13F, 0.02F };
    public static final float[] PIP_DARK = { 0.14F, 0.14F, 0.14F };

    // a plain white sheet the shell sphere samples and the caller multiplies by the ball colour. Reused from the space
    // renderer (one all-white pixel), so a flat tint is all it carries. Public because the CUBE-style renderers draw
    // their rim hull with this same flat sheet through {@link BallRenderTypes#rim} (a lighter tint over white, so the
    // rim is a flat lighter shade of the body colour and never the detailed surface texture).
    public static final ResourceLocation SHELL_SHEET =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/star_body.png");

    private static final int FULL_BRIGHT = 0x00F000F0;

    // RIM: the outer highlight the reference art shows, a thin ring of a slightly LIGHTER shade of the ball's own body
    // colour hugging the silhouette. Rendered as an inverted hull: a concentric copy of the ball scaled up by RIM_SCALE
    // from which only the BACK faces are drawn, so inside the silhouette those far faces fail the depth test against the
    // body's already-written surface and are discarded, while just outside the silhouette (where the body wrote no
    // depth) they pass and paint the ring. Shared by every ball style so a future set gets a correct rim for free.
    public static final float RIM_SCALE = 1.08F;
    public static final float RIM_ALPHA = 0.9F;

    // CUBE-style rim scale, for the SINGLE convex hull the cube rim now draws (DragonBallHull): one closed surface hugging
    // the ball's real silhouette, enlarged about the ball centre and reverse-wound. A convex hull hugs as tightly as the
    // sphere hull, so the value sits just above the sphere's 1.08 (a faceted hull reads a touch thinner along its flat
    // regions). Because the rim hugs the body through the LEQUAL test it never floats as a detached shell, only thickens
    // outward from the true silhouette, so it can never leave the oversized-box corners the old union AABB showed. This is
    // a MULTIPLICATIVE scale so it works across the whole 12x placed-ball size range AND the far larger space super body,
    // where a fixed world thickness would vanish; the min-thickness floor below keeps the tiniest ball's ring readable.
    public static final float RIM_HULL_SCALE = 1.10F;

    // a floor, in the hull's own coordinate units, on the outward offset of each rim vertex, so a very small ball still
    // shows a ring the multiplicative scale alone would make sub-pixel. Applied as max((scale-1)*radialDist, thickness)
    // per vertex. At 0.02 it only bites the tiniest set (cerulean, half-extent about 0.12), giving it roughly the outward
    // margin earth already gets from the scale; on every larger ball the scale dominates and the floor is inert. For the
    // space super body the hull units are the normalised (half-extent 1) geo, multiplied by the body radius at draw time,
    // so this floor scales with the body there and the scale still dominates. Kept separate so the two tune independently.
    public static final float RIM_HULL_MIN_THICKNESS = 0.02F;

    // how far the rim colour is lerped toward white from the body colour. One shared function, no per-set constants:
    // any body colour yields a matching lighter rim. 0.4 reads as a clear lighter shade without washing out the hue.
    private static final float RIM_LIGHTEN = 0.4F;

    /** A slightly lighter shade of the given RGB (lerp toward white), for the outer rim highlight. */
    public static int lighten(int rgb)
    {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = Math.round(r + (255 - r) * RIM_LIGHTEN);
        g = Math.round(g + (255 - g) * RIM_LIGHTEN);
        b = Math.round(b + (255 - b) * RIM_LIGHTEN);
        return (r << 16) | (g << 8) | b;
    }

    // latitude rings and longitude sectors of the shell. 12x18 is smooth at block scale and only a few hundred quads
    // for the handful of balls ever in view at once.
    private static final int SPHERE_RINGS = 12;
    private static final int SPHERE_SECTORS = 18;

    // the ball turns slowly so a claimed ball's inner stars are not dead still. Degrees per client tick.
    private static final float SPIN_DEG_PER_TICK = 0.4F;

    private static ResourceLocation starSprite(int stars)
    {
        int n = Math.max(1, Math.min(7, stars));
        return new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/dball_stars/star" + n + ".png");
    }

    /**
     * Draw a placed ball as a translucent sphere plus an inner star billboard, in the block-local space a
     * {@code BlockEntityRenderer} is handed (the unit cube with (0,0,0) at the block corner). {@code centerY} and
     * {@code radius} are in blocks and are taken from the ball's own geo bounds so the drawn ball sits exactly where
     * the old geo did.
     *
     * @param stars 1..7; the star arrangement painted on the inner billboard. Pass 0 to draw no stars (bare shell).
     * @param pip   the star tint (PIP_RED or PIP_DARK).
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, double tickCount,
                              float radius, float centerY, int baseRgb, float[] pip, int stars)
    {
        float r = ((baseRgb >> 16) & 0xFF) / 255.0F;
        float g = ((baseRgb >> 8) & 0xFF) / 255.0F;
        float b = (baseRgb & 0xFF) / 255.0F;
        float alpha = (float) SUConfig.dragonBallAlpha;

        MultiBufferSource.BufferSource flushable =
                buffer instanceof MultiBufferSource.BufferSource bs ? bs : null;

        poseStack.pushPose();
        poseStack.translate(0.5D, centerY, 0.5D);

        // INNER STARS FIRST, then flush, then the shell over them. The two use different render types (an emissive
        // star sprite vs the translucent shell sheet) and a BufferSource flushes by render type, so ending the star
        // batch before the shell is what makes the shell blend in front of the stars rather than behind them.
        if (stars >= 1 && flushable != null)
        {
            drawStars(poseStack, buffer, radius, pip, stars);
            flushable.endBatch();
        }

        // Wrapped into 0..360 before it narrows to a float. A long-lived world is tens of millions of ticks in
        // (production is past 41,500,000), and both the tick count itself and the accumulated angle lose all their
        // fractional precision at that size, which makes a spin advance in visible steps instead of turning smoothly.
        float spin = (float) (((tickCount * SPIN_DEG_PER_TICK) % 360.0 + 360.0) % 360.0);

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        // a gentle tilt so the spin axis is not dead vertical, matching the space super body.
        poseStack.mulPose(Axis.ZP.rotationDegrees(12.0F));
        // entityTranslucentCull: a CULLING translucent type, so the sphere's inward faces drop and only the one
        // outward layer blends. The shell uses the real block light so a ball in a dark cave reads dark.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentCull(SHELL_SHEET));
        drawSphere(poseStack.last(), vc, radius, r, g, b, alpha, packedLight, false);
        poseStack.popPose();

        // flush the shell so its colour and depth land before the rim: a BufferSource batches by render type, and the
        // rim's LEQUAL test must run against the committed shell surface depth.
        if (flushable != null)
        {
            flushable.endBatch();
        }

        poseStack.popPose();

        // RIM last, emissive, drawn once through the shared helper so both ball styles and both ball renderers match.
        renderRim(poseStack, buffer, radius, centerY, baseRgb);
    }

    /**
     * Draw a Z orb: the SAME translucent culled shell and reverse-wound rim a placed dragon ball uses, but centred on
     * the current pose origin (no block-corner offset and no star billboard) and at a caller-chosen radius, colour and
     * alpha. It reuses the existing private {@link #drawSphere} primitive and the {@link BallRenderTypes#rim} pass, so a
     * Z orb reads as the same glass ball the dragon balls do without touching how a dragon ball renders. The orb draws
     * emissive ({@link #FULL_BRIGHT}) so it glows like the energy it is, whatever the world light.
     *
     * <p>Requires a flushable buffer (an entity render always has one). The caller draws the emissive core / inner item
     * first and flushes, so this shell blends over them exactly as the dragon ball shell blends over its stars.
     */
    public static void renderOrb(PoseStack poseStack, MultiBufferSource buffer, float radius, int rgb, float alpha)
    {
        MultiBufferSource.BufferSource flushable =
                buffer instanceof MultiBufferSource.BufferSource bs ? bs : null;
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;

        // translucent shell, one clean culled layer, at the orb's colour and alpha.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentCull(SHELL_SHEET));
        drawSphere(poseStack.last(), vc, radius, r, g, b, alpha, FULL_BRIGHT, false);
        if (flushable != null)
            flushable.endBatch();

        // rim, exactly the sphere rim technique but centred on the orb (LEQUAL against the committed shell depth).
        if (flushable != null)
        {
            int rimRgb = lighten(rgb);
            float rr = ((rimRgb >> 16) & 0xFF) / 255.0F;
            float rg = ((rimRgb >> 8) & 0xFF) / 255.0F;
            float rb = (rimRgb & 0xFF) / 255.0F;
            RenderType rim = BallRenderTypes.rim(SHELL_SHEET);
            VertexConsumer rimVc = buffer.getBuffer(rim);
            drawSphere(poseStack.last(), rimVc, radius * RIM_SCALE, rr, rg, rb, RIM_ALPHA, FULL_BRIGHT, true);
            flushable.endBatch(rim);
        }
    }

    /**
     * Draw ONLY the outer rim highlight for a placed ball: a concentric sphere hull one {@link #RIM_SCALE} larger than
     * the body, wound in REVERSE so back-face culling ({@link BallRenderTypes#rim} uses CULL) keeps only the FAR
     * hemisphere. Against the body surface depth the caller has already committed, the interior fails the rim's LEQUAL
     * test and is discarded, so only the thin lighter ring at the silhouette survives. Shared by SPHERE style (over its
     * translucent shell) and CUBE style (over the two-pass translucent geo), so every ball gets the same rim and a new
     * set gets one for free.
     *
     * <p>Emissive: the rim, and only the rim, is drawn at {@link #FULL_BRIGHT} packed light, so it glows at night and in
     * caves whatever the world light, while the body and inner star keep their own lighting. The rim uses a winding
     * controlled sphere hull rather than an enlarged GeckoLib cube: GeckoLib geometry has no guaranteed winding (see
     * {@link BallRenderTypes}), so selecting its back faces via a manual {@code glCullFace} flip was unreliable and the
     * ring did not appear in CUBE style; the reverse-wound sphere is the same proven technique the space super body uses.
     *
     * <p>Requires a flushable buffer so the rim batch can be ended after the body's; for a block entity that is always
     * true. Call AFTER the body passes have been drawn and flushed so the body depth is in the buffer.
     */
    public static void renderRim(PoseStack poseStack, MultiBufferSource buffer, float radius, float centerY, int baseRgb)
    {
        MultiBufferSource.BufferSource flushable =
                buffer instanceof MultiBufferSource.BufferSource bs ? bs : null;
        if (flushable == null)
        {
            return;
        }
        int rimRgb = lighten(baseRgb);
        float rr = ((rimRgb >> 16) & 0xFF) / 255.0F;
        float rg = ((rimRgb >> 8) & 0xFF) / 255.0F;
        float rb = (rimRgb & 0xFF) / 255.0F;
        RenderType rim = BallRenderTypes.rim(SHELL_SHEET);
        poseStack.pushPose();
        poseStack.translate(0.5D, centerY, 0.5D);
        VertexConsumer rimVc = buffer.getBuffer(rim);
        drawSphere(poseStack.last(), rimVc, radius * RIM_SCALE, rr, rg, rb, RIM_ALPHA, FULL_BRIGHT, true);
        poseStack.popPose();
        flushable.endBatch(rim);
    }

    /**
     * Draw ONLY the outer rim highlight for a CUBE-style ball: the SINGLE convex hull of the set's own faceted geo
     * ({@link DragonBallHull}), enlarged about the ball centre and wound in REVERSE, so the outline is exactly one edge
     * following the ball's real silhouette. This replaces both earlier forms the user rejected: the per-cube hull (which
     * drew an outline on every internal panel edge) and the single union AABB (whose empty corners ballooned past the
     * round body, worst on the super ball). A convex hull is the tightest single closed surface over all the corner
     * points, and every ball geo is a faceted sphere, so its hull hugs the silhouette; drawn reversed through the shared
     * CULLING rim type ({@link BallRenderTypes#rim}), only the FAR faces survive, and against the body depth the caller
     * committed the interior fails the rim's LEQUAL test, leaving just the thin lighter ring at the edge. No {@code GL11}
     * cull flip and no winding assumption about GeckoLib output: the winding is ours because we build the hull ourselves.
     *
     * <p>Emissive at {@link #FULL_BRIGHT} and the same {@link #lighten}ed body colour as the sphere rim, so both styles
     * match; the body and inner star keep their own lighting. The hull is enlarged about the ball's own centre
     * {@code (0, centerY, 0)} (not the block centre) so the ring is even all round, by {@link #RIM_HULL_SCALE} with the
     * per-vertex {@link #RIM_HULL_MIN_THICKNESS} floor.
     *
     * @param boxes the set's geo as {@code {x0,y0,z0, x1,y1,z1}} rows in block units, from {@link DragonBallCubeGeo}.
     * @param centerY the ball's own centre height in blocks (the same value the body and the sphere rim use).
     *
     * <p>Requires a flushable buffer; a block entity always has one. Call AFTER the body passes are drawn and flushed.
     */
    public static void renderHullRim(PoseStack poseStack, MultiBufferSource buffer, float[][] boxes, float centerY,
                                     int baseRgb)
    {
        MultiBufferSource.BufferSource flushable =
                buffer instanceof MultiBufferSource.BufferSource bs ? bs : null;
        if (flushable == null)
        {
            return;
        }
        int rimRgb = lighten(baseRgb);
        float rr = ((rimRgb >> 16) & 0xFF) / 255.0F;
        float rg = ((rimRgb >> 8) & 0xFF) / 255.0F;
        float rb = (rimRgb & 0xFF) / 255.0F;
        float[][] tris = DragonBallHull.hull(boxes);
        RenderType rim = BallRenderTypes.rim(SHELL_SHEET);
        poseStack.pushPose();
        // the block-local frame GeckoLib renders the geo in: translate to the block's horizontal centre, geo y from 0 up.
        // The hull carries its real geo y, so no centerY translate here; the enlarge below is what centres the growth.
        poseStack.translate(0.5D, 0.0D, 0.5D);
        VertexConsumer rimVc = buffer.getBuffer(rim);
        drawHullInverted(poseStack.last(), rimVc, tris, 0.0F, centerY, 0.0F, RIM_HULL_SCALE, RIM_HULL_MIN_THICKNESS,
                1.0F, rr, rg, rb, RIM_ALPHA, FULL_BRIGHT);
        poseStack.popPose();
        flushable.endBatch(rim);
    }

    /**
     * Draw a convex hull's triangles enlarged about {@code (cx, cy, cz)} and wound in REVERSE, so a CULLING render type
     * keeps only the faces pointing AWAY from the camera (the inverted-hull rim). The single shared hull-rim drawer for
     * both the placed CUBE ball ({@link #renderHullRim}) and the space super body
     * ({@code SpaceBodyRenderer.drawSuperRimGeo}), so there is exactly one copy of the enlarge/reverse/draw logic.
     *
     * <p>Each vertex is pushed outward from the centre by a factor {@code max(scale, 1 + minThickness/radialDist)}: the
     * multiplicative {@code scale} dominates on all but the tiniest ball, where the {@code minThickness} floor keeps the
     * ring readable. Every vertex is then multiplied by {@code outputScale} so a caller whose hull is normalised (the
     * space super body, hull in half-extent-1 units) can scale it up by the body radius at draw time; the placed balls
     * pass {@code outputScale = 1} because their hull is already in block units.
     *
     * @param tris outward-wound triangle rows {@code {ax,ay,az, bx,by,bz, cx,cy,cz}} from {@link DragonBallHull}.
     */
    public static void drawHullInverted(Pose pose, VertexConsumer vc, float[][] tris, float cx, float cy, float cz,
                                        float scale, float minThickness, float outputScale, float r, float g, float b,
                                        float alpha, int light)
    {
        for (float[] t : tris)
        {
            float[] a = enlarge(t[0], t[1], t[2], cx, cy, cz, scale, minThickness, outputScale);
            float[] bb = enlarge(t[3], t[4], t[5], cx, cy, cz, scale, minThickness, outputScale);
            float[] c = enlarge(t[6], t[7], t[8], cx, cy, cz, scale, minThickness, outputScale);
            // REVERSE the outward winding (a,b,c) -> (a,c,b) so the CULL rim type keeps only the far faces, then emit as a
            // degenerate quad (v0,v1,v2,v2): QUADS mode splits it into (a,c,b) plus a zero-area (a,b,b) triangle.
            hullQuad(pose, vc, a, c, bb, r, g, b, alpha, light);
        }
    }

    // push a hull vertex outward from the centre, apply the min-thickness floor, then the uniform output scale.
    private static float[] enlarge(float px, float py, float pz, float cx, float cy, float cz, float scale,
                                   float minThickness, float outputScale)
    {
        float vx = px - cx;
        float vy = py - cy;
        float vz = pz - cz;
        float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        float f = scale;
        if (len > 1.0E-6F)
        {
            float floorF = 1.0F + minThickness / len;
            if (floorF > f)
            {
                f = floorF;
            }
        }
        return new float[] {
                (cx + vx * f) * outputScale,
                (cy + vy * f) * outputScale,
                (cz + vz * f) * outputScale
        };
    }

    // one flat-tinted triangle of the inverted hull, emitted as a degenerate quad (last vertex repeated). UV is the whole
    // 0..1 white sheet, so the rim is a flat lighter tint. The normal is computed from the emitted (reversed) winding for
    // consistency; back-face culling uses the screen-space winding, not this attribute, and the rim is emissive anyway.
    private static void hullQuad(Pose pose, VertexConsumer vc, float[] v0, float[] v1, float[] v2, float r, float g,
                                 float b, float alpha, int light)
    {
        float ux = v1[0] - v0[0], uy = v1[1] - v0[1], uz = v1[2] - v0[2];
        float wx = v2[0] - v0[0], wy = v2[1] - v0[1], wz = v2[2] - v0[2];
        float nx = uy * wz - uz * wy;
        float ny = uz * wx - ux * wz;
        float nz = ux * wy - uy * wx;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl > 1.0E-6F)
        {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        }
        else
        {
            nx = 0.0F;
            ny = 1.0F;
            nz = 0.0F;
        }
        hullVertex(pose, vc, v0, 0.0F, 0.0F, nx, ny, nz, r, g, b, alpha, light);
        hullVertex(pose, vc, v1, 1.0F, 0.0F, nx, ny, nz, r, g, b, alpha, light);
        hullVertex(pose, vc, v2, 1.0F, 1.0F, nx, ny, nz, r, g, b, alpha, light);
        hullVertex(pose, vc, v2, 1.0F, 1.0F, nx, ny, nz, r, g, b, alpha, light);
    }

    private static void hullVertex(Pose pose, VertexConsumer vc, float[] p, float u, float v,
                                   float nx, float ny, float nz, float r, float g, float b, float alpha, int light)
    {
        vc.vertex(pose.pose(), p[0], p[1], p[2])
                .color(r, g, b, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
    }

    /**
     * Draw ONLY the inner star billboard for a ball, at its block-local centre, with no shell around it. The CUBE render
     * style uses this for EVERY set so the translucent geo always shows a star inside, matching sphere style. Sets whose
     * surface texture also carries painted stars (earth, namek, corrupted) therefore show the star twice until a starless
     * cube texture is supplied for them. Call BEFORE the translucent geo and flush, so the geo blends over the billboard
     * exactly like the shell does in sphere mode.
     */
    public static void renderInnerStars(PoseStack poseStack, MultiBufferSource buffer, float radius, float centerY,
                                        float[] pip, int stars)
    {
        if (stars < 1)
        {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5D, centerY, 0.5D);
        drawStars(poseStack, buffer, radius, pip, stars);
        poseStack.popPose();
    }

    // the inner star billboard: a camera-facing quad at the ball centre showing this ball's star sprite, tinted and
    // emissive so it stays legible through the glass. Sized under the ball radius so it sits well inside the shell.
    private static void drawStars(PoseStack poseStack, MultiBufferSource buffer, float radius, float[] pip, int stars)
    {
        poseStack.pushPose();
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(starSprite(stars)));
        drawDisc(poseStack.last(), vc, radius * 0.62F, pip[0], pip[1], pip[2], 1.0F, FULL_BRIGHT);
        poseStack.popPose();
    }

    // a single closed UV sphere of radius s wound CCW seen from outside, so a culling render type keeps just the one
    // outward layer. UV is a plain lat/long map, seamless enough for the flat white sheet the caller tints. When
    // {@code reverse} is set the vertex order (and normal) of every quad is flipped, so the SAME culling type keeps the
    // FAR hemisphere instead: that is how the rim's inverted hull shows only its back faces.
    private static void drawSphere(Pose pose, VertexConsumer vc, float s, float r, float g, float b, float alpha,
                                   int light, boolean reverse)
    {
        for (int i = 0; i < SPHERE_RINGS; ++i)
        {
            float v0 = (float) i / SPHERE_RINGS;
            float v1 = (float) (i + 1) / SPHERE_RINGS;
            float phi0 = (float) (Math.PI * v0);
            float phi1 = (float) (Math.PI * v1);
            for (int j = 0; j < SPHERE_SECTORS; ++j)
            {
                float u0 = (float) j / SPHERE_SECTORS;
                float u1 = (float) (j + 1) / SPHERE_SECTORS;
                float th0 = (float) (2.0 * Math.PI * u0);
                float th1 = (float) (2.0 * Math.PI * u1);
                float[] p00 = spherePoint(s, phi0, th0);
                float[] p01 = spherePoint(s, phi0, th1);
                float[] p11 = spherePoint(s, phi1, th1);
                float[] p10 = spherePoint(s, phi1, th0);
                if (reverse)
                {
                    sphereVertex(pose, vc, p00, u0, v0, r, g, b, alpha, light, true);
                    sphereVertex(pose, vc, p10, u0, v1, r, g, b, alpha, light, true);
                    sphereVertex(pose, vc, p11, u1, v1, r, g, b, alpha, light, true);
                    sphereVertex(pose, vc, p01, u1, v0, r, g, b, alpha, light, true);
                }
                else
                {
                    sphereVertex(pose, vc, p00, u0, v0, r, g, b, alpha, light, false);
                    sphereVertex(pose, vc, p01, u1, v0, r, g, b, alpha, light, false);
                    sphereVertex(pose, vc, p11, u1, v1, r, g, b, alpha, light, false);
                    sphereVertex(pose, vc, p10, u0, v1, r, g, b, alpha, light, false);
                }
            }
        }
    }

    private static float[] spherePoint(float s, float phi, float theta)
    {
        float sinPhi = (float) Math.sin(phi);
        return new float[] {
                s * sinPhi * (float) Math.cos(theta),
                s * (float) Math.cos(phi),
                s * sinPhi * (float) Math.sin(theta)
        };
    }

    private static void sphereVertex(Pose pose, VertexConsumer vc, float[] p, float u, float v,
                                     float r, float g, float b, float alpha, int light, boolean flipNormal)
    {
        float len = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        float sign = flipNormal ? -1.0F : 1.0F;
        float nx = len > 1.0E-5F ? sign * p[0] / len : 0.0F;
        float ny = len > 1.0E-5F ? sign * p[1] / len : sign;
        float nz = len > 1.0E-5F ? sign * p[2] / len : 0.0F;
        vc.vertex(pose.pose(), p[0], p[1], p[2])
                .color(r, g, b, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
    }

    // a flat camera-facing quad with a per-vertex tint. The caller has already applied the camera orientation, so it
    // is drawn in the XY plane facing the viewer.
    private static void drawDisc(Pose pose, VertexConsumer vc, float s, float r, float g, float b, float alpha,
                                 int light)
    {
        discVertex(pose, vc, -s, -s, 0.0F, 0.0F, r, g, b, alpha, light);
        discVertex(pose, vc, s, -s, 1.0F, 0.0F, r, g, b, alpha, light);
        discVertex(pose, vc, s, s, 1.0F, 1.0F, r, g, b, alpha, light);
        discVertex(pose, vc, -s, s, 0.0F, 1.0F, r, g, b, alpha, light);
    }

    private static void discVertex(Pose pose, VertexConsumer vc, float x, float y, float u, float v,
                                   float r, float g, float b, float alpha, int light)
    {
        vc.vertex(pose.pose(), x, y, 0.0F)
                .color(r, g, b, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
