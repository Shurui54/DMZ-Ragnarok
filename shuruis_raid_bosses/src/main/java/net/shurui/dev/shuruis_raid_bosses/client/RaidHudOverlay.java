package net.shurui.dev.shuruis_raid_bosses.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokBarAnim;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHud;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudElement;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudFrame;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;

/**
 * The raid progress bar, replacing the vanilla boss bar with DMZ's ki-sense HP art
 * ({@code textures/gui/hud/alternativehud.png}), top-centre where the boss bar lived. Shows NPCs left during
 * waves, boss health once a single boss is the fight. Drawn BELOW sdu's waypoint compass strip by default.
 *
 * <p>A Ragnarok HUD extra ({@link RagnarokHudSlot#RAID_BAR}): placed, scaled and hidden in DragonMineZ's HUD editor,
 * slid in and out like DMZ's panels, and its fill moves like DMZ's bars (eased, with a trailing loss chip and a hit
 * flash, see {@link RagnarokBarAnim}).
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

    // local layout: the label line, the bar, the value line
    private static final int LABEL_H = 11;
    private static final int BOX_W = FRAME_W * SCALE;
    private static final int BOX_H = LABEL_H + FRAME_H * SCALE + 3 + 9;

    private RaidHudOverlay() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenW, int screenH) {
        RagnarokHud.draw(RagnarokHudSlot.RAID_BAR, g, partialTick, screenW, screenH);
    }

    /** Bind the bar to its slot in DMZ's HUD layout. */
    public static void registerElement() {
        RagnarokHud.register(new Element());
    }

    private static final class Element extends RagnarokHudElement {
        private final RagnarokBarAnim bar = new RagnarokBarAnim();
        private float lastMax = -1.0f;
        private String label = "";
        private String value = "";

        Element() {
            super(RagnarokHudSlot.RAID_BAR);
        }

        @Override
        public float width() {
            return BOX_W;
        }

        @Override
        public float height() {
            return BOX_H;
        }

        @Override
        public float[] defaultPlacement() {
            return new float[] {0.5f, 0.0f, 0.0f, BAR_TOP - LABEL_H, 1.0f};
        }

        @Override
        public boolean update(boolean preview, float partialTick) {
            boolean live = RaidHudState.visible();
            if (!live && !preview) {
                return false;
            }
            float current = live ? RaidHudState.current() : 7.0f;
            float max = Math.max(1.0f, live ? RaidHudState.max() : 10.0f);
            if (max != lastMax) {
                // a new fight or a new phase: no chip from the old one
                bar.reset(current / max);
                lastMax = max;
            }
            bar.update(current / max);
            label = live ? RaidHudState.label() : "Raid";
            value = live && RaidHudState.mode() == net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket.MODE_BOSS_HP
                    ? fmt(current) + " / " + fmt(max)
                    : net.minecraft.client.resources.language.I18n.get("hud.dmz_ragnarok.raid.enemies_remaining", (int) current);
            return true;
        }

        @Override
        public void render(GuiGraphics g, float partialTick, RagnarokHudFrame frame) {
            Minecraft mc = Minecraft.getInstance();
            int w = FRAME_W * SCALE;
            int h = FRAME_H * SCALE;
            int x = 0;
            int y = LABEL_H;

            // Depleted backdrop (red strip), the trailing loss chip, the green fill up to the eased fraction, the hit
            // flash, then the frame on top.
            int fillX = x + FILL_U * SCALE;
            int fillY = y + 2 * SCALE;
            int fillFullW = FILL_W * SCALE;
            g.blit(DMZ_HUD, fillX, fillY, fillFullW, FILL_H * SCALE, DEPLETED_U, DEPLETED_V, FILL_W, FILL_H, ATLAS, ATLAS);
            float front = bar.front();
            float ghost = bar.ghost();
            if (ghost > front + 0.001f) {
                // DMZ's chips: pale for a loss, green for a burst gain
                int chip = bar.healing() ? 0x8C57FF6B : 0xBDE4E8FF;
                g.fill(fillX + Math.round(fillFullW * front), fillY,
                        fillX + Math.round(fillFullW * ghost), fillY + FILL_H * SCALE, chip);
            }
            int fillW = Math.round(fillFullW * front);
            if (fillW > 0) {
                int texW = Math.max(1, Math.round(FILL_W * front));
                g.blit(DMZ_HUD, fillX, fillY, fillW, FILL_H * SCALE, FILL_U, FILL_V, texW, FILL_H, ATLAS, ATLAS);
                float hit = bar.impact();
                if (hit > 0.02f) {
                    g.fill(fillX, fillY, fillX + fillW, fillY + FILL_H * SCALE, (Math.round(hit * 0.35f * 255.0f) << 24) | 0xFFFFFF);
                }
            }
            RenderSystem.enableBlend();
            g.blit(DMZ_HUD, x, y, w, h, FRAME_U, FRAME_V, FRAME_W, FRAME_H, ATLAS, ATLAS);

            // Label above, value below, enemies remaining during waves, boss HP once the boss is up.
            var font = mc.font;
            if (!label.isBlank()) {
                g.drawString(font, label, (BOX_W - font.width(label)) / 2, 0, 0xFFF6E27A, true);
            }
            g.drawString(font, value, (BOX_W - font.width(value)) / 2, y + h + 3, 0xFFFFFFFF, true);
        }
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.round(v));
    }
}
