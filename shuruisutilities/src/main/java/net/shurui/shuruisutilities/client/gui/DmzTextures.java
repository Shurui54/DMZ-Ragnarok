package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

// DMZ's own GUI textures, reused so SU menus match DMZ's look (DMZ is a hard dep, always present). UVs taken
// from DMZ's CharacterStatsScreen.
public final class DmzTextures
{
    public static final String DMZ = "dragonminez";

    // 256x256 atlas. panel u0,v0 141x213; field u142,v22 107x21; button u142,v0 79x21.
    public static final ResourceLocation MENU_BIG = new ResourceLocation(DMZ, "textures/gui/menu/menubig.png");
    // 256x256 atlas. header u0,v95 145x58.
    public static final ResourceLocation MENU_SMALL = new ResourceLocation(DMZ, "textures/gui/menu/menusmall.png");

    public static final int ATLAS = 256;

    // menubig regions
    public static final int PANEL_U = 0, PANEL_V = 0, PANEL_W = 141, PANEL_H = 213;
    public static final int FIELD_U = 142, FIELD_V = 22, FIELD_W = 107, FIELD_H = 21;
    public static final int BUTTON_U = 142, BUTTON_V = 0, BUTTON_W = 79, BUTTON_H = 21;

    // menusmall region
    public static final int HEADER_U = 0, HEADER_V = 95, HEADER_W = 145, HEADER_H = 58;

    // DMZ-ish palette
    public static final int GOLD = 0xFFF6E27A;
    public static final int GREEN = 0xFF9BE0AB;
    public static final int GRAY = 0xFFB0B0B0;
    public static final int RED = 0xFFE06A6A;

    private DmzTextures() {}

    // Draw the themed panel background at any size. Now delegates to the commissioned theme art
    // (ThemeRender.panel) so every screen that calls DmzTextures.panel (the chest screens, direct-render
    // screens) picks up the new spliced panel with no edit of its own. The theme, not this class, owns the
    // panel look; the menubig UV constants above are retained only so existing call sites keep compiling.
    public static void panel(GuiGraphics g, int x, int y, int w, int h)
    {
        net.shurui.shuruisutilities.client.gui.theme.ThemeRender.panel(g, x, y, w, h);
    }
}
