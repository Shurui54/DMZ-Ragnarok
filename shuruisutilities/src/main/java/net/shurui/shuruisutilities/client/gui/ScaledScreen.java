package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

// base screen: lays out in a fixed virtual canvas (uiWidth x uiHeight) and uniformly scales it to fit the
// window. subclasses work in virtual coords; this handles the scale transform and remaps mouse input so clicks
// still land.
public abstract class ScaledScreen extends Screen {

    protected final int uiWidth;
    protected final int uiHeight;
    protected double guiScale = 1.0;

    protected ScaledScreen(Component title, int uiWidth, int uiHeight) {
        super(title);
        this.uiWidth = uiWidth;
        this.uiHeight = uiHeight;
    }

    @Override
    protected void init() {
        // One canonical scale for every screen (GuiTheme.CANONICAL_UI_SCALE): a virtual pixel maps to the same
        // number of physical pixels on every screen, so a button/logo/text row is physically identical across the
        // suite. Only reduce it (uniformly) when this screen's canvas would not otherwise fit the window; never
        // enlarge past canonical. This is the source fix for "the gui sizes are changing between screens".
        double fit = Math.min((double) this.width / uiWidth, (double) this.height / uiHeight)
                * GuiTheme.UI_FIT_MARGIN;
        double scale = Math.min(GuiTheme.CANONICAL_UI_SCALE, fit);
        this.guiScale = Math.max(GuiTheme.MIN_UI_SCALE, scale);
    }

    // SNAPPED TO A WHOLE PIXEL, and that matters more than it looks. Centring is a division by two, so on an odd
    // window width or height this used to land the whole panel on a HALF pixel. GUI blits sample
    // nearest-neighbour, so a half-pixel offset makes every source pixel straddle two screen pixels: some source
    // rows get drawn twice as wide as their neighbours and the phase of that doubling shifts with the window
    // size. Large flat shapes do not care, which is why this went unnoticed. One-pixel detail cares a great deal:
    // it is what made the dragon balls in the task plaque corners come out looking mis-layered against the gold
    // border, with the ball's red centre landing on the wrong side of it.
    //
    // Rounding costs at most half a pixel of centring, which nobody can see, and buys pixel-exact art everywhere.
    // Both the draw transform and the mouse mapping below go through these, so they stay in agreement.
    protected double originX() {
        return Math.round((this.width - uiWidth * guiScale) / 2.0);
    }

    protected double originY() {
        return Math.round((this.height - uiHeight * guiScale) / 2.0);
    }

    // real screen coord -> virtual canvas space
    protected double toVirtualX(double screenX) {
        return (screenX - originX()) / guiScale;
    }

    protected double toVirtualY(double screenY) {
        return (screenY - originY()) / guiScale;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(toVirtualX(mouseX), toVirtualY(mouseY), button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(toVirtualX(mouseX), toVirtualY(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(toVirtualX(mouseX), toVirtualY(mouseY), button, dragX / guiScale, dragY / guiScale);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return super.mouseScrolled(toVirtualX(mouseX), toVirtualY(mouseY), delta);
    }
}
