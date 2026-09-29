package net.shurui.dev.shuruis_raid_bosses.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text input wearing the {@link GuiTheme} field skin instead of vanilla's flat black box, keeping every
 * {@link EditBox} behaviour intact (caret, scrolling, selection, suggestion, limit, filter, responder,
 * keyboard, greyed-out state).
 *
 * <p>Subclass-and-delegate, not a reimplementation: vanilla {@link EditBox#renderWidget} paints the border +
 * black background (only when {@code bordered}) and separately lays out text/caret/suggestion/selection. This
 * never copies the text maths. For the delegated render it flips {@code bordered} off (suppressing exactly
 * vanilla's two background {@code fill}s) and reproduces the bordered branch's geometry: translate the pose by
 * {@code (+4, +(height-8)/2)} and shrink {@link #width} by 8, so scrolling and highlight extents match. Both
 * restored immediately after.
 *
 * <p>Client-only.
 */
public class ThemedEditBox extends EditBox {

    /**
     * Vanilla's fixed text inset: the bordered {@code renderWidget} uses {@code getX()+4} and
     * {@code getInnerWidth()} uses {@code width-8}. Mirrored so the pose translate and width shrink stay in
     * lockstep with vanilla.
     */
    private static final int VANILLA_INSET_X = 4;
    private static final int VANILLA_INSET_TOTAL = 8;

    /**
     * Mirror of vanilla's private {@code bordered} flag (vanilla has {@code setBordered} but no getter); needed
     * to decide whether to draw the themed frame and compensate. Defaults {@code true} like vanilla, kept in
     * sync by overriding {@link #setBordered(boolean)}.
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
