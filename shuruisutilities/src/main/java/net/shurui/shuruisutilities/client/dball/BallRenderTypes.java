package net.shurui.shuruisutilities.client.dball;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * The two render types the CUBE ball style draws through: a depth prefill and an EQUAL colour pass, so the ball's real
 * GeckoLib geo can be drawn translucent as ONE clean layer instead of a stack of the model's internal panel faces (the
 * artifact the user rejected on a naive translucent geo draw). This is the block-scale twin of
 * {@code SpaceBodyRenderer.SuperRenderTypes}, per texture and cached:
 *
 * <ul>
 *   <li>Pass 1 (depthPrefill): colour masked off, depth writes ON, LEQUAL. Stamps the NEAREST surface depth per pixel.</li>
 *   <li>Pass 2 (colorEqual): colour ON, depth writes off, EQUAL, translucent blend. Only the fragment whose depth equals
 *       the prefilled nearest one survives, so exactly one translucent layer paints per pixel.</li>
 * </ul>
 *
 * <p>Both reference {@code RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER}. The two passes draw the SAME geo through the SAME
 * renderer, pose and partial tick, so the same shader gives bit-identical window depth and EQUAL matches. Pairing two
 * DIFFERENT core shaders (which are not declared invariant) is what made an earlier super-body attempt render nothing.
 *
 * <p>WHY NO_CULL here where the super shell uses CULL. {@code SuperRenderTypes} draws its own hand-wound outward AABBs,
 * so back-face culling is safe there. GeckoLib emits its cube geometry under a no-cull assumption (its default type is
 * {@code entityCutoutNoCull}) and does not guarantee a consistent GL winding, so culling could drop real front faces and
 * leave the ball invisible or holed. NO_CULL keeps every face, and the single-layer result comes from the depth EQUAL
 * test alone: for any covered pixel the nearest fragment is the frontmost surface, so pass 2 colours only that one.
 */
public final class BallRenderTypes extends RenderType
{
    private BallRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                            boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear)
    {
        super(name, format, mode, size, affectsCrumbling, sortOnUpload, setup, clear);
        throw new UnsupportedOperationException();
    }

    // one pair per texture, built once and reused, so the BufferSource keeps batching by type instead of seeing a fresh
    // render type each frame. A handful of ball textures ever appear, so these maps stay tiny.
    private static final Map<ResourceLocation, RenderType> DEPTH = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, RenderType> COLOR = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, RenderType> RIM = new ConcurrentHashMap<>();

    public static RenderType depthPrefill(ResourceLocation texture)
    {
        return DEPTH.computeIfAbsent(texture, BallRenderTypes::buildDepth);
    }

    public static RenderType colorEqual(ResourceLocation texture)
    {
        return COLOR.computeIfAbsent(texture, BallRenderTypes::buildColor);
    }

    /**
     * The outer rim highlight pass, shared by SPHERE and CUBE style. A translucent CULLING colour pass (depth writes
     * off, LEQUAL depth test) that draws the enlarged concentric hull's back faces over the already-committed body
     * depth: inside the silhouette those far faces fail LEQUAL against the body surface and are discarded; at the edge,
     * where the body wrote no depth, they pass and paint a thin lighter ring.
     *
     * <p>CULL is standard back-face culling. Both styles reverse their OWN vertex winding so this keeps the far faces:
     * SPHERE style reverses its sphere hull, CUBE style reverses the convex hull it builds from the set's box table
     * ({@link DragonBallHull}). Neither poking {@code glCullFace} nor any assumption about GeckoLib's own winding is
     * involved; the winding is ours because we generate the hull geometry ourselves.
     */
    public static RenderType rim(ResourceLocation texture)
    {
        return RIM.computeIfAbsent(texture, BallRenderTypes::buildRim);
    }

    private static RenderType buildRim(ResourceLocation texture)
    {
        CompositeState state = CompositeState.builder()
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setWriteMaskState(COLOR_WRITE)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .createCompositeState(true);
        return create("su_ball_rim_" + texture.getPath(), DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 256, true, false, state);
    }

    private static RenderType buildDepth(ResourceLocation texture)
    {
        CompositeState state = CompositeState.builder()
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setWriteMaskState(DEPTH_WRITE)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .createCompositeState(true);
        return create("su_ball_cube_depth_" + texture.getPath(), DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 256, true, false, state);
    }

    private static RenderType buildColor(ResourceLocation texture)
    {
        CompositeState state = CompositeState.builder()
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setWriteMaskState(COLOR_WRITE)
                .setDepthTestState(EQUAL_DEPTH_TEST)
                .createCompositeState(true);
        return create("su_ball_cube_color_" + texture.getPath(), DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 256, true, false, state);
    }
}
