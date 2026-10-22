package net.shurui.shuruisutilities.compat.client;

import java.util.ArrayList;
import java.util.List;


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// state + rendering for the "right-drag a region" gesture on Xaero's World Map. fed press/release events with
// the block coords under the cursor; draws the live box and, on a real drag, opens the add-selection popup
// (add the box to an existing NPC region or create a new one, or make it a server claim or a claim's new area). a plain right-click is left alone so Xaero's own
// menu still opens. no Xaero types, so it links even when Xaero is absent (the mixin just never calls it).
public final class NpcRegionMapSelect
{
    // below this screen-pixel movement a right-press is a click (menu), not a drag
    private static final int DRAG_THRESHOLD_PX = 5;

    private static boolean dragging;
    // screen press point (for the box + click-vs-drag test) and the world XZ at press
    private static int pressScreenX, pressScreenY;
    private static int startWorldX, startWorldZ;

    private NpcRegionMapSelect() {}

    public static boolean isDragging()
    {
        return dragging;
    }

    // begin a selection: record the screen point + world XZ under the cursor
    public static void begin(int screenX, int screenY, int worldX, int worldZ)
    {
        // drag-create is for NPC region managers and, for server claims, operators (the server enforces the nodes
        // and the op level too; this avoids a pointless drag + error for everyone else)
        if (!NpcRegionCacheClient.canManage() && !claimsAllowed())
            return;
        dragging = true;
        pressScreenX = screenX;
        pressScreenY = screenY;
        startWorldX = worldX;
        startWorldZ = worldZ;
    }

    public static void cancel()
    {
        dragging = false;
    }

