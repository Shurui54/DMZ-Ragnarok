package net.shurui.shuruisutilities.core.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.client.gui.quest.StoryToast;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import net.shurui.shuruisutilities.client.gui.task.QuestArt;

/**
 * Reskins DragonMineZ's quest notification toast onto the commissioned quest banner, taking over BOTH the art
 * and the text.
 *
 * <p>DMZ's stock toast ({@code StoryToast}) is a 220x52 box: a tone coloured background, a 2px top accent, a 1px
 * bottom shadow, a countdown bar, then its title at (8,6) and a wrapped description below it, both laid out to
 * the box. An earlier reskin kept the banner but squeezed the title and description INSIDE the bar, to the right
 * of the badge. The owner read that as the text duplicating the banner graphic (the banner already reads as the
 * quest label), exactly the same complaint the tracker had. The tracker's fix was to draw the banner BARE and put
 * all text BELOW it; this mixin now does the same.</p>
 *
 * <p>The banner is drawn bare at {@link #SU_TOAST_SCALE} (0.62, a 219x36 bar, well short of the authored 354x58 the
 * owner called huge), and the title and description are laid out UNDERNEATH it at NORMAL font size, wrapped to the
 * box width. The WIDTH is unchanged from the approved deployed toast (219). The height grows by the MINIMUM needed:
 * the 36px bare banner plus a tight text block of at most {@link #SU_MAX_LINES} lines and the gold countdown, so a
 * one line message is a 52px box and the three line cap is a 72px box (the old text-on-banner box was 36px tall).
 * The budget is deliberately NOT generous: three lines is exactly DMZ's own stock toast capacity (a title plus a
 * two line description), at least one line is reserved for the description so a long title cannot eat the box, and
 * any overflow is signalled by an ellipsis rather than drawn past the box. The FONT is never scaled. DMZ's own
 * title and description Components and its Tone are reused untouched, so a failure still reads red and a success
 * green. The gold countdown bar is kept, sitting at the box bottom as real feedback.</p>
 *
 * <p>Both size overrides are updated together, not just the height: vanilla right-aligns a toast by {@code width()}
 * and stacks toasts by {@code height()}, so a width or height that disagreed with the drawn box would push the
 * toast off the screen edge or overlap its neighbours. {@code width()} is the banner width; {@code height()} is the
 * banner height plus the measured text block and the countdown, computed from the SAME font and budget the render
 * uses, so the reserved box and the drawn content always agree.</p>
 *
 * <p>Mixin discipline: the target is DMZ's own class, so {@code @Mixin(remap = false)}. The three injected methods
 * (render, width, height) are DMZ's overrides of VANILLA {@code Toast} members, so their selectors carry
 * {@code remap = true} to be mapped to their runtime names. The three shadowed fields are DMZ's OWN members, so
 * they stay {@code remap = false} (inherited from the class). Every injector is {@code require = 0} so a DMZ
 * reshape degrades to the stock box instead of crashing the toast. Handler parameters match each target exactly.</p>
 */
@Mixin(targets = "com.dragonminez.client.gui.quest.StoryToast", remap = false)
public abstract class MixinDmzStoryToast
{
    /** Gold, matching the banner's underline, for the countdown bar. */
    private static final int SU_COUNTDOWN_COLOUR = 0xFFFFC24A;

    private static final int SU_TITLE_COLOUR = 0xFFFFFFFF;
    private static final int SU_DESC_INFO = 0xFFD8E1FF;
    private static final int SU_DESC_FAILURE = 0xFFFF7A7A;
    private static final int SU_DESC_SUCCESS = 0xFF9CE88B;

    private static final long SU_DURATION_MS = 5000L;

    /**
     * Uniform scale for the toast banner. The banner is now bare (it carries no text), so this only sizes the
     * title graphic. 0.62 gives a 219x36 bar: wide enough that the text block beneath wraps in few lines, short of
     * the authored 354x58 size the owner called huge. Both axes take one factor, so the badge cap, body detail and
     * right taper keep their shape. The font is never scaled to this: the text below is at normal size.
     */
    private static final float SU_TOAST_SCALE = 0.62F;

    /** Left and right padding for the text block below the banner. */
    private static final int SU_PAD_X = 5;
    /** Full size text line advance. Never a scaled font: this is normal 9px text with 1px of breathing room. */
    private static final int SU_LINE_H = 10;
    /** Countdown bar thickness, sitting at the box bottom. */
    private static final int SU_COUNTDOWN_H = 2;
    /** Banner bottom to the first text line. Kept tight so the box grows the minimum. */
    private static final int SU_GAP_BANNER_TEXT = 2;
    /** Space under the countdown bar. */
    private static final int SU_PAD_BOTTOM = 2;

    /**
     * Total line budget for the text block beneath the banner. Kept tight because the box must not grow generously:
     * three 10px lines is exactly DMZ's own stock toast capacity (a title plus a two line description). At least one
     * line is always reserved for the description; overflow past the budget is signalled with an ellipsis, not
     * drawn past the box. The box height is computed from the ACTUAL wrapped line count up to this cap, so a one
     * line message is a short 52px box and a full three line message is the 72px cap, without ever scaling the font.
     */
    private static final int SU_MAX_LINES = 3;

    @Shadow @Final private Component title;
    @Shadow @Final private Component description;
    @Shadow @Final private StoryToast.Tone tone;

