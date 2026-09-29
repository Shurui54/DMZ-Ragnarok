package net.shurui.shuruisutilities.client.particle;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The one render type vanilla does not have: ADDITIVE.
 *
 * <p>1.20.1 ships five usable particle render types and not one of them adds. {@code PARTICLE_SHEET_TRANSLUCENT}
 * is the normal alpha blend ({@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}), {@code PARTICLE_SHEET_OPAQUE} and
 * {@code PARTICLE_SHEET_LIT} turn blending off entirely, and the effects that look like they glow in vanilla
 * (flame, end rod, soul fire) are just bright translucent sprites. Additive is what makes overlapping particles
 * pile up towards white, which is the difference between a trail of coloured specks and a trail that looks hot.
 *
 * <p>Two departures from the translucent type, both deliberate:
 * <ul>
 *   <li>{@code depthMask(false)}. Additive quads must not write depth or the nearest one in a dense trail
 *       occludes the ones behind it and the pile-up never happens. Depth TESTING stays on, so terrain still
 *       hides them.</li>
 *   <li>the blend is set with {@code blendFuncSeparate}, leaving the destination alpha alone, so this composites
 *       correctly into the translucent framebuffer when a shader pack is running.</li>
 * </ul>
 *
 * <p>Forge supports custom particle render types: {@code ParticleEngine} buckets particles in a TreeMap ordered
 * by {@code ForgeHooksClient.makeParticleRenderTypeComparator}, which sorts every unknown type AFTER all five
 * vanilla ones. That ordering is exactly what additive wants, so the glow is composited over the finished
 * translucent pass rather than into the middle of it.
 */
@OnlyIn(Dist.CLIENT)
public final class RgParticleRenderTypes
{
    private RgParticleRenderTypes()
    {
    }

    public static final ParticleRenderType ADDITIVE = new ParticleRenderType()
    {
        @Override
        public void begin(BufferBuilder builder, TextureManager textures)
        {
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(Tesselator tesselator)
        {
            tesselator.end();
            // Put back what the next consumer of the global state expects. ParticleEngine.render does restore
            // the depth mask and disable blending after the loop, but only after EVERY type has drawn, and this
            // one sorts last only among custom types, not among all of them.
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
        }

        @Override
        public String toString()
        {
            return "DMZ_RAGNAROK_ADDITIVE";
        }
    };
}