    /**
     * May this player make a server claim from the map box? Server claims are the private regions feature and the
     * /serverclaim command is op level, so both are asked: the synced feature answer and the client's own permission
     * level. The server checks the command permission again.
     */
    public static boolean claimsAllowed()
    {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2)
                && net.shurui.dev.sdu.api.ClientGate.feature(net.shurui.shuruisutilities.api.key.RegionHooks.FEATURE_ID);
    }

    // finish a right-drag. barely moved = a click: no-op, false so Xaero opens its own menu. otherwise open the
    // add-selection popup (add the box to a region or create a new one) and return true to suppress the menu.
    public static boolean finish(int worldX, int worldZ, int screenX, int screenY)
    {
        if (!dragging)
            return false;
        dragging = false;
        if (Math.abs(screenX - pressScreenX) < DRAG_THRESHOLD_PX && Math.abs(screenY - pressScreenY) < DRAG_THRESHOLD_PX)
            return false; // a click, leave it for Xaero's menu
        Minecraft mc = Minecraft.getInstance();
        // regions are created in the PLAYER's dim (server uses player.level()), so list that dim's regions
        // regardless of which map dim is being viewed
        String dim = mc.level != null ? mc.level.dimension().location().toString() : "minecraft:overworld";
        mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.NpcRegionAddSelectionScreen(
                dim, startWorldX, startWorldZ, worldX, worldZ));
        return true;
    }

    // draw the translucent selection rect + border from press point to cursor
    public static void render(GuiGraphics g, int mouseX, int mouseY)
    {
        if (!dragging)
            return;
        int x1 = Math.min(pressScreenX, mouseX);
        int y1 = Math.min(pressScreenY, mouseY);
        int x2 = Math.max(pressScreenX, mouseX);
        int y2 = Math.max(pressScreenY, mouseY);
        g.fill(x1, y1, x2, y2, 0x3355FF55);          // translucent green fill
        int border = 0xFF55FF55;
        g.fill(x1, y1, x2, y1 + 1, border);           // top
        g.fill(x1, y2 - 1, x2, y2, border);           // bottom
        g.fill(x1, y1, x1 + 1, y2, border);           // left
        g.fill(x2 - 1, y1, x2, y2, border);           // right
    }

    // while the overlay is on and not mid-drag, hovering a region shows its info tooltip (name, spawns, reward
    // ranges, selection size). no modifier needed since the overlay is already manager-only.
    public static void renderHoverTooltip(GuiGraphics g, int mouseX, int mouseY, String dim, int blockX, int blockZ)
    {
        if (dragging || dim == null || dim.isEmpty() || !NpcRegionCacheClient.outlinesVisible())
            return;
        NpcRegionCacheClient.Entry e = NpcRegionCacheClient.regionAt(dim, blockX, blockZ);
        if (e == null)
            return;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§a§lNPC Region: §r§f" + e.name));
        lines.add(Component.literal("§7Spawns: §f" + e.entity + " §7x §f" + e.count + " §8/ player"));
        if (e.tpMax > 0)
            lines.add(Component.literal("§7TP / kill: §f" + range(e.tpMin, e.tpMax)));
        if (e.balMax > 0)
            lines.add(Component.literal("§7Balance / kill: §f" + range(e.balMin, e.balMax)));
        int w = e.maxX - e.minX + 1, h = e.maxZ - e.minZ + 1;
        long selections = NpcRegionCacheClient.entriesIn(dim).stream().filter(x -> x.name.equals(e.name)).count();
        String size = e.isOutline()
                ? Component.translatable("gui.dmz_ragnarok.core.npcdraw.hover_size", e.outline.length / 2, e.areaXZ()).getString()
                : w + " x " + h + " blocks";
        lines.add(Component.literal("§8" + size
                + (selections > 1 ? " (one of " + selections + " selections)" : "")));
        g.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
    }

    // the draw-mode button under the overlay hint (GUI px), shared by the renderer and the click test
    private static final int DRAW_BTN_X = 6, DRAW_BTN_Y = 18, DRAW_BTN_H = 12;

    private static int drawButtonWidth()
    {
        return Minecraft.getInstance().font.width(drawButtonLabel()) + 8;
    }

    private static Component drawButtonLabel()
    {
        return Component.translatable(NpcRegionMapDraw.active()
                ? "gui.dmz_ragnarok.core.npcdraw.button_stop" : "gui.dmz_ragnarok.core.npcdraw.button_start");
    }

    /** Is this GUI point on the draw-mode button? (only shown to region managers) */
    public static boolean overDrawButton(int x, int y)
    {
        return NpcRegionCacheClient.canManage() && x >= DRAW_BTN_X && x < DRAW_BTN_X + drawButtonWidth()
                && y >= DRAW_BTN_Y && y < DRAW_BTN_Y + DRAW_BTN_H;
    }

    // top-left hint: overlay on/off + which key toggles it, then the draw-mode button and, while drawing, the
    // controls and the latest message. drawn every frame the map is open.
    public static void renderOverlayHint(GuiGraphics g, int mouseX, int mouseY)
    {
        // overlay is manager-only; hide the hint from everyone else
        if (!NpcRegionCacheClient.canManage())
            return;
        Minecraft mc = Minecraft.getInstance();
        boolean hover = overDrawButton(mouseX, mouseY);
        int bw = drawButtonWidth();
        g.fill(DRAW_BTN_X, DRAW_BTN_Y, DRAW_BTN_X + bw, DRAW_BTN_Y + DRAW_BTN_H, hover ? 0xC0306030 : 0xA0000000);
        g.fill(DRAW_BTN_X, DRAW_BTN_Y + DRAW_BTN_H - 1, DRAW_BTN_X + bw, DRAW_BTN_Y + DRAW_BTN_H, 0xFF55FF55);
        g.drawString(mc.font, drawButtonLabel(), DRAW_BTN_X + 4, DRAW_BTN_Y + 2, NpcRegionMapDraw.active() ? 0xFFFF55 : 0x55FF55, true);
        int y = DRAW_BTN_Y + DRAW_BTN_H + 3;
        if (NpcRegionMapDraw.active())
        {
            g.drawString(mc.font, Component.translatable("gui.dmz_ragnarok.core.npcdraw.status",
                    NpcRegionMapDraw.count(), net.shurui.shuruisutilities.util.PolygonXZ.MAX_VERTICES), 6, y, 0xFFFFFF, true);
            y += 10;
            g.drawString(mc.font, Component.translatable("gui.dmz_ragnarok.core.npcdraw.help1"), 6, y, 0xAAAAAA, true);
            y += 10;
            g.drawString(mc.font, Component.translatable("gui.dmz_ragnarok.core.npcdraw.help2"), 6, y, 0xAAAAAA, true);
            y += 10;
        }
        Component msg = NpcRegionMapDraw.message();
        if (msg != null)
            g.drawString(mc.font, msg, 6, y, 0xFFFFFF, true);
        String keyName = net.shurui.shuruisutilities.client.SUKeybinds.TOGGLE_REGION_OVERLAY.getKey().getValue()
                == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN
                ? "unbound, set in Controls"
                : net.shurui.shuruisutilities.client.SUKeybinds.TOGGLE_REGION_OVERLAY.getKey().getDisplayName().getString();
        boolean on = NpcRegionCacheClient.overlayEnabled();
        String text = "§eNPC Regions: " + (on ? "§aON" : "§cOFF") + " §7[" + keyName + "]";
        g.drawString(mc.font, Component.literal(text), 6, 6, 0xFFFFFF, true);
    }

    private static String range(int lo, int hi)
    {
        return lo == hi ? Integer.toString(hi) : lo + " - " + hi;
    }
}
