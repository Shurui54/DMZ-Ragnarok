package net.shurui.shuruisutilities.client.shard;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.mixin.client.AccessorCompositeRenderType;
import net.shurui.shuruisutilities.core.mixin.client.AccessorCompositeState;
import net.shurui.shuruisutilities.core.mixin.client.AccessorTextureStateShard;

/**
 * Makes a cross-server ghost draw barely visible, body and every layer alike.
 *
 * <h2>Why a buffer wrapper and not per call alpha</h2>
 * In this modpack a player is NOT drawn by the vanilla player renderer. DragonMineZ cancels that at the head of
 * {@code PlayerRenderer.render} and draws the player itself through a GeckoLib {@code DMZPlayerRenderer} (a
 * {@code GeoEntityRenderer}), so the body model, the armour layer, the held item layer and any cape all go
 * through GeckoLib's draw, not through {@code LivingEntityRenderer}. GeckoLib only carries the render colour's
 * alpha into the BODY; each layer computes its own and would stay solid. The one thing they all share is the
 * {@code MultiBufferSource} they pull their buffers from, so wrapping that source is the single point that
 * catches everything. {@code MixinGeoEntityRendererGhost} swaps in this wrapper for ghosts only.
 *
 * <h2>What the wrapper does to each buffer</h2>
 * Two things have to be true for a solid model to read as see through: the render type has to blend (an entity
 * cutout type ignores fractional alpha, it only tests it), and the alpha written per vertex has to be low. So
 * for every render type requested, {@link #translucentFor(RenderType)} re-issues the SAME texture on a blending
 * entity translucent type, and {@link GhostVertexConsumer} scales every vertex's alpha (and any default colour)
 * by {@link #GHOST_ALPHA}. A render type that cannot be substituted (no recoverable texture, or a vertex format
 * that is not the one being swapped in) is passed through untouched and unwrapped, so that geometry stays opaque
 * rather than the draw failing: a cosmetic miss, never a crash. See {@link #translucentFor} for why the format
 * check is load bearing rather than defensive.
 *
 * <h2>Safety</h2>
 * The wrapper is only ever installed for {@code GhostPlayer} instances, so no other entity's rendering is
 * touched. Every recovery step is wrapped so a future render type reshape degrades to the original (opaque)
 * type instead of throwing on the render thread.
 */
public final class GhostRender
{
    private GhostRender() {}

    /**
     * How visible a ghost is, 0 (gone) to 1 (solid). Low on purpose: the operator asked for "barely visible,
     * mostly transparent". One place to retune body and every layer together.
     */
    public static final float GHOST_ALPHA = 0.25F;

    /**
     * Requested render type to its translucent, same texture equivalent. Identity keyed because render types
     * are singletons, and populated a handful of times per session (body, armour, item, cape), so the walk to
     * recover a texture runs a few times total, not per frame.
     */
    private static final Map<RenderType, RenderType> TRANSLUCENT = new IdentityHashMap<>();

    /** Wrap a buffer source so everything drawn through it comes out translucent. Ghosts only. */
    public static MultiBufferSource wrap(MultiBufferSource delegate)
    {
        return new GhostBufferSource(delegate);
    }

    private record GhostBufferSource(MultiBufferSource delegate) implements MultiBufferSource
    {
        @Override
        public VertexConsumer getBuffer(RenderType type)
        {
            RenderType ghost = translucentFor(type);
            // Nothing was substituted, so this draw is not one of ours to fade: hand back the real buffer,
            // unwrapped. Anything else would put our alpha scaling in front of a writer we have not vetted.
            if (ghost == type)
                return this.delegate.getBuffer(type);
            return new GhostVertexConsumer(this.delegate.getBuffer(ghost));
        }
    }

