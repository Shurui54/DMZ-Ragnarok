package net.shurui.shuruisutilities.client.planet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

/**
 * A baked model wrapper that makes an ALREADY-baked DragonMineZ block model tintable, without disturbing its geometry or
 * its per-variant rotation. This is the mechanism that lets stairs, slabs, walls, fences, gates, buttons and pressure
 * plates tint the same way their base block does.
 *
 * <p>WHY A WRAPPER AND NOT A MODEL SWAP. {@link NamekBlockModelBind} tints simple cube blocks by swapping in an SU model
 * whose JSON authors a tint index. That works only when one model can stand in for every blockstate. Stairs and the like
 * carry geometry-bearing states (facing, half, shape, type, axis, open) whose rotation is baked into each variant's model
 * during blockstate resolution, so a single-model swap would flatten every orientation to one. Instead this wraps DMZ's
 * OWN baked model for each individual state, so the shape and rotation DMZ already baked are kept verbatim, and only two
 * things are rewritten per quad:
 * <ul>
 *   <li>the sprite is repointed from DMZ's coloured texture ({@code dragonminez:block/x}) to SU's greyscale copy at the
 *       same path ({@code dmz_ragnarok:block/x}), so the per-biome multiply tint reads cleanly instead of compounding
 *       with a baked-in colour, exactly as the cube swaps do;</li>
 *   <li>the tint index is forced to {@code tintIndex} so a registered {@link net.minecraft.client.color.block.BlockColor}
 *       can colour it. DMZ's stock quads carry tint index -1 (no tint).</li>
 * </ul>
 *
 * <p>The greyscale sprite is only present on the block atlas when some SU model references it; the wood and stone base
 * cubes already ship SU greyscale copies and tinted models, so those sprites are stitched. A quad whose sprite has NO SU
 * greyscale counterpart on the atlas is left UNTOUCHED (its DMZ texture and its -1 tint), so a family without a greyscale
 * copy silently keeps its DMZ look rather than turning muddy or grey.
 *
 * <p>Only blockstate models are wrapped, never the {@code #inventory} item model, so the block in hand and in the
 * creative menu keeps DMZ's own coloured look, matching how the cube swaps behave.
 */
public final class TintedRetexturedModel extends BakedModelWrapper<BakedModel>
{
    private static final String DMZ_NAMESPACE = "dragonminez";
    private static final String SU_NAMESPACE = "dmz_ragnarok";

    // Block vertex format is 8 ints per vertex; the interpolated atlas U and V live at ints 4 and 5 of each vertex.
    private static final int VERTEX_STRIDE = 8;
    private static final int UV_U_OFFSET = 4;
    private static final int UV_V_OFFSET = 5;

    // Source sprite name -> SU greyscale sprite on the block atlas, or null cached as ABSENT when there is no SU copy.
    // Shared across every wrapper instance; resolved lazily at first render so the atlas is guaranteed stitched.
    private static final ConcurrentHashMap<ResourceLocation, TextureAtlasSprite> REMAP = new ConcurrentHashMap<>();
    private static final TextureAtlasSprite ABSENT = null;

    private final int tintIndex;

    // Retinted, retextured quads cached per (state, side, render type). The transform is deterministic in those, so this
    // avoids rebuilding quads on every chunk mesh. Populated on render worker threads, hence a concurrent map.
    private final ConcurrentHashMap<QuadKey, List<BakedQuad>> cache = new ConcurrentHashMap<>();

    public TintedRetexturedModel(BakedModel original, int tintIndex)
    {
        super(original);
        this.tintIndex = tintIndex;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand)
    {
        return transform(state, side, null, originalModel.getQuads(state, side, rand));
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData extraData,
            RenderType renderType)
    {
        return transform(state, side, renderType, originalModel.getQuads(state, side, rand, extraData, renderType));
    }

    private List<BakedQuad> transform(BlockState state, Direction side, RenderType renderType, List<BakedQuad> source)
    {
        if (source.isEmpty())
        {
            return source;
        }
        QuadKey key = new QuadKey(state, side, renderType);
        List<BakedQuad> cached = cache.get(key);
        if (cached != null)
        {
            return cached;
        }
        List<BakedQuad> out = new ArrayList<>(source.size());
        for (BakedQuad quad : source)
        {
            TextureAtlasSprite from = quad.getSprite();
            TextureAtlasSprite to = remap(from);
            if (to == null)
            {
                // No SU greyscale for this sprite; keep DMZ's quad verbatim so it stays its stock colour, not a muddy
                // colour-over-colour or an untinted grey.
                out.add(quad);
                continue;
            }
            int[] vertices = quad.getVertices().clone();
            for (int v = 0; v < 4; v++)
            {
                int base = v * VERTEX_STRIDE;
                float u = Float.intBitsToFloat(vertices[base + UV_U_OFFSET]);
                float w = Float.intBitsToFloat(vertices[base + UV_V_OFFSET]);
                float localU = spanFraction(u, from.getU0(), from.getU1());
                float localV = spanFraction(w, from.getV0(), from.getV1());
                vertices[base + UV_U_OFFSET] = Float.floatToRawIntBits(lerp(to.getU0(), to.getU1(), localU));
                vertices[base + UV_V_OFFSET] = Float.floatToRawIntBits(lerp(to.getV0(), to.getV1(), localV));
            }
            out.add(new BakedQuad(vertices, tintIndex, quad.getDirection(), to, quad.isShade(),
                    quad.hasAmbientOcclusion()));
        }
        cache.put(key, out);
        return out;
    }

    // Resolve the SU greyscale sprite for a DMZ sprite: same texture path under the SU namespace, if it is on the block
    // atlas. Returns null (kept as ABSENT in the cache) for non-DMZ sprites or when SU ships no greyscale copy.
    private static TextureAtlasSprite remap(TextureAtlasSprite from)
    {
        ResourceLocation name = from.contents().name();
        if (!DMZ_NAMESPACE.equals(name.getNamespace()))
        {
            return ABSENT;
        }
        return REMAP.computeIfAbsent(name, n ->
        {
            try
            {
                TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getModelManager()
                        .getAtlas(TextureAtlas.LOCATION_BLOCKS);
                TextureAtlasSprite su = atlas.getSprite(new ResourceLocation(SU_NAMESPACE, n.getPath()));
                if (su == null || su.contents().name().equals(MissingTextureAtlasSprite.getLocation()))
                {
                    return ABSENT;
                }
                return su;
            }
            catch (Throwable t)
            {
                return ABSENT;
            }
        });
    }

    private static float spanFraction(float value, float lo, float hi)
    {
        float span = hi - lo;
        if (span == 0.0F)
        {
            return 0.0F;
        }
        return (value - lo) / span;
    }

    private static float lerp(float lo, float hi, float fraction)
    {
        return lo + (hi - lo) * fraction;
    }

    private static final class QuadKey
    {
        private final BlockState state;
        private final Direction side;
        private final RenderType renderType;

        private QuadKey(BlockState state, Direction side, RenderType renderType)
        {
            this.state = state;
            this.side = side;
            this.renderType = renderType;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof QuadKey other))
            {
                return false;
            }
            return state == other.state && side == other.side && renderType == other.renderType;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(System.identityHashCode(state), side, renderType);
        }
    }
}
