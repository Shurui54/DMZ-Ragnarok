package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * A small HSV colour picker popup in the DMZ style: a live swatch, three draggable gradient bars
 * (hue / saturation / value) like DragonMine Z's {@code ColorSlider}, plus "None" (clears the colour
 * to an empty string) and "Done". Rendered as a top overlay by the owning screen, which routes mouse
 * input to it and reads {@link #hex()} when it closes. All geometry is in the screen's virtual coords.
 */
public class DmzColorPicker {

    private static final int PW = 152;
    private static final int PH = 84;
    private static final int BAR_H = 9;

    private final int x;
    private final int y;

    private float hue;         // 0..1
    private float sat = 1f;    // 0..1
    private float val = 1f;    // 0..1
    private boolean none;      // colour cleared (empty string)
    private int dragBar = -1;  // 0 hue, 1 sat, 2 val, -1 none
    private boolean closeRequested;

    public DmzColorPicker(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Load a "#RRGGBB" value (blank/invalid => the "None" state). */
    public void open(String hex) {
        float[] hsv = hexToHsv(hex);
        if (hsv == null) {
            none = true;
            hue = 0f;
            sat = 1f;
            val = 1f;
        } else {
            none = false;
            hue = hsv[0];
            sat = hsv[1];
            val = hsv[2];
        }
    }

    /** Current value: "" when cleared, else "#RRGGBB". */
    public String hex() {
        if (none) {
            return "";
        }
        int rgb = hsvToRgb(hue, sat, val);
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    public boolean isCloseRequested() {
        return closeRequested;
    }

    public boolean contains(double mx, double my) {
        return mx >= x - 2 && mx < x + PW + 2 && my >= y - 2 && my < y + PH + 2;
    }

    public boolean mouseClicked(double mx, double my) {
        for (int i = 0; i < 3; i++) {
            int by = barY(i);
            if (mx >= x + 6 && mx < x + 6 + barW() && my >= by && my < by + BAR_H) {
                none = false;
                dragBar = i;
                setFromX(i, mx);
                return true;
            }
        }
        if (in(mx, my, x + 6, y + 68, 58, 12)) {   // None
            none = true;
            closeRequested = true;
            return true;
        }
        if (in(mx, my, x + PW - 52, y + 68, 46, 12)) { // Done
            closeRequested = true;
            return true;
        }
        return contains(mx, my);
    }

    public void mouseDragged(double mx, double my) {
        if (dragBar >= 0) {
            setFromX(dragBar, mx);
        }
    }

    public void mouseReleased() {
        dragBar = -1;
    }

    private void setFromX(int bar, double mx) {
        float frac = (float) Math.max(0.0, Math.min(1.0, (mx - (x + 6)) / barW()));
        switch (bar) {
            case 0 -> hue = frac;
            case 1 -> sat = frac;
            case 2 -> val = frac;
            default -> { }
        }
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        g.fill(x - 2, y - 2, x + PW + 2, y + PH + 2, 0xFF10281A);   // border
        g.fill(x, y, x + PW, y + PH, 0xF0071A10);                    // body

        // Swatch + hex readout.
        int rgb = hsvToRgb(hue, sat, val);
        g.fill(x + 6, y + 6, x + 36, y + 20, 0xFF000000);
        if (none) {
            g.drawString(font, "§7none", x + 10, y + 9, 0xFFFFFFFF, false);
        } else {
            g.fill(x + 7, y + 7, x + 35, y + 19, 0xFF000000 | rgb);
        }
        g.drawString(font, none ? "§7(no colour)" : hex(), x + 42, y + 9, 0xFFF6E27A, false);

        drawBar(g, 0, mouseY);
        drawBar(g, 1, mouseY);
        drawBar(g, 2, mouseY);

        g.drawString(font, "§7[ None ]", x + 8, y + 70, 0xFFE0A0A0, false);
        g.drawString(font, "§a[ Done ]", x + PW - 50, y + 70, 0xFFCFE8B0, false);
    }

    private void drawBar(GuiGraphics g, int bar, int mouseY) {
        int bx = x + 6;
        int by = barY(bar);
        int bw = barW();
        for (int i = 0; i < bw; i++) {
            float frac = (float) i / bw;
            int c = switch (bar) {
                case 0 -> hsvToRgb(frac, 1f, 1f);
                case 1 -> hsvToRgb(hue, frac, val);
                default -> hsvToRgb(hue, sat, frac);
            };
            g.fill(bx + i, by, bx + i + 1, by + BAR_H, 0xFF000000 | c);
        }
        float pos = switch (bar) {
            case 0 -> hue;
            case 1 -> sat;
            default -> val;
        };
        int hx = bx + Math.round(pos * (bw - 1));
        g.fill(hx - 1, by - 1, hx + 2, by + BAR_H + 1, 0xFFFFFFFF); // handle
    }

    private int barY(int bar) {
        return y + 26 + bar * (BAR_H + 5);
    }

    private int barW() {
        return PW - 12;
    }

    private static boolean in(double mx, double my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }

    private static int hsvToRgb(float h, float s, float v) {
        float r = 0, g = 0, b = 0;
        int i = (int) Math.floor(h * 6) % 6;
        if (i < 0) {
            i += 6;
        }
        float f = h * 6 - (float) Math.floor(h * 6);
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        int ri = Math.round(r * 255), gi = Math.round(g * 255), bi = Math.round(b * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Parse "#RRGGBB" (or "RRGGBB") into {h,s,v}; null if blank/invalid. */
    private static float[] hexToHsv(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(s, 16);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float gg = ((rgb >> 8) & 0xFF) / 255f;
            float b = (rgb & 0xFF) / 255f;
            float max = Math.max(r, Math.max(gg, b));
            float min = Math.min(r, Math.min(gg, b));
            float v = max;
            float sat = max == 0 ? 0 : (max - min) / max;
            float h = 0;
            float d = max - min;
            if (d != 0) {
                if (max == r) {
                    h = ((gg - b) / d) % 6;
                } else if (max == gg) {
                    h = (b - r) / d + 2;
                } else {
                    h = (r - gg) / d + 4;
                }
                h /= 6;
                if (h < 0) {
                    h += 1;
                }
            }
            return new float[]{h, sat, v};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
