package net.shurui.shuruisutilities.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The zeni readout: coin, pill housing, currency mark, then the balance. ONE implementation, shared by the
 * scouter HUD and by any screen that shows a balance, so the two cannot drift apart.
 *
 * <p>It exists because the task board grew a second, hand-rolled zeni display that looked nothing like the
 * HUD's: different art, a yellow un-outlined number, and no handling for a balance long enough to overflow its
 * housing. The rules below are the HUD's, and they are the whole point of sharing this:</p>
 *
 * <ul>
 *   <li>Every digit is shown, grouped in threes. NEVER abbreviated: the coin already says what the number is,
 *       and abbreviating hides exactly the digits a player looked in order to read.</li>
 *   <li>The number SHRINKS to fit its housing rather than running out of it, so a large balance gets smaller
 *       instead of spilling over the pill.</li>
 *   <li>Left aligned from two pixels past the coin, vertically centred on the pill.</li>
 *   <li>Drawn in DMZ's HUD-number style so it reads native beside them.</li>
 * </ul>
 *
 * <p>All geometry is expressed RELATIVE to the coin's top-left, which is the origin callers pass. The absolute
 * numbers came from the HUD, where they were tuned against the art; do not "tidy" them.</p>
 *
 * <p>Client-only.</p>
 */
public final class ZeniReadout
{
    private ZeniReadout() {}

    private static ResourceLocation rework(String name)
    {
        return ResourceLocation.fromNamespaceAndPath(ShuruisUtilities.MODID, "textures/gui/hud/rework/" + name + ".png");
    }

    private static final ResourceLocation TEX_BAR = rework("zeni_bar");
    private static final ResourceLocation TEX_ORB = rework("zeni_orb");
    private static final ResourceLocation TEX_Z = rework("zeni_z");

    /** The coin. Origin of the whole assembly. */
    private static final int ORB_S = 22;

    /**
     * The pill. Drawn from {@code u = BAR_U} rather than from 0: the sprite carries a stray tab on its left that
     * the coin is meant to cover, so the visible housing starts partway into the texture.
     */
    private static final int BAR_DX = 10, BAR_DY = 1;
    private static final int BAR_TEX_W = 80, BAR_H = 20;
    private static final int BAR_U = 11;
    private static final int BAR_DRAW_W = BAR_TEX_W - BAR_U;

    /** The currency mark, sitting over the coin. */
    private static final int Z_DX = 9, Z_DY = 7, Z_W = 4, Z_H = 8;

    /** Text band, derived from the coin and the pill so it cannot drift out of step with either. */
    private static final int TEXT_DX = ORB_S + 2;
    private static final int TEXT_RIGHT_DX = BAR_DX + BAR_DRAW_W - 2;
    private static final float TEXT_DY = BAR_DY + BAR_H / 2.0f;
    private static final float TEXT_SCALE = 0.75f;
    private static final int TEXT_COLOR = 0xFFFFD34D;

    /** Full width of the assembly, coin's left edge to the pill's right edge. */
    public static final int WIDTH = BAR_DX + BAR_DRAW_W;
    /** Full height of the assembly. The coin is the tallest piece. */
    public static final int HEIGHT = ORB_S;

    /**
     * Draw the assembly with the coin's top-left at {@code (x, y)}.
     *
     * @param known whether the server has actually said what the balance is. When false the housing is drawn
     *              but no number, so a player with money never flashes a 0 before the first sync lands.
     */
    public static void draw(GuiGraphics g, Font font, int x, int y, long amount, boolean known)
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_BAR, x + BAR_DX, y + BAR_DY, BAR_U, 0.0f, BAR_DRAW_W, BAR_H, BAR_TEX_W, BAR_H);
        g.blit(TEX_ORB, x, y, 0.0f, 0.0f, ORB_S, ORB_S, ORB_S, ORB_S);
        g.blit(TEX_Z, x + Z_DX, y + Z_DY, 0.0f, 0.0f, Z_W, Z_H, Z_W, Z_H);
        if (known)
        {
            drawAmount(g, font, x, y, amount);
        }
    }

    private static void drawAmount(GuiGraphics g, Font font, int x, int y, long amount)
    {
        String text = grouped(amount);
        int available = TEXT_RIGHT_DX - TEXT_DX;
        float scale = TEXT_SCALE;
        int width = font.width(text);
        if (width * scale > available)
        {
            scale = (float) available / width;
        }

        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + TEXT_DX, y + TEXT_DY - font.lineHeight * scale / 2.0f, 0.0f);
        pose.scale(scale, scale, 1.0f);
        // The same four-way black stamp DMZ uses for its own HUD numbers.
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0)
                    g.drawString(font, text, dx, dy, 0xFF000000, false);
        g.drawString(font, text, 0, 0, TEXT_COLOR, false);
        pose.popPose();
    }

    /** The balance with thousands separators, sign preserved. */
    public static String grouped(long amount)
    {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3 + 1);
        if (amount < 0)
        {
            out.append('-');
        }
        for (int i = 0; i < digits.length(); i++)
        {
            if (i > 0 && (digits.length() - i) % 3 == 0)
            {
                out.append(',');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
