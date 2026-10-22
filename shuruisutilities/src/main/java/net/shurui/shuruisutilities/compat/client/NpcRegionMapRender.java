package net.shurui.shuruisutilities.compat.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Screen-space line drawing for the NPC region map overlay: polylines as thin quads (GuiGraphics has no line call)
 * and square vertex markers. Coordinates are GUI pixels. No Xaero types.
 */
public final class NpcRegionMapRender
{
    private NpcRegionMapRender() {}

    /**
     * Draw a polyline through {@code xy} (interleaved GUI x, y; {@code n} points), closing it back to the first point
     * when {@code closed}. {@code argb} colour, {@code width} in GUI pixels.
     */
    public static void polyline(GuiGraphics g, float[] xy, int n, boolean closed, int argb, float width)
    {
        if (n < 2)
            return;
        float a = (argb >>> 24 & 0xFF) / 255f, r = (argb >> 16 & 0xFF) / 255f;
        float gr = (argb >> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        g.flush(); // keep draw order with the GuiGraphics batch (fills, text) queued before this
        Matrix4f m = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull(); // either winding; the segments are built from arbitrary directions
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int segs = closed ? n : n - 1;
        float h = width / 2f;
        for (int i = 0; i < segs; i++)
        {
            int j = (i + 1) % n;
            float x1 = xy[2 * i], y1 = xy[2 * i + 1], x2 = xy[2 * j], y2 = xy[2 * j + 1];
            float dx = x2 - x1, dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.01f)
                continue;
            float nx = -dy / len * h, ny = dx / len * h;
            buf.vertex(m, x1 + nx, y1 + ny, 0).color(r, gr, b, a).endVertex();
            buf.vertex(m, x1 - nx, y1 - ny, 0).color(r, gr, b, a).endVertex();
            buf.vertex(m, x2 - nx, y2 - ny, 0).color(r, gr, b, a).endVertex();
            buf.vertex(m, x2 + nx, y2 + ny, 0).color(r, gr, b, a).endVertex();
        }
        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** A square marker centred on a point. */
    public static void marker(GuiGraphics g, float x, float y, int half, int fill, int border)
    {
        int cx = Math.round(x), cy = Math.round(y);
        g.fill(cx - half - 1, cy - half - 1, cx + half + 1, cy + half + 1, border);
        g.fill(cx - half, cy - half, cx + half, cy + half, fill);
    }
}
