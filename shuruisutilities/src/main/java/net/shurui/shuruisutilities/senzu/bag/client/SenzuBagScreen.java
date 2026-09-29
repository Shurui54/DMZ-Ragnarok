package net.shurui.shuruisutilities.senzu.bag.client;

import net.shurui.shuruisutilities.senzu.bag.SenzuBagMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Screen for the senzu bean bag, skinned with the commissioned artwork
 * (assets/shuruisutilities/textures/gui/senzu_bag.png) instead of the shared DMZ panel. This is a purely cosmetic
 * layer: every rule about what the bag may hold is enforced by the server-side {@link SenzuBagMenu} and its events,
 * and this class only draws the background and mirrors the synced slot contents. Mirrors {@code DragonBallBagScreen}.
 *
 * <p>The sheet paints a bean bag with a 3x2 pocket of six sockets on its body, and the standard player inventory grid
 * below it. {@link SenzuBagMenu} places its slots to line up with those drawn sockets and grid cells, so here we only
 * have to blit the sheet at the right size; the slot highlights fall exactly on the artwork.
 */
public class SenzuBagScreen extends AbstractContainerScreen<SenzuBagMenu>
{
    // The commissioned 256x256 container sheet. If this file is ever missing the game does NOT crash: vanilla's
    // texture manager substitutes the magenta/black missing-texture placeholder, so the menu stays fully usable.
    private static final ResourceLocation BAG_TEXTURE =
            new ResourceLocation("dmz_ragnarok", "textures/gui/senzu_bag.png");

    // The sheet is a full 256x256 atlas; this is its pixel size so blit() can map UVs correctly.
    private static final int SHEET_SIZE = 256;

    // Drawn window size, measured off the artwork's opaque content. The bag sprite and six-socket pocket occupy the
    // top; the grey player-inventory grid runs down to its bottom border at y=221, hence a 222px tall window. The
    // frame is a standard 176px wide container.
    private static final int WINDOW_WIDTH = 176;
    private static final int WINDOW_HEIGHT = 222;

    public SenzuBagScreen(SenzuBagMenu menu, Inventory playerInv, Component title)
    {
        super(menu, playerInv, title);
        this.imageWidth = WINDOW_WIDTH;
        this.imageHeight = WINDOW_HEIGHT;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY)
    {
        // Blit the whole sheet at the window origin. The overload with explicit atlas size keeps the UVs correct on
        // the 256x256 texture, and GuiGraphics.blit handles shader and texture binding internally, so it cannot leave
        // the render thread in a bad state even if the texture is absent.
        g.blit(BAG_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, SHEET_SIZE, SHEET_SIZE);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY)
    {
        // Deliberately draw NO text. The artwork fills the top of the window with the bag sprite where a vanilla
        // title would sit, and paints no label rows, so any "Senzu Bag" / "Inventory" strings would overlap the
        // picture. The bag sprite itself identifies the screen; the window title is still set on the menu for
        // narration and accessibility, it is simply not drawn over the art.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
