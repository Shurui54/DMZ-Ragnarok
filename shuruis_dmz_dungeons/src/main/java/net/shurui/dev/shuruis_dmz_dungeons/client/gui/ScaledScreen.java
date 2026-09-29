package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

// fixed virtual canvas (uiWidth x uiHeight) scaled uniformly to fit the window. subclasses lay everything
// out in virtual coords; this handles the scale transform and remaps mouse input so clicks still land right.
// same trick DMZ uses to keep its menus fitting every resolution.
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
        // number of physical pixels everywhere, so a button/logo/text row is physically identical across the suite.
        // Only reduce it (uniformly) when this canvas would not fit the window; never enlarge past canonical. Source
        // fix for "gui sizes changing between screens".
        double fit = Math.min((double) this.width / uiWidth, (double) this.height / uiHeight)
                * GuiTheme.UI_FIT_MARGIN;
        double scale = Math.min(GuiTheme.CANONICAL_UI_SCALE, fit);
        this.guiScale = Math.max(GuiTheme.MIN_UI_SCALE, scale);
    }
    // Snapped to a WHOLE pixel: centring divides by two, so an odd window size put the panel on a half pixel. GUI
    // blits sample nearest-neighbour, so that straddles every source pixel across two screen pixels and one-pixel
    // art comes out mis-layered. Costs at most half a pixel of centring.

    protected double originX() {
        return Math.round((this.width - uiWidth * guiScale) / 2.0);
    }

    protected double originY() {
        return Math.round((this.height - uiHeight * guiScale) / 2.0);
    }

    // real screen coord -> virtual-canvas coord
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
