package net.shurui.shuruisutilities.client;

import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Kinetic Hosting affiliate banner: one implementation, right-pinned on whichever screen wants it, at a
 * corner that is chosen PER SCREEN (see {@link Anchor}).
 *
 * <p>It lives here rather than inside a screen because three different screens show it (our Ragnarok menu,
 * Minecraft's own title screen and the multiplayer list) and two of those are vanilla screens we reach through
 * Forge events rather than own. One drawer and one hit-test means the banner cannot drift out of step between
 * them, and the affiliate URL is written down exactly once.</p>
 *
 * <p>The corner is not the same on every screen. On the two menus with nothing important in the top right (our
 * Ragnarok menu and the vanilla title screen) it stays TOP RIGHT, out of the way of the logo. On the world
 * list and the server list it sits BOTTOM RIGHT instead: top-right there landed straight over the first list
 * row, hiding a world's or a server's player count and status, which is exactly the content those screens
 * exist to show. The caller that knows which screen is in front picks the corner and hands it in; the drawer
 * and the click test both take the same {@link Anchor}, so they cannot disagree about where the banner is.</p>
 *
 * <p>Client-only.</p>
 */
public final class KineticBanner
{
    private KineticBanner() {}

    /**
     * Which corner the banner pins to. Right-pinned in both cases; only the vertical edge differs. The choice
     * is made by the caller per screen, next to the code that decides which screens get a banner at all, so the
     * two decisions stay in one place.
     */
    public enum Anchor
    {
        TOP_RIGHT,
        BOTTOM_RIGHT
    }

    public static final String URL = "https://billing.kinetichosting.com/aff.php?aff=1545";

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("dmz_ragnarok", "textures/gui/mainmenu/kinetic_banner.png");

    /**
     * Authored size. The draw keeps this aspect, so the banner is never stretched.
     *
     * <p>This was 837x80, a thin strip. The current art is 720x300, so the banner is now 2.4:1 rather than 10.5:1
     * and every size below had to move with it: at the old {@link #TARGET_LOGICAL_W} of 320 the new aspect would
     * have drawn 133 logical pixels tall instead of 31, taking over the top right corner of the menu.
     */
    private static final int TEX_W = 720;
    private static final int TEX_H = 300;

    /**
     * The width the banner wants, in LOGICAL (gui-scaled) pixels. This is the knob that makes it scale WITH the
     * gui scale, and it is why the number is constant rather than a fraction of the screen.
     *
     * <p>Everything Minecraft draws is authored in logical pixels and multiplied back up by the gui scale, so a
     * constant logical size grows on screen as the gui scale rises, exactly like a vanilla button (which is 200
     * logical wide). The old size, {@code screenW * WIDTH_FRACTION}, was a fraction of the LOGICAL screen, and the
     * logical screen itself shrinks as gui scale rises, so those two cancelled and the banner stayed one fixed
     * physical size at every gui scale, out of step with the UI around it. A constant logical width fixes that.</p>
     *
     * <p>180 for the 720x300 art, which draws 75 logical pixels tall. The number is chosen by AREA rather than by
     * width: the previous 320x31 strip covered about 9900 square logical pixels, and at 2.4:1 the width that
     * covers the same is 154. 180 is a little above that, because this art carries a logo AND the words KINETIC
     * HOSTING where the old strip carried only a line of text, so it needs the extra size to stay legible. Going
     * back to a width near 320 would triple the corner the banner occupies.</p>
     *
     * <p>It is only the PREFERRED size: the two caps below can still take it smaller, and on a narrow window or at
     * a high gui scale the {@link #MAX_WIDTH_FRACTION} logo-clearance cap is what actually binds.</p>
     */
    private static final int TARGET_LOGICAL_W = 180;

    /**
     * Smallest width worth drawing. This is a DEGENERATE guard only, for a zero or near-zero window size reported
     * transiently during init, and it must stay far below any real playable size.
     *
     * <p>It used to be 130, paired with the width fraction as a readability cutoff: below that the banner was
     * dropped entirely rather than shown as a smear. That was wrong, and visibly so. At gui scale 2 in a windowed
     * client the logical width lands near 430, and 30% of 430 is 129, one pixel under the cutoff, so the banner
     * silently vanished at an entirely ordinary window size. A banner that disappears is worse than a small one,
     * so it now always scales down with the menu instead of ever being dropped.</p>
     */
    private static final int MIN_W = 16;

    /**
     * The most of the screen WIDTH the banner may occupy. This is the "scale with the menu" cap: it shrinks the
     * banner on a narrow window and, right-pinned, keeps it clear of the logo.
     *
     * <p>0.32 is the largest value that still clears the logo. The Ragnarok menu's logo occupies the middle 0.304
     * to 0.696 of the width at EVERY aspect ratio (its width is a fixed fraction of the window either way), so a
     * right-pinned banner starts at {@code 1 - MAX_WIDTH_FRACTION} and must not cross 0.696. At 0.32 it starts at
     * 0.68, just clear; at 0.35 it starts at 0.65 and eats about 5% of the width into the logo's box.
     */
    private static final float MAX_WIDTH_FRACTION = 0.32F;

    /**
     * The most of the screen HEIGHT the banner may occupy. This is the cap the old code never had, and its
     * absence was the real scaling defect: width was capped against the screen but height was only ever derived
     * from width, so at a high gui scale, where the logical screen goes short, a banner sized by width alone
     * kept its full 75-logical-pixel height and swallowed a big share of the vertical space (75 of a 270-tall
     * logical screen at gui scale 4 is nearly a third of it).
     *
     * <p>0.20 keeps the banner clear of dominating a short screen. At an ordinary window it does not bind at
     * all (the {@link #TARGET_LOGICAL_W} width cap is what sets the size there); it only starts to shrink the
     * banner once the logical screen is short enough that the width-derived height would cross a fifth of it,
     * which is exactly the high-gui-scale case it exists for. When it binds, the width is derived BACK from the
     * capped height so the 2.4:1 aspect is kept and the banner just gets smaller.</p>
     */
    private static final float MAX_HEIGHT_FRACTION = 0.20F;

    private static final int MARGIN_TOP = 4;
    private static final int MARGIN_RIGHT = 4;

    /**
     * Gap left below a BOTTOM_RIGHT banner, in logical pixels. It has to clear the vanilla bottom button band
     * on the world list and the server list, because the banner steals clicks wherever it sits (see
     * KineticBannerClientEvents), so any overlap would make a vanilla button unpressable.
     *
     * <p>Both of those screens use a {@code HeaderAndFooterLayout} with a footer height of 60, and the footer
     * frame is placed at {@code height - 60} spanning the full width, with its button rows centred inside it.
     * So the bottom 60 logical pixels are the interactive footer band on both screens, and because the buttons
     * are horizontally CENTRED they can reach the right edge on a narrow window, where a right-pinned banner
     * would otherwise sit on top of them. Clearing the band by VERTICAL position is therefore the robust fix,
     * independent of window width: 64 = the 60-pixel footer band plus a 4-pixel gap above it, so the banner's
     * bottom edge lands at {@code height - 64}, four pixels clear of the top of the footer at every width and
     * every gui scale.</p>
     */
    private static final int MARGIN_BOTTOM = 64;
    private static final int TEXT_GAP = 3;

    /** Where the banner currently sits, so the click test and the draw cannot disagree. */
    public record Rect(int x, int y, int w, int h) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /**
     * The banner's rectangle for a screen of this size and this {@link Anchor}, or null only when the window
     * has no usable size at all.
     *
     * <p>Width is the smallest of three limits, so all three behaviours hold at once:</p>
     * <ul>
     *   <li>{@link #TARGET_LOGICAL_W} makes it scale WITH the gui scale (a constant logical size grows on screen
     *       as gui scale rises);</li>
     *   <li>{@code screenW * MAX_WIDTH_FRACTION} makes it scale with the menu (shrinks on a narrow window, clears
     *       the logo);</li>
     *   <li>{@code TEX_W / guiScale} is the native-resolution cap in logical px, so the art is never upscaled past
     *       its authored size into a blur.</li>
     * </ul>
     *
     * <p>Then the height is capped against {@link #MAX_HEIGHT_FRACTION} of the screen height, and if that binds
     * the width is derived back from the capped height. The width caps alone never looked at how TALL the screen
     * is, so this is what stops the banner dominating a short logical screen at a high gui scale.</p>
     *
     * <p>The x edge is always right-pinned. The y edge depends on the anchor: TOP_RIGHT sits at
     * {@link #MARGIN_TOP}, BOTTOM_RIGHT sits {@link #MARGIN_BOTTOM} up from the bottom so it clears the vanilla
     * footer button band.</p>
     */
    public static Rect rectFor(int screenW, int screenH, Anchor anchor)
    {
        double guiScale = Math.max(1.0D, Minecraft.getInstance().getWindow().getGuiScale());
        int nativeCapLogical = (int) Math.floor(TEX_W / guiScale);
        int w = Math.min(TARGET_LOGICAL_W,
                Math.min(nativeCapLogical, Math.round(screenW * MAX_WIDTH_FRACTION)));
        int h = Math.max(1, Math.round(w * (TEX_H / (float) TEX_W)));

        // Height cap. Everything above sized the banner by width and never against the screen height, so on a
        // short logical screen (high gui scale) the derived height ate too much of it. Cap the height at a
        // fraction of the screen height and, when that binds, derive the width BACK from the capped height so
        // the 2.4:1 aspect is kept: the banner just shrinks rather than being squashed.
        int maxH = Math.round(screenH * MAX_HEIGHT_FRACTION);
        if (h > maxH)
        {
            h = maxH;
            w = Math.round(h * (TEX_W / (float) TEX_H));
        }
        // Too small to be worth drawing (the degenerate zero/near-zero window during init, or a screen so short
        // the height cap crushed it below the guard). Return null cleanly rather than a smear.
        if (w < MIN_W)
        {
            return null;
        }

        int x = Math.max(0, screenW - w - MARGIN_RIGHT);
        // Right-pinned in both cases so it never crosses the centred logo. The corner is chosen per screen:
        // TOP_RIGHT on the menus, which have nothing important up there, and BOTTOM_RIGHT on the world and
        // server lists, where the top right is the first list row's player count and status.
        int y = anchor == Anchor.BOTTOM_RIGHT
                ? Math.max(0, screenH - h - MARGIN_BOTTOM)
                : MARGIN_TOP;
        return new Rect(x, y, w, h);
    }

    /**
     * Draw the banner in the corner named by {@code anchor}. Its pitch line is drawn ONLY while the cursor is
     * over the banner, so it stays out of the way until someone shows interest in it. Returns the banner's
     * rectangle so a caller that also handles clicks can use the SAME geometry, or null if it was not drawn.
     */
    public static Rect render(GuiGraphics g, int screenW, int screenH, double mouseX, double mouseY, Anchor anchor)
    {
        Rect r = rectFor(screenW, screenH, anchor);
        if (r == null)
        {
            return null;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEXTURE, r.x(), r.y(), r.w(), r.h(), 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);

        // Pitch line on hover only. The banner itself is always visible; the sentence under it appears when the
        // cursor is on the banner and is otherwise not drawn at all.
        if (!r.contains(mouseX, mouseY))
        {
            return r;
        }

        // The pitch line is drawn at the NORMAL font size, wrapped to the banner's own width.
        //
        // It used to be laid out in the authored TEX_W (837) font space and then scaled down by r.w() / TEX_W to
        // match the drawn banner. That ratio is 0.18 to 0.30 in practice, so the glyphs landed 1.7 to 2.7 logical
        // pixels tall against a normal 9, which is illegible at every gui scale and worst at the high ones. The
        // intent behind it, that the text always span the same fraction of the banner, is not worth a font nobody
        // can read.
        //
        // Wrapping to r.w() at scale 1.0 is also what makes this behave correctly with the gui scale: logical
        // pixels are already multiplied by the gui scale on the way to the screen, so a normal-size font grows with
        // the rest of the menu exactly like a vanilla label, with no scaling of our own.
        Font font = Minecraft.getInstance().font;
        List<FormattedCharSequence> lines =
                font.split(Component.translatable("gui.dmz_ragnarok.mainmenu.kinetic"), Math.max(MIN_W, r.w()));
        int lineH = font.lineHeight + 1;

        // The pitch line sits just below a TOP_RIGHT banner. For a BOTTOM_RIGHT one it goes ABOVE instead:
        // below would run it straight into the footer band the banner was positioned to clear (and off the
        // bottom of the screen). The wording and size of the line are unchanged, only which side of the banner
        // it lands on.
        int textTop = anchor == Anchor.BOTTOM_RIGHT
                ? r.y() - TEXT_GAP - lines.size() * lineH
                : r.y() + r.h() + TEXT_GAP;
        for (int i = 0; i < lines.size(); i++)
        {
            FormattedCharSequence line = lines.get(i);
            // Centred under the banner, in the same logical space the banner was drawn in, so the two stay aligned
            // at every window size without a transform.
            g.drawString(font, line, r.x() + (r.w() - font.width(line)) / 2, textTop + i * lineH, 0xFFFFFFFF, true);
        }
        return r;
    }

    /**
     * Handle a click on the banner. Returns true when the click was ours, so the caller can stop the screen
     * underneath from also acting on it.
     */
    public static boolean click(Screen screen, double mouseX, double mouseY, int screenW, int screenH, Anchor anchor)
    {
        Rect r = rectFor(screenW, screenH, anchor);
        if (r == null || !r.contains(mouseX, mouseY))
        {
            return false;
        }
        ConfirmLinkScreen.confirmLinkNow(URL, screen, false);
        return true;
    }
}
