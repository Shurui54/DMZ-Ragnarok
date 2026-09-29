package net.shurui.dev.shuruis_raid_bosses.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * The raid progress bar, replacing the vanilla boss bar with DMZ's ki-sense HP art
 * ({@code textures/gui/hud/alternativehud.png}), top-centre where the boss bar lived. Shows NPCs left during
 * waves, boss health once a single boss is the fight. Drawn BELOW sdu's waypoint compass strip (compass
 * occupies roughly y 3-30 incl. labels) so they never overlap.
 */
public final class RaidHudOverlay {

    public static final String OVERLAY_ID = "raid_progress_bar";

    /** DMZ's ki-sense HUD atlas (128x128): bar frame at (0,0) 83x9, green fill (2,11) 79x4, red (2,22) 79x4. */
    private static final ResourceLocation DMZ_HUD = new ResourceLocation("dragonminez", "textures/gui/hud/alternativehud.png");
    private static final int ATLAS = 128;
    private static final int FRAME_U = 0, FRAME_V = 0, FRAME_W = 83, FRAME_H = 9;
    private static final int FILL_U = 2, FILL_V = 11, FILL_W = 79, FILL_H = 4;
    private static final int DEPLETED_U = 2, DEPLETED_V = 22;

    private static final int SCALE = 2;
    /** Top-centre, below sdu's compass strip (bar y4-17 + waypoint labels to ~30). */
    private static final int BAR_TOP = 36;

    private RaidHudOverlay() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !RaidHudState.visible()) {
            return;
        }
        float max = Math.max(1.0f, RaidHudState.max());
        float fraction = Math.max(0.0f, Math.min(1.0f, RaidHudState.current() / max));

        int w = FRAME_W * SCALE;
        int h = FRAME_H * SCALE;
        int x = (screenW - w) / 2;
        int y = BAR_TOP;

        // Depleted backdrop (red strip), then the green fill up to the fraction, then the frame on top.
        int fillX = x + FILL_U * SCALE;
        int fillY = y + 2 * SCALE;
        int fillFullW = FILL_W * SCALE;
        g.blit(DMZ_HUD, fillX, fillY, fillFullW, FILL_H * SCALE, DEPLETED_U, DEPLETED_V, FILL_W, FILL_H, ATLAS, ATLAS);
        int fillW = Math.round(fillFullW * fraction);
        if (fillW > 0) {
            int texW = Math.max(1, Math.round(FILL_W * fraction));
            g.blit(DMZ_HUD, fillX, fillY, fillW, FILL_H * SCALE, FILL_U, FILL_V, texW, FILL_H, ATLAS, ATLAS);
        }
        g.blit(DMZ_HUD, x, y, w, h, FRAME_U, FRAME_V, FRAME_W, FRAME_H, ATLAS, ATLAS);

        // Label above, value below, enemies remaining during waves, boss HP once the boss is up.
        var font = mc.font;
        String label = RaidHudState.label();
        if (!label.isBlank()) {
            g.drawString(font, label, (screenW - font.width(label)) / 2, y - 11, 0xFFF6E27A, true);
        }
        String value = RaidHudState.mode() == net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket.MODE_BOSS_HP
                ? fmt(RaidHudState.current()) + " / " + fmt(RaidHudState.max())
                : net.minecraft.client.resources.language.I18n.get(
                        "hud.dmz_ragnarok.raid.enemies_remaining", (int) RaidHudState.current());
        g.drawString(font, value, (screenW - font.width(value)) / 2, y + h + 3, 0xFFFFFFFF, true);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.round(v));
    }
}