    /**
     * The blending equivalent of a render type, reusing its own texture, or the type itself when it must not be
     * substituted.
     *
     * <h2>Why the vertex format has to match</h2>
     * A {@code BufferBuilder} is a SEQUENTIAL writer: the caller fills the elements of its render type's format
     * in order, and {@code endVertex} throws {@code "Not filled all elements of the vertex"} if the sequence did
     * not land exactly on the end. Handing a caller a buffer whose format is not the one it is writing for is
     * therefore a crash on the render thread, not a wrong colour.
     *
     * <p>{@link RenderType#entityTranslucent} is {@code NEW_ENTITY} (position, colour, uv0, overlay, uv2,
     * normal) drawn as quads. The body model, the armour layer and a held item all use that format, so they can
     * be swapped. Several things drawn through the very same buffer source cannot:
     * {@code GeoEntityRenderer.renderFinal} calls {@code EntityRenderer.render}, which draws the NAMEPLATE on
     * {@code RenderType.text} ({@code POSITION_COLOR_TEX_LIGHTMAP}, no overlay and no normal), and an enchanted
     * item's glint is {@code POSITION_TEX}. Substituting those was what crashed the client the moment a ghost
     * with a name came into view. So the format and the draw mode are checked, and anything that does not match
     * is passed through untouched: that piece stays opaque, which is a cosmetic miss rather than a crash.
     */
    private static RenderType translucentFor(RenderType type)
    {
        RenderType cached = TRANSLUCENT.get(type);
        if (cached != null)
            return cached;
        RenderType result = type;
        try
        {
            if (type.format() == DefaultVertexFormat.NEW_ENTITY && type.mode() == VertexFormat.Mode.QUADS)
            {
                ResourceLocation texture = textureOf(type);
                if (texture != null)
                    result = RenderType.entityTranslucent(texture);
            }
        }
        catch (Throwable ignored)
        {
            // Leave result as the original type: that geometry stays opaque, which is a cosmetic miss only.
        }
        TRANSLUCENT.put(type, result);
        return result;
    }

    /** Recover a render type's texture by walking its composite state, or null if it carries none. */
    private static ResourceLocation textureOf(RenderType type)
    {
        if (!(type instanceof AccessorCompositeRenderType composite))
            return null;
        RenderType.CompositeState state = composite.su$state();
        if (state == null)
            return null;
        // CompositeState is a final class, so the accessor interface (added by the mixin at runtime) is not
        // visible to the compiler on it; the cast has to go through Object.
        RenderStateShard.EmptyTextureStateShard textureState =
                ((AccessorCompositeState) (Object) state).su$textureState();
        if (!(textureState instanceof AccessorTextureStateShard shard))
            return null;
        Optional<ResourceLocation> texture = shard.su$texture();
        return texture != null ? texture.orElse(null) : null;
    }

    /**
     * A vertex consumer that scales every vertex's alpha by {@link #GHOST_ALPHA} before handing it to the real
     * buffer. Only the atomic vertex operations are delegated; the composite helpers (the all in one vertex
     * call and the various bulk quad calls) are left to their interface defaults, which route back through
     * these overrides, so a held item drawn through bulk data is scaled just the same as a per vertex model.
     *
     * <p>Falling back to those defaults also means a rendering backend's fast path (Sodium and Embeddium detect
     * a writer they recognise and stream whole quads into it) sees a consumer it does not know and takes the
     * ordinary per vertex route instead. That is correct and safe, just not accelerated for the handful of
     * ghosts on screen. It is only ever installed on {@code NEW_ENTITY} quad buffers, so the element sequence
     * these calls produce is exactly the one the underlying builder is expecting.
     */
    private static final class GhostVertexConsumer implements VertexConsumer
    {
        private final VertexConsumer inner;

        private GhostVertexConsumer(VertexConsumer inner)
        {
            this.inner = inner;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z)
        {
            this.inner.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha)
        {
            this.inner.color(red, green, blue, Math.round(alpha * GHOST_ALPHA));
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v)
        {
            this.inner.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v)
        {
            this.inner.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v)
        {
            this.inner.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z)
        {
            this.inner.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex()
        {
            this.inner.endVertex();
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha)
        {
            this.inner.defaultColor(red, green, blue, Math.round(alpha * GHOST_ALPHA));
        }

        @Override
        public void unsetDefaultColor()
        {
            this.inner.unsetDefaultColor();
        }
    }
}
