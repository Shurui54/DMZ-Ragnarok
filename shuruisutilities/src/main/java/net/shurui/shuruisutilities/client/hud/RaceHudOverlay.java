package net.shurui.shuruisutilities.client.hud;

import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import net.shurui.shuruisutilities.racing.client.RaceClientState;
import net.shurui.shuruisutilities.racing.client.RaceItemIcons;
import net.shurui.shuruisutilities.racing.net.PacketRaceResults;
import net.shurui.shuruisutilities.racing.physics.PowerupKind;

/**
 * The core race HUD, registered in {@code SUClientMenus} and drawn only while a race session is active (which itself
 * requires the {@code racing} feature synced from the server, so a keyless or non-racing client draws nothing). It is
 * a pure reader of {@link RaceClientState}: position, lap, the race timer and last-lap split, the centre "3 2 1 GO!"
 * countdown, the WRONG WAY and FINAL LAP banners, the finishing results table, and the rescue fade. Server
 * authoritative throughout; the HUD invents no state.
 */
public final class RaceHudOverlay
{
    public static final String OVERLAY_ID = "race_hud";

    private RaceHudOverlay() {}

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GOLD = 0xFFFFD23F;
    private static final int RED = 0xFFFF4040;
    private static final int CYAN = 0xFF6FE0FF;

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        if (!RaceClientState.isSessionActive())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui)
            return;
        Font font = mc.font;

        // The results take over when a race finishes: the full RaceResultsScreen is opened (best lap, records beaten),
        // and this compact table is only a fallback for when that screen is not up (e.g. dismissed early).
        if (RaceClientState.resultsActive())
        {
            if (mc.screen == null)
                drawResults(g, font, screenWidth, screenHeight);
            return;
        }

        // Rescue fade: a translucent black wash over everything during the pickup pause.
        if (RaceClientState.rescueActive())
            g.fill(0, 0, screenWidth, screenHeight, 0x80000000);

        // R9 Solar Flare: a fading WHITE wash blinding a racer that was ahead of the user. Stood down when the R11
        // DMZ taiyoken_flash post shader is carrying it instead (else it would double up).
        float blind = RaceClientState.blindAlpha();
        if (blind > 0.001F && !net.shurui.shuruisutilities.racing.client.RacePostShaders.blindShaderActive())
        {
            int a = (int) (0xF0 * Math.min(1.0F, blind));
            g.fill(0, 0, screenWidth, screenHeight, (a << 24) | 0xFFFFFF);
        }

        // R9 Gravity Crush: a fading RED wash (the shake is applied to the camera in RaceClientEvents). Stood down when
        // the R11 DMZ gravity_red post shader is carrying it; the camera shake still runs either way.
        float gravity = RaceClientState.gravityAlpha();
        if (gravity > 0.001F && !net.shurui.shuruisutilities.racing.client.RacePostShaders.gravityShaderActive())
        {
            int a = (int) (0xA0 * Math.min(1.0F, gravity));
            g.fill(0, 0, screenWidth, screenHeight, (a << 24) | 0xC00000);
        }

        // --- top right: position, lap, timer, last split ---
        int place = RaceClientState.place();
        int total = RaceClientState.totalRacers();
        String posStr = ordinal(place);
        int posColor = place == 1 ? GOLD : place == 2 ? 0xFFC0C0C0 : place == 3 ? 0xFFCD7F32 : WHITE;
        int rx = screenWidth - 6;
        int ry = 6;
        drawRight(g, font, posStr, rx, ry, posColor, 1.6F);
        ry += 20;
        if (total > 0)
        {
            drawRight(g, font, "/ " + total, rx, ry, 0xFFBFBFBF, 0.9F);
            ry += 12;
        }
        drawRight(g, font, "Lap " + RaceClientState.lapCurrent() + "/" + RaceClientState.lapTotal(), rx, ry, WHITE, 1.0F);
        ry += 12;
        drawRight(g, font, time(RaceClientState.timeTicks()), rx, ry, WHITE, 1.0F);
        ry += 11;
        if (RaceClientState.lastLapSplit() > 0)
        {
            drawRight(g, font, "last " + time(RaceClientState.lastLapSplit()), rx, ry, 0xFFBFBFBF, 0.85F);
            ry += 11;
        }

        // --- minimap: top-right, under the lap / timer block; the track centreline plus a dot per racer ---
        if (minimapVisible)
            drawMinimap(g, screenWidth, ry + 4);

        // --- TOP CENTRE: the item slot + roulette, the self-powerup ring / badge, the Zeni counter. Moved out of the
        // top-left (R8 follow-up) so it never overlaps DMZ's portrait and ki / health / stamina bars there. Centred
        // horizontally near the top, clear of the centred countdown / banners lower down, at any GUI scale.
        int slotX = (screenWidth - SLOT) / 2;
        int slotY = 6;
        drawItemSlot(g, font, slotX, slotY);
        drawSelfEffect(g, font, slotX, slotY);
        drawZeni(g, font, slotX, slotY);

        // --- centre countdown ---
        int countdown = RaceClientState.countdown();
        if (countdown > 0)
        {
            int n = (countdown + 19) / 20; // ceil to whole seconds: 3, 2, 1
            drawBig(g, font, Integer.toString(n), screenWidth / 2, screenHeight / 2 - 30, GOLD, 3.0F);
        }
        else if (RaceClientState.timeTicks() > 0 && RaceClientState.timeTicks() < 20)
        {
            drawBig(g, font, "GO!", screenWidth / 2, screenHeight / 2 - 30, 0xFF40FF40, 3.0F);
        }

        // --- banners ---
        if (RaceClientState.wrongWay())
            drawBig(g, font, "WRONG WAY", screenWidth / 2, screenHeight / 3, RED, 1.6F);
        else if (RaceClientState.finalLap())
            drawBig(g, font, "FINAL LAP", screenWidth / 2, screenHeight / 3, GOLD, 1.4F);
    }

    // The top-left item slot: a framed 16x16 icon. While the roulette spins the icon cycles across the sixteen
    // powerups, slowing as it nears the reveal (the server already decided the result). Once revealed it holds the
    // item steadily, with charge pips for a multi-use item (Kaioken x3). Empty slot draws nothing.
    private static final int SLOT = 24;

    private static void drawItemSlot(GuiGraphics g, Font font, int SLOT_X, int SLOT_Y)
    {
        boolean spinning = RaceClientState.rouletteActive();
        PowerupKind held = RaceClientState.heldItem();
        if (!spinning && held == PowerupKind.NONE)
            return;

        // Which kind's icon to show this frame: the decided result once revealed, else a decelerating cycle.
        int shownOrdinal;
        if (spinning)
        {
            float p = RaceClientState.rouletteProgress();
            // decelerating phase: fast early, near-still at the reveal, so the wheel visibly slows.
            double phase = 22.0 * (1.0 - (1.0 - p) * (1.0 - p));
            shownOrdinal = (int) Math.floor(phase) % PowerupKind.NONE.ordinal();
        }
        else
        {
            shownOrdinal = held.ordinal();
        }

        int frame = 0xFF000000 | (RaceItemIcons.colour(PowerupKind.byId(shownOrdinal)) & 0xFFFFFF);
        // frame + dark inset
        g.fill(SLOT_X - 1, SLOT_Y - 1, SLOT_X + SLOT + 1, SLOT_Y + SLOT + 1, frame);
        g.fill(SLOT_X, SLOT_Y, SLOT_X + SLOT, SLOT_Y + SLOT, 0xE0121218);

        ResourceLocation icon = RaceItemIcons.icon(shownOrdinal);
        int iconSize = 16;
        int ix = SLOT_X + (SLOT - iconSize) / 2;
        int iy = SLOT_Y + (SLOT - iconSize) / 2;
        if (icon != null)
        {
            g.blit(icon, ix, iy, 0, 0, iconSize, iconSize, iconSize, iconSize);
        }
        else
        {
            // fallback: a themed filled square so the slot still reads if an icon is missing.
            g.fill(ix, iy, ix + iconSize, iy + iconSize, frame);
        }

        // charge pips for a revealed multi-use item.
        if (!spinning)
        {
            int charges = RaceClientState.heldCharges();
            if (charges > 1)
            {
                for (int i = 0; i < charges; i++)
                {
                    int px = SLOT_X + 2 + i * 5;
                    int py = SLOT_Y + SLOT - 4;
                    g.fill(px, py, px + 3, py + 3, GOLD);
                }
            }
        }
    }

    // The x20 window length the client rings against (matches the key's KaiokenX20 window); a fixed reference is
    // fine because the ring only shows the FRACTION remaining and the server sends the live ticks-left.
    private static final int KAIOKEN_X20_WINDOW = 150;

    // The self-powerup indicator around the item slot: a timer ring for Kaioken x20 (its window running down), and a
    // small tinted badge under the slot for the sustained auras (Destroyer, Nimbus, Afterimage). Reads only synced state.
    private static void drawSelfEffect(GuiGraphics g, Font font, int SLOT_X, int SLOT_Y)
    {
        int ord = RaceClientState.selfEffect();
        if (ord < 0)
            return;
        PowerupKind kind = PowerupKind.byId(ord);
        if (kind == PowerupKind.NONE)
            return;
        int colour = RaceItemIcons.colour(kind);

        if (kind == PowerupKind.KAIOKEN_X20)
        {
            // A timer ring hugging the slot, its filled arc shrinking as the window runs out.
            float frac = Math.max(0.0F, Math.min(1.0F, RaceClientState.selfEffectTicks() / (float) KAIOKEN_X20_WINDOW));
            int cx = SLOT_X + SLOT / 2;
            int cy = SLOT_Y + SLOT / 2;
            double radius = SLOT / 2.0 + 5.0;
            int segments = 48;
            int filled = (int) Math.ceil(segments * frac);
            for (int i = 0; i < filled; i++)
            {
                double a = -Math.PI / 2 + (2 * Math.PI) * i / segments; // start at 12 o'clock, clockwise
                int px = cx + (int) Math.round(Math.cos(a) * radius);
                int py = cy + (int) Math.round(Math.sin(a) * radius);
                g.fill(px - 1, py - 1, px + 1, py + 1, colour);
            }
        }
        else
        {
            // A tinted badge with the aura name under the slot.
            String label = kind == PowerupKind.DESTROYER_AURA ? "AURA"
                    : kind == PowerupKind.NIMBUS ? "NIMBUS"
                    : kind == PowerupKind.AFTERIMAGE ? "GHOST" : kind.name();
            int by = SLOT_Y + SLOT + 3;
            int bw = font.width(label) + 6;
            g.fill(SLOT_X - 1, by - 1, SLOT_X + bw, by + 10, 0xC0000000 | (colour & 0xFFFFFF));
            g.drawString(font, label, SLOT_X + 2, by + 1, WHITE, false);
        }
    }

    // The Zeni counter: the zeni icon and the carried count, below the item slot (and any aura badge).
    private static void drawZeni(GuiGraphics g, Font font, int SLOT_X, int SLOT_Y)
    {
        int zeni = RaceClientState.zeni();
        if (zeni <= 0)
            return;
        int y = SLOT_Y + SLOT + 16;
        ResourceLocation icon = RaceItemIcons.icon(PowerupKind.ZENI.ordinal());
        if (icon != null)
            g.blit(icon, SLOT_X, y, 0, 0, 10, 10, 10, 10);
        g.drawString(font, "x" + zeni, SLOT_X + 13, y + 1, GOLD, true);
    }

    // --- minimap (R11) ---
    /** Whether the minimap panel draws; toggled by the racing minimap keybind. On by default. */
    private static boolean minimapVisible = true;

    public static void toggleMinimap()
    {
        minimapVisible = !minimapVisible;
    }

    public static boolean minimapVisible()
    {
        return minimapVisible;
    }

    private static final int MINIMAP_SIZE = 74;

    /**
     * The Super dragon-ball radar dial. The PNG is a 256x256 atlas (DMZ's layout): the dial occupies u 0..120,
     * v 27..145, its green field is centred at (57.5, 88.5) with a radius of about 43.5; the rest is arrow sprites.
     */
    private static final ResourceLocation RADAR = new ResourceLocation("dmz_ragnarok", "textures/gui/super_radar.png");
    private static final int RADAR_TEX = 256;
    private static final int DIAL_U = 0, DIAL_V = 27, DIAL_W = 121, DIAL_H = 119;
    private static final float FIELD_CU = 57.5F, FIELD_CV = 88.5F - DIAL_V, FIELD_R = 43.5F;
    /** How much of the green field's diameter the track's bounding box may span. */
    private static final float FIELD_FILL = 0.78F;
    private static final int FACE_OTHER = 8;
    private static final int FACE_SELF = 12;

    // The race radar: the Super dragon-ball radar dial as the background, the track centreline drawn on it, and each
    // racer as their skin FACE (the local racer's face larger, drawn last so it sits on top). The world is read from
    // RaceClientState (centreline = packet 109, racer xz + ids = packet 110); both are projected into the panel by the
    // same track-bounds transform, so the faces sit on the line.
    private static void drawMinimap(GuiGraphics g, int screenWidth, int topY)
    {
        java.util.List<float[]> line = RaceClientState.centreline();
        if (line.size() < 2)
            return;

        int panelX = screenWidth - 6 - MINIMAP_SIZE;
        int panelY = topY;

        // Track XZ bounds.
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (float[] p : line)
        {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        float spanX = Math.max(1.0e-3F, maxX - minX);
        float spanZ = Math.max(1.0e-3F, maxZ - minZ);
        // The radar dial, its atlas region scaled to the panel width (no grey box).
        int dialH = Math.round(MINIMAP_SIZE * (float) DIAL_H / DIAL_W);
        g.blit(RADAR, panelX, panelY, MINIMAP_SIZE, dialH, DIAL_U, DIAL_V, DIAL_W, DIAL_H, RADAR_TEX, RADAR_TEX);
        // A single uniform scale keeps the track's aspect ratio; centre it on the dial's green field.
        float px = (float) MINIMAP_SIZE / DIAL_W;
        float fieldX = panelX + FIELD_CU * px;
        float fieldY = panelY + FIELD_CV * ((float) dialH / DIAL_H);
        float inner = FIELD_R * px * 2.0F * FIELD_FILL;
        float scale = inner / Math.max(spanX, spanZ);
        float offX = fieldX - spanX * scale / 2.0F;
        float offY = fieldY - spanZ * scale / 2.0F;

        // centreline as a closed polyline (project every point, plot the segments), bright so it reads on the dial.
        int prevX = Integer.MIN_VALUE;
        int prevY = 0;
        int firstX = 0;
        int firstY = 0;
        for (int i = 0; i < line.size(); i++)
        {
            float[] p = line.get(i);
            int sx = Math.round(offX + (p[0] - minX) * scale);
            int sy = Math.round(offY + (p[2] - minZ) * scale);
            if (i == 0)
            {
                firstX = sx;
                firstY = sy;
            }
            else
            {
                plotLine(g, prevX, prevY, sx, sy, 0xFFFFE070);
            }
            prevX = sx;
            prevY = sy;
        }
        // close the loop
        plotLine(g, prevX, prevY, firstX, firstY, 0xFFFFE070);

        // Racer faces: everyone else first, then the local racer bigger on top.
        Minecraft mc = Minecraft.getInstance();
        java.util.List<float[]> dots = RaceClientState.racerDots();
        java.util.List<UUID> ids = RaceClientState.racerUuids();
        UUID self = RaceClientState.selfId();
        int selfIndex = -1;
        for (int i = 0; i < dots.size(); i++)
        {
            UUID id = i < ids.size() ? ids.get(i) : null;
            if (self != null && self.equals(id))
            {
                selfIndex = i;
                continue;
            }
            float[] d = dots.get(i);
            drawFace(g, mc, id, Math.round(offX + (d[0] - minX) * scale), Math.round(offY + (d[1] - minZ) * scale),
                    FACE_OTHER);
        }
        if (selfIndex >= 0)
        {
            float[] d = dots.get(selfIndex);
            drawFace(g, mc, self, Math.round(offX + (d[0] - minX) * scale), Math.round(offY + (d[1] - minZ) * scale),
                    FACE_SELF);
        }
        else if (mc.player != null)
        {
            // Not identified in the dot list (should not happen): the local player's own position and skin.
            drawFace(g, mc, mc.player.getUUID(), Math.round(offX + ((float) mc.player.getX() - minX) * scale),
                    Math.round(offY + ((float) mc.player.getZ() - minZ) * scale), FACE_SELF);
        }
    }

    // A racer's skin FACE centred on (cx, cy): the loaded player's skin when present, else the default skin for the id
    // (a bot has no player entity).
    private static void drawFace(GuiGraphics g, Minecraft mc, UUID id, int cx, int cy, int size)
    {
        ResourceLocation skin = null;
        if (id != null && mc.level != null && mc.level.getPlayerByUUID(id) instanceof AbstractClientPlayer player)
            skin = player.getSkinTextureLocation();
        if (skin == null)
            skin = DefaultPlayerSkin.getDefaultSkin(id != null ? id : new UUID(0L, 0L));
        PlayerFaceRenderer.draw(g, skin, cx - size / 2, cy - size / 2, size);
    }

    // Plot a line into the GUI with 1px fills (GuiGraphics has no line primitive); a short DDA is plenty for a minimap.
    private static void plotLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color)
    {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        if (steps == 0)
        {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++)
        {
            int x = x0 + (x1 - x0) * i / steps;
            int y = y0 + (y1 - y0) * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void drawResults(GuiGraphics g, Font font, int w, int h)
    {
        List<PacketRaceResults.Entry> entries = RaceClientState.results();
        int rows = entries.size();
        int panelW = 220;
        int panelH = 30 + rows * 12 + 8;
        int x = (w - panelW) / 2;
        int y = (h - panelH) / 2;
        g.fill(x, y, x + panelW, y + panelH, 0xC0101018);
        g.fill(x, y, x + panelW, y + 2, GOLD);
        g.drawCenteredString(font, "RESULTS", w / 2, y + 10, GOLD);
        int ry = y + 28;
        for (PacketRaceResults.Entry e : entries)
        {
            String left = ordinal(e.place()) + "  " + e.name();
            String right = e.dnf() ? "DNF" : time(e.timeTicks());
            g.drawString(font, left, x + 10, ry, e.place() == 1 ? GOLD : WHITE, false);
            g.drawString(font, right, x + panelW - 10 - font.width(right), ry, e.dnf() ? RED : 0xFFBFBFBF, false);
            ry += 12;
        }
    }

    // --- helpers ---

    private static void drawRight(GuiGraphics g, Font font, String text, int rightX, int y, int color, float scale)
    {
        g.pose().pushPose();
        int w = font.width(text);
        g.pose().translate(rightX - w * scale, y, 0);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, text, 0, 0, color, true);
        g.pose().popPose();
    }

    private static void drawBig(GuiGraphics g, Font font, String text, int cx, int cy, int color, float scale)
    {
        g.pose().pushPose();
        int w = font.width(text);
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, text, -w / 2, 0, color, true);
        g.pose().popPose();
    }

    private static String ordinal(int n)
    {
        if (n <= 0)
            return "-";
        if (n % 100 >= 11 && n % 100 <= 13)
            return n + "th";
        return switch (n % 10)
        {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    private static String time(int ticks)
    {
        int totalTenths = ticks * 10 / 20; // tenths of a second
        int minutes = totalTenths / 600;
        int seconds = (totalTenths / 10) % 60;
        int tenths = totalTenths % 10;
        return String.format("%d:%02d.%d", minutes, seconds, tenths);
    }
}
