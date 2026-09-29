package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.resources.ResourceLocation;

/**
 * References to DragonMine Z's own GUI textures, reused so the editor matches DMZ's look exactly.
 * UV rectangles below are taken from DMZ's CharacterStatsScreen.
 */
public final class DmzTextures {

    public static final String DMZ = "dragonminez";

    /** 256x256 atlas. Main panel u0,v0 141x213; text field u142,v22 107x21; button u142,v0 79x21. */
    public static final ResourceLocation MENU_BIG = new ResourceLocation(DMZ, "textures/gui/menu/menubig.png");
    /** 256x256 atlas. Header panel u0,v95 145x58. */
    public static final ResourceLocation MENU_SMALL = new ResourceLocation(DMZ, "textures/gui/menu/menusmall.png");

    public static final int ATLAS = 256;

    // menubig regions
    public static final int PANEL_U = 0, PANEL_V = 0, PANEL_W = 141, PANEL_H = 213;
    public static final int FIELD_U = 142, FIELD_V = 22, FIELD_W = 107, FIELD_H = 21;
    public static final int BUTTON_U = 142, BUTTON_V = 0, BUTTON_W = 79, BUTTON_H = 21;

    // menusmall region
    public static final int HEADER_U = 0, HEADER_V = 95, HEADER_W = 145, HEADER_H = 58;

    private DmzTextures() {
    }

    /**
     * Draw the DMZ menubig panel at any size via nine-slice, so the border stays crisp while the
     * green centre stretches. Used to give the saga editor the same background as the NPC editor.
     */
    public static void panel(net.minecraft.client.gui.GuiGraphics g, int x, int y, int w, int h) {
        final int b = 7; // border thickness in the source texture
        // corners
        g.blit(MENU_BIG, x, y, b, b, (float) PANEL_U, (float) PANEL_V, b, b, ATLAS, ATLAS);
        g.blit(MENU_BIG, x + w - b, y, b, b, (float) (PANEL_U + PANEL_W - b), (float) PANEL_V, b, b, ATLAS, ATLAS);
        g.blit(MENU_BIG, x, y + h - b, b, b, (float) PANEL_U, (float) (PANEL_V + PANEL_H - b), b, b, ATLAS, ATLAS);
        g.blit(MENU_BIG, x + w - b, y + h - b, b, b, (float) (PANEL_U + PANEL_W - b), (float) (PANEL_V + PANEL_H - b), b, b, ATLAS, ATLAS);
        // edges
        g.blit(MENU_BIG, x + b, y, w - 2 * b, b, (float) (PANEL_U + b), (float) PANEL_V, PANEL_W - 2 * b, b, ATLAS, ATLAS);
        g.blit(MENU_BIG, x + b, y + h - b, w - 2 * b, b, (float) (PANEL_U + b), (float) (PANEL_V + PANEL_H - b), PANEL_W - 2 * b, b, ATLAS, ATLAS);
        g.blit(MENU_BIG, x, y + b, b, h - 2 * b, (float) PANEL_U, (float) (PANEL_V + b), b, PANEL_H - 2 * b, ATLAS, ATLAS);
        g.blit(MENU_BIG, x + w - b, y + b, b, h - 2 * b, (float) (PANEL_U + PANEL_W - b), (float) (PANEL_V + b), b, PANEL_H - 2 * b, ATLAS, ATLAS);
        // centre
        g.blit(MENU_BIG, x + b, y + b, w - 2 * b, h - 2 * b, (float) (PANEL_U + b), (float) (PANEL_V + b), PANEL_W - 2 * b, PANEL_H - 2 * b, ATLAS, ATLAS);
    }
}
