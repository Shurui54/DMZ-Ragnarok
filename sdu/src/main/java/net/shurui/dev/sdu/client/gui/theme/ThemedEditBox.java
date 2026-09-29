package net.shurui.dev.sdu.client.gui.theme;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text input that wears the {@link GuiTheme} field skin instead of vanilla's flat black box and grey/white
 * border, while keeping every {@link EditBox} behaviour intact: caret blink and position, horizontal scrolling
 * of long content, click/drag/double-click selection, shift-click extend, the selection highlight rectangle,
 * the suggestion/hint text, the character limit, the input filter, {@code setResponder}, all keyboard handling
 * (arrows, home/end, ctrl+A/C/V/X, backspace/delete) and the editable vs greyed-out appearance.
 *
 * <p><b>Why a subclass that delegates rather than a reimplementation.</b> Vanilla
 * {@link EditBox#renderWidget} does two separable jobs: it paints the border rectangle and the black
 * background (only when {@code bordered}), and it lays out the text, caret, suggestion and selection. Every
 * caret/selection/scroll coordinate vanilla computes is a function of three things that {@code bordered}
 * toggles: the text origin ({@code getX()+4, getY()+(height-8)/2} when bordered, else {@code getX(), getY()}),
 * the inner width ({@code width-8} when bordered, else {@code width}), and the click hit offset (also -4 when
 * bordered). We do not want vanilla's two background fills, but we want its text/caret/selection maths byte for
 * byte.
 *
 * <p>So this class never copies that maths. Instead, for the duration of the delegated render it flips
 * {@code bordered} off (which suppresses exactly and only vanilla's two background {@code fill} calls), and it
 * reproduces the bordered branch's geometry precisely: it translates the pose by {@code (+4, +(height-8)/2)}
 * so the text origin lands where the bordered branch would put it, and it shrinks {@link #width} by 8 for the
 * call so vanilla's inner-width and therefore its scrolling and highlight extents are identical to the bordered
 * case. Both are restored immediately after. Off-render logic ({@code onClick}, {@code isMouseOver},
 * {@code setHighlightPos}) still sees the real geometry with {@code bordered == true}, so click-to-caret,
 * drag-select and scroll-on-overflow are unchanged. The only vanilla drawing we replace is the background; all
 * text, caret and selection drawing is delegated to {@code super}.
 *
 * <p>The 4px vanilla horizontal inset is kept as the text padding: it is wider than {@link GuiTheme#FIELD_INSET}
 * (the frame corner size), so the text and caret always sit inside the frame and never touch or overdraw it,
 * even when the caret scrolls to the right edge. Keeping the vanilla inset rather than substituting a theme
 * padding is what lets us leave the scroll/caret maths untouched.
 *
 * <p>Client-only.
 */
public class ThemedEditBox extends EditBox {

    /**
     * Vanilla's fixed horizontal text inset used by the bordered branch of {@code renderWidget}
     * ({@code getX()+4}) and by {@code getInnerWidth()} ({@code width-8}). Mirrored here as named constants so
     * the pose translate and width shrink below stay in lockstep with vanilla and never drift.
     */
    private static final int VANILLA_INSET_X = 4;
    private static final int VANILLA_INSET_TOTAL = 8;

    /**
     * Mirror of vanilla's private {@code bordered} flag. Vanilla exposes {@code setBordered} but not a getter,
     * and we must know the state to decide whether to draw the themed frame and run the border-compensation.
     * Defaults to {@code true} to match vanilla's constructor default, and is kept in sync by overriding
     * {@link #setBordered(boolean)}.
     */
    private boolean themedBordered = true;

    public ThemedEditBox(Font font, int x, int y, int w, int h, Component message) {
        super(font, x, y, w, h, message);
        // Theme colours for the value text (gold) and the greyed non-editable state.
        setTextColor(GuiTheme.COLOR_VALUE);
        setTextColorUneditable(GuiTheme.COLOR_DISABLED);
    }

    @Override
    public void setBordered(boolean bordered) {
        super.setBordered(bordered);
        this.themedBordered = bordered;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }

        // 1. Draw the themed frame in place of vanilla's border + black box.
        if (themedBordered) {
            ThemeRender.field(g, getX(), getY(), this.width, this.height);
        }

        // 2. Delegate text/caret/selection/suggestion to vanilla, suppressing only its background fills.
        //    Flip bordered off (kills the two fills), then reproduce the bordered branch's exact geometry:
        //    translate the origin by (+4, +(height-8)/2) and shrink the inner width by 8 for the call.
        boolean wasBordered = themedBordered;
        int realWidth = this.width;
        if (wasBordered) {
            setBordered(false);
            this.width = realWidth - VANILLA_INSET_TOTAL;
            g.pose().pushPose();
            g.pose().translate(VANILLA_INSET_X, (this.height - 8) / 2f, 0);
        }
        try {
            super.renderWidget(g, mouseX, mouseY, partialTick);
        } finally {
            if (wasBordered) {
                g.pose().popPose();
                this.width = realWidth;
                setBordered(wasBordered);
            }
        }
    }
}
