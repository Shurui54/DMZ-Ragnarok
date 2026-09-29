package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Base screen that lays out content in a fixed virtual canvas ({@code uiWidth} x {@code uiHeight}) and
 * uniformly scales it to fit the window. Subclasses position widgets in virtual coords; this class handles
 * the scale transform and remaps mouse input so clicks still land.
 */
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
        // One canonical scale for every screen (see GuiTheme.CANONICAL_UI_SCALE): a virtual pixel maps to the
        // same number of physical pixels on every screen, so a button/logo/text row is physically identical
        // suite-wide. Only reduce it (uniformly) when this screen's canvas would not otherwise fit the window;
        // never enlarge past canonical.
        double fit = Math.min((double) this.width / uiWidth, (double) this.height / uiHeight)
                * GuiTheme.UI_FIT_MARGIN;
        double scale = Math.min(GuiTheme.CANONICAL_UI_SCALE, fit);
        this.guiScale = Math.max(GuiTheme.MIN_UI_SCALE, scale);
    }
    // Snapped to a WHOLE pixel: centring is a division by two, so an odd window size used to put the panel
    // on a half pixel. GUI blits sample nearest-neighbour, so that makes every source pixel straddle two
    // screen pixels and one-pixel art detail comes out mis-layered. Costs at most half a pixel of centring.

    protected double originX() {
        return Math.round((this.width - uiWidth * guiScale) / 2.0);
    }

    protected double originY() {
        return Math.round((this.height - uiHeight * guiScale) / 2.0);
    }

    /** Convert a real screen X into virtual-canvas space. */
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