    /** Match the on screen slot width to the bare banner so the 1:1 blit fits exactly to the right screen edge. */
    @Inject(method = "width", at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$width(CallbackInfoReturnable<Integer> cir)
    {
        cir.setReturnValue(QuestArt.scaledW(SU_TOAST_SCALE));
    }

    /** Box height: banner + countdown + gaps + the measured text block. Vanilla stacks toasts by this value. */
    @Inject(method = "height", at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$height(CallbackInfoReturnable<Integer> cir)
    {
        try
        {
            Font font = Minecraft.getInstance().font;
            int wrapW = QuestArt.scaledW(SU_TOAST_SCALE) - 2 * SU_PAD_X;
            int[] counts = su$budget(font.split(this.title, wrapW).size(),
                                     font.split(this.description, wrapW).size());
            cir.setReturnValue(su$boxHeight(counts[0] + counts[1]));
        }
        catch (Throwable ignored)
        {
            // A conservative two line box, still consistent with the render's fallback path.
            cir.setReturnValue(su$boxHeight(2));
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;"
                    + "Lnet/minecraft/client/gui/components/toasts/ToastComponent;J)"
                    + "Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$render(GuiGraphics g, ToastComponent toastComponent, long delta,
                           CallbackInfoReturnable<Toast.Visibility> cir)
    {
        try
        {
            final int w = QuestArt.scaledW(SU_TOAST_SCALE);

            // The bare banner, uniformly scaled: it is the toast's title graphic, so no text is drawn on it.
            QuestArt.banner(g, 0, 0, SU_TOAST_SCALE);

            final Font font = toastComponent.getMinecraft().font;
            final int wrapW = w - 2 * SU_PAD_X;

            final List<FormattedCharSequence> titleLines = font.split(this.title, wrapW);
            final List<FormattedCharSequence> descLines = font.split(this.description, wrapW);
            final int[] counts = su$budget(titleLines.size(), descLines.size());
            final int titleCount = counts[0];
            final int descCount = counts[1];

            // Text sits BELOW the banner, laid out at normal font size. Shadowed for readability over the world,
            // matching the tracker panel.
            int y = su$textTop();
            for (int i = 0; i < titleCount; i++)
            {
                g.drawString(font, titleLines.get(i), SU_PAD_X, y, SU_TITLE_COLOUR, true);
                y += SU_LINE_H;
            }

            final int descColour = su$descColour();
            for (int i = 0; i < descCount; i++)
            {
                g.drawString(font, descLines.get(i), SU_PAD_X, y, descColour, true);
                y += SU_LINE_H;
            }

            // Anything past the line budget is signalled, not drawn past the box: an ellipsis on the last line.
            boolean truncated = titleLines.size() > titleCount || descLines.size() > descCount;
            if (truncated && (titleCount + descCount) > 0)
            {
                int ellipsisW = font.width("...");
                g.drawString(font, "...", w - SU_PAD_X - ellipsisW, y - SU_LINE_H, descColour, true);
            }

            // Countdown, recoloured gold, at the box bottom. Shrinks left to right as the toast ages. The box height
            // is recomputed from the same budget height() used, so the bar sits exactly on the box's lower edge.
            final int h = su$boxHeight(titleCount + descCount);
            final int barY = h - SU_PAD_BOTTOM - SU_COUNTDOWN_H;
            final int barMaxW = w - 2 * SU_PAD_X;
            final int progressWidth = (int) ((1.0f - Math.min((float) delta / (float) SU_DURATION_MS, 1.0f)) * barMaxW);
            g.fill(SU_PAD_X, barY, SU_PAD_X + progressWidth, barY + SU_COUNTDOWN_H, SU_COUNTDOWN_COLOUR);

            cir.setReturnValue(delta >= SU_DURATION_MS ? Toast.Visibility.HIDE : Toast.Visibility.SHOW);
        }
        catch (Throwable ignored)
        {
            // Leave cir unset so DMZ's own render runs: a degraded stock box beats a crashed toast.
        }
    }

    /** Y of the first text line: just below the bare banner. */
    private static int su$textTop()
    {
        return QuestArt.scaledH(SU_TOAST_SCALE) + SU_GAP_BANNER_TEXT;
    }

    /** Box height for a given drawn line count: banner, gap, the text block, then the countdown and bottom pad. */
    private static int su$boxHeight(int lines)
    {
        return su$textTop() + lines * SU_LINE_H + SU_COUNTDOWN_H + SU_PAD_BOTTOM;
    }

    /**
     * Split the {@link #SU_MAX_LINES} budget between the title and the description. The title takes what it needs up
     * to the budget, but at least one line is reserved for the description when there is one, so a long title cannot
     * eat the whole box.
     *
     * @return a two element array: title line count, then description line count.
     */
    private static int[] su$budget(int titleSize, int descSize)
    {
        int titleBudget = descSize == 0 ? SU_MAX_LINES : Math.max(1, SU_MAX_LINES - 1);
        int titleCount = Math.min(titleBudget, titleSize);
        int descCount = Math.min(SU_MAX_LINES - titleCount, descSize);
        return new int[] { titleCount, descCount };
    }

    private int su$descColour()
    {
        if (this.tone == StoryToast.Tone.FAILURE)
            return SU_DESC_FAILURE;
        if (this.tone == StoryToast.Tone.SUCCESS)
            return SU_DESC_SUCCESS;
        return SU_DESC_INFO;
    }
}
