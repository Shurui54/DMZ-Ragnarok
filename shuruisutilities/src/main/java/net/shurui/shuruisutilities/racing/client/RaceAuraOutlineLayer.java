package net.shurui.shuruisutilities.racing.client;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;
import net.shurui.shuruisutilities.racing.physics.RaceFx;

/**
 * The coloured aura outline on a racer under a Destroyer / Kaioken powerup, drawn as a client render layer instead of
 * the vanilla GLOWING effect (which does not show on the mounted DragonMineZ rider). It is a vanilla
 * {@code PlayerRenderer} layer: DragonMineZ's {@code DMZThirdPartyLayerForwarder} poses the vanilla player model from
 * its geo body ({@code VanillaModelSync}) and forwards every non-vanilla player layer onto it, so adding this to the
 * vanilla player renderers draws it on the DMZ player, on the racer THEMSELF and on everyone else. The class name is
 * deliberately clear of {@code cosarmor}/{@code cosmeticarmor}, the two substrings the forwarder filters out.
 *
 * <p>The outline is an inverted hull: every cube of the posed body parts is re-emitted a little larger with its faces
 * wound INWARD, drawn with back-face culling, colour only (no depth write), LEQUAL. Culling keeps only the far side of
 * the enlarged boxes; wherever the body is in front it covers them (or they fail the depth test against it), so only a
 * thin rim around the silhouette stays visible. The hull geometry is ours, so its winding is ours too (the same idea
 * as the dragon ball rim, {@code BallRenderTypes.rim}); nothing touches {@code glCullFace}.
 *
 * <p>The colour is read from the rider's race bike FX bits, which the server already syncs ({@code getRaceFx} for the
 * local rider, {@code getRaceAura} for a remote one, unified by {@link RaceBikeFx#effectiveFx}), so there is no new
 * packet. Nothing draws unless the {@code racing} feature is synced.
 */
public class RaceAuraOutlineLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>
{
    /** A plain white texture, so the emissive shader times the vertex colour reads as a flat colour. */
    private static final ResourceLocation WHITE =
            new ResourceLocation("dmz_ragnarok", "textures/misc/race_white.png");

    private static final int DESTROYER_RGB = 0x9B30FF; // purple
    private static final int KAIOKEN_X20_RGB = 0xFF7A20; // red-gold
    private static final int KAIOKEN_RGB = 0xFF2020; // red

    /** How far each box grows on every side, in model pixels. Small, so it reads as an edge, not a second body. */
    private static final float GROW = 0.9F;
    private static final float ALPHA = 0.9F;

    public RaceAuraOutlineLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent)
    {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch)
    {
        if (!ClientGate.feature("racing"))
            return;
        int rgb = auraColour(player);
        if (rgb == 0)
            return;

        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;

        VertexConsumer vc = buffer.getBuffer(Types.hull(WHITE));
        // getParentModel() is already posed for this frame (limb swing, head yaw), so the hull matches the body pose.
        PlayerModel<AbstractClientPlayer> model = this.getParentModel();
        ModelPart[] parts = { model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg };
        for (ModelPart part : parts)
        {
            if (!part.visible)
                continue;
            part.visit(poseStack, (pose, path, index, cube) ->
                    hullBox(pose, vc, cube.minX - GROW, cube.minY - GROW, cube.minZ - GROW,
                            cube.maxX + GROW, cube.maxY + GROW, cube.maxZ + GROW, r, g, b));
        }
    }

    /** One enlarged box (model pixels) with its six faces wound inward, so back-face culling keeps the far side. */
    private static void hullBox(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float z0,
                                float x1, float y1, float z1, float r, float g, float b)
    {
        x0 /= 16.0F; y0 /= 16.0F; z0 /= 16.0F;
        x1 /= 16.0F; y1 /= 16.0F; z1 /= 16.0F;
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        // Corners per face as an outward cube face lists them; quad() reverses them so each face points INTO the box.
        quad(m, n, vc, r, g, b, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0); // north face, z0
        quad(m, n, vc, r, g, b, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // south face, z1
        quad(m, n, vc, r, g, b, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // west face, x0
        quad(m, n, vc, r, g, b, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1); // east face, x1
        quad(m, n, vc, r, g, b, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // y0 face
        quad(m, n, vc, r, g, b, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0); // y1 face
    }

    private static void quad(Matrix4f m, Matrix3f n, VertexConsumer vc, float r, float g, float b,
                             float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz)
    {
        // The corners are listed clockwise as seen from inside the box (the outward winding), so emit them reversed:
        // counter-clockwise from inside, which is what survives back-face culling.
        vert(m, n, vc, r, g, b, nx, ny, nz, dx, dy, dz, 1, 0);
        vert(m, n, vc, r, g, b, nx, ny, nz, cx, cy, cz, 1, 1);
        vert(m, n, vc, r, g, b, nx, ny, nz, bx, by, bz, 0, 1);
        vert(m, n, vc, r, g, b, nx, ny, nz, ax, ay, az, 0, 0);
    }

    private static void vert(Matrix4f m, Matrix3f n, VertexConsumer vc, float r, float g, float b,
                             float nx, float ny, float nz, float x, float y, float z, float u, float v)
    {
        vc.vertex(m, x, y, z).color(r, g, b, ALPHA).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT).normal(n, nx, ny, nz).endVertex();
    }

    /** The aura colour for the racer, from the bike they ride, or 0 for none. */
    private static int auraColour(AbstractClientPlayer player)
    {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof HoverbikeEntity bike) || !bike.isRaceBike())
            return 0;
        int fx = RaceBikeFx.effectiveFx(bike);
        if (RaceFx.has(fx, RaceFx.DESTROYER_AURA))
            return DESTROYER_RGB;
        if (RaceFx.has(fx, RaceFx.KAIOKEN_X20))
            return KAIOKEN_X20_RGB;
        if (RaceFx.has(fx, RaceFx.KAIOKEN))
            return KAIOKEN_RGB;
        return 0;
    }

    /** The hull render type: emissive flat colour, back-face CULL, colour writes only, LEQUAL, translucent. */
    private static final class Types extends RenderType
    {
        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                      boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear)
        {
            super(name, format, mode, size, affectsCrumbling, sortOnUpload, setup, clear);
            throw new UnsupportedOperationException();
        }

        private static RenderType hullType;

        static RenderType hull(ResourceLocation texture)
        {
            if (hullType == null)
            {
                CompositeState state = CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(CULL)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .createCompositeState(false);
                hullType = create("dmzr_race_aura_hull", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                        256, true, true, state);
            }
            return hullType;
        }
    }
}
