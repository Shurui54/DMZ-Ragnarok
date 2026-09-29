package net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text input that wears the {@link GuiTheme} field skin instead of vanilla's flat black box and border, while
 * keeping every {@link EditBox} behaviour intact (caret, selection, scrolling, hint, char limit, input filter,
 * {@code setResponder}, all keyboard handling, editable vs greyed appearance).
 *
 * <p><b>Why a subclass that delegates rather than a reimplementation.</b> Vanilla {@link EditBox#renderWidget}
 * computes every caret/selection/scroll coordinate from three things {@code bordered} toggles: the text origin
 * ({@code getX()+4, getY()+(height-8)/2} when bordered, else {@code getX(), getY()}), the inner width
 * ({@code width-8} when bordered), and the click hit offset (-4 when bordered). We want vanilla's text maths byte
 * for byte, just not its two background fills.
 *
 * <p>So for the delegated render it flips {@code bordered} off (suppressing exactly those two {@code fill} calls)
 * and reproduces the bordered geometry: translate the pose by {@code (+4, +(height-8)/2)} and shrink
 * {@link #width} by 8 for the call, both restored immediately after. Off-render logic ({@code onClick},
 * {@code isMouseOver}, {@code setHighlightPos}) still sees the real geometry with {@code bordered == true}, so
 * click-to-caret, drag-select and scroll-on-overflow are unchanged.
 *
 * <p>The 4px vanilla inset is kept as the text padding: it is wider than {@link GuiTheme#FIELD_INSET} (the frame
 * corner size), so text and caret always sit inside the frame and never overdraw it. Keeping the vanilla inset is
 * what lets the scroll/caret maths stay untouched. Client-only.
 */
public class ThemedEditBox extends EditBox {

    /**
     * Vanilla's fixed horizontal text inset, from the bordered branch of {@code renderWidget} ({@code getX()+4})
     * and {@code getInnerWidth()} ({@code width-8}). Mirrored as named constants so the pose translate and width
     * shrink below stay in lockstep with vanilla.
     */
    private static final int VANILLA_INSET_X = 4;
    private static final int VANILLA_INSET_TOTAL = 8;

    /**
     * Mirror of vanilla's private {@code bordered} flag. Vanilla has {@code setBordered} but no getter, and we
     * need the state to decide whether to draw the themed frame and run the compensation. Defaults to
     * {@code true} like vanilla, kept in sync by overriding {@link #setBordered(boolean)}.
     */
    private boolean themedBordered = true;

    public ThemedEditBox(Font font, int x, int y, int w, int h, Component message) {
        super(font, x, y, w, h, message);
        // gold value text, greyed non-editable state
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

        // themed frame in place of vanilla's border + black box
        if (themedBordered) {
            ThemeRender.field(g, getX(), getY(), this.width, this.height);
        }

        // Delegate text/caret/selection to vanilla, suppressing only its background fills: flip bordered off,
        // then reproduce the bordered geometry (origin +4,+(height-8)/2; inner width -8).
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
