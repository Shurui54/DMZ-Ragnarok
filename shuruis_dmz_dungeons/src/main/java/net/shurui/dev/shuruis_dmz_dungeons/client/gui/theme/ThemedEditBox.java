package net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text input that wears the {@link GuiTheme} field skin instead of vanilla's flat black box and grey/white
 * border, keeping every {@link EditBox} behaviour intact (caret, selection, scrolling, suggestion, char limit,
 * input filter, keyboard handling, editable vs greyed appearance).
 *
 * <p><b>Why a delegating subclass rather than a reimplementation.</b> Vanilla {@link EditBox#renderWidget} does
 * two separable jobs: it paints the border rectangle and black background (only when {@code bordered}), and it
 * lays out text, caret, suggestion and selection. Every caret/selection/scroll coordinate is a function of three
 * things {@code bordered} toggles: the text origin ({@code getX()+4, getY()+(height-8)/2} when bordered, else
 * {@code getX(), getY()}), the inner width ({@code width-8} vs {@code width}), and the click hit offset (also -4).
 * We want none of vanilla's background fills but all of its text/caret/selection maths byte for byte.
 *
 * <p>So this never copies that maths. For the delegated render it flips {@code bordered} off (suppressing exactly
 * vanilla's two background {@code fill} calls) and reproduces the bordered branch's geometry: translate the pose
 * by {@code (+4, +(height-8)/2)} and shrink {@link #width} by 8 for the call, both restored immediately after.
 * Off-render logic ({@code onClick}, {@code isMouseOver}, {@code setHighlightPos}) still sees the real geometry
 * with {@code bordered == true}, so click-to-caret, drag-select and scroll are unchanged.
 *
 * <p>The 4px vanilla horizontal inset is kept as the text padding: it is wider than {@link GuiTheme#FIELD_INSET}
 * (the frame corner size), so text and caret always sit inside the frame. Keeping the vanilla inset is what lets
 * the scroll/caret maths stay untouched.
 *
 * <p>Client-only.
 */
public class ThemedEditBox extends EditBox {

    /**
     * Vanilla's fixed horizontal text inset used by the bordered branch of {@code renderWidget} ({@code getX()+4})
     * and {@code getInnerWidth()} ({@code width-8}). Mirrored here so the pose translate and width shrink below
     * stay in lockstep with vanilla and never drift.
     */
    private static final int VANILLA_INSET_X = 4;
    private static final int VANILLA_INSET_TOTAL = 8;

    /**
     * Mirror of vanilla's private {@code bordered} flag. Vanilla exposes {@code setBordered} but not a getter, and
     * we need the state to decide whether to draw the themed frame and run the border-compensation. Defaults to
     * {@code true} to match vanilla, kept in sync by overriding {@link #setBordered(boolean)}.
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

        // 2. Delegate text/caret/selection/suggestion to vanilla, suppressing only its background fills: flip
        //    bordered off, then reproduce the bordered branch's geometry (origin +4/+(height-8)/2, inner width -8).
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
