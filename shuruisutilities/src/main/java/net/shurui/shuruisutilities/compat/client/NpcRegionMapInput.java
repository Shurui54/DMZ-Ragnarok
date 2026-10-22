package net.shurui.shuruisutilities.compat.client;

import net.shurui.shuruisutilities.compat.mixin.AccessorXaeroWorldMap;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// glue between ScreenEvents and the map-agnostic NpcRegionMapSelect (right-drag box) and NpcRegionMapDraw (freeform
// outline). events (fired only when Xaero's World Map is installed) hand us the Screen + cursor; we confirm it's
// Xaero's map via our AccessorXaeroWorldMap interface (the mixin makes only GuiMap implement it), read the block
// coord under the cursor and the map camera, and run the gestures. only references our accessor interface, never
// GuiMap itself (that would pull Xaero's ScreenBase in at compile).
public final class NpcRegionMapInput
{
    private NpcRegionMapInput() {}

    // below this screen-pixel movement a press is a click, not a pan (Xaero uses the same 5 for its own clicks)
    private static final int CLICK_PX = 5;
    // how close (GUI px) a click must land to the first vertex to close the outline
    private static final int CLOSE_PX = 6;

    // left press point while drawing, to tell a click (add a vertex) from a pan
    private static int leftPressX = Integer.MIN_VALUE, leftPressY;

    /** Whether this player may draw NPC regions on the map at all (key present, server granted manage). */
    private static boolean mayDraw()
    {
        return net.shurui.dev.sdu.api.ClientGate.key() && NpcRegionCacheClient.canManage();
    }

    // press. true when consumed (the caller cancels the event so Xaero never sees it).
    public static boolean onPress(Screen screen, double mouseX, double mouseY, int button)
    {
        if (!(screen instanceof AccessorXaeroWorldMap a))
            return false;
        if (!mayDraw())
        {
            // not an NPC region manager: the right-drag box is still there for server claims (operators)
            if (button == 1 && NpcRegionMapSelect.claimsAllowed())
                NpcRegionMapSelect.begin((int) mouseX, (int) mouseY, a.su$mouseBlockPosX(), a.su$mouseBlockPosZ());
            return false;
        }
        // the draw-mode button in the top-left hint
        if (button == 0 && NpcRegionMapSelect.overDrawButton((int) mouseX, (int) mouseY))
        {
            if (NpcRegionMapDraw.active())
                NpcRegionMapDraw.cancel();
            else
                NpcRegionMapDraw.start(playerDim());
            return true;
        }
        if (NpcRegionMapDraw.active())
        {
            if (button == 1)
            {
                NpcRegionMapDraw.undo(); // right click undoes, and never opens Xaero's menu while drawing
                return true;
            }
            if (button == 0)
            {
                // not consumed: Xaero still pans on a drag; a click is turned into a vertex on release
                leftPressX = (int) mouseX;
                leftPressY = (int) mouseY;
            }
            return false;
        }
        // right press: begin a box selection (not consumed, so Xaero still records its menu pos)
        if (button == 1)
            NpcRegionMapSelect.begin((int) mouseX, (int) mouseY, a.su$mouseBlockPosX(), a.su$mouseBlockPosZ());
        return false;
    }

    // release. true when consumed (a real right-drag that opened the popup, or a right release while drawing).
    public static boolean onRelease(Screen screen, double mouseX, double mouseY, int button)
    {
        if (!(screen instanceof AccessorXaeroWorldMap a))
            return false;
        if (NpcRegionMapDraw.active())
        {
            if (button == 1)
                return true; // the press was consumed; keep Xaero's half of the click away too
            if (button == 0 && leftPressX != Integer.MIN_VALUE)
            {
                boolean click = Math.abs((int) mouseX - leftPressX) < CLICK_PX
                        && Math.abs((int) mouseY - leftPressY) < CLICK_PX;
                leftPressX = Integer.MIN_VALUE;
                if (click && mayDraw())
                    clickVertex(a, mouseX, mouseY);
            }
            return false;
        }
        if (button != 1 || !NpcRegionMapSelect.isDragging())
            return false;
        if (!net.shurui.dev.sdu.api.ClientGate.key() && !NpcRegionMapSelect.claimsAllowed())
            return false;
        return NpcRegionMapSelect.finish(a.su$mouseBlockPosX(), a.su$mouseBlockPosZ(), (int) mouseX, (int) mouseY);
    }

    private static void clickVertex(AccessorXaeroWorldMap a, double mouseX, double mouseY)
    {
        if (!viewingPlayerDim(a))
        {
            NpcRegionMapDraw.wrongDimension();
            return;
        }
        int n = NpcRegionMapDraw.count();
        if (n >= 3)
        {
            int[] p = NpcRegionMapDraw.points();
            float[] first = project(a, p[0] + 0.5, p[1] + 0.5);
            if (Math.abs(first[0] - mouseX) <= CLOSE_PX && Math.abs(first[1] - mouseY) <= CLOSE_PX)
            {
                NpcRegionMapDraw.close();
                return;
            }
        }
        NpcRegionMapDraw.add(a.su$mouseBlockPosX(), a.su$mouseBlockPosZ());
    }

    // key pressed while a screen is open. true when consumed (the caller cancels the event): only the draw-mode keys.
    public static boolean onKey(Screen screen, int keyCode)
    {
        // NPC regions are private: keyless the toggle key is inert here, like its keybind (PrivateKeybinds)
        if (!net.shurui.dev.sdu.api.ClientGate.key() || !(screen instanceof AccessorXaeroWorldMap))
            return false;
        if (NpcRegionMapDraw.active())
        {
            switch (keyCode)
            {
                case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
                    NpcRegionMapDraw.undo();
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    NpcRegionMapDraw.close();
                    return true;
                }
                // Escape leaves draw mode instead of closing the map
                case GLFW.GLFW_KEY_ESCAPE -> {
                    NpcRegionMapDraw.cancel();
                    return true;
                }
                default -> { }
            }
        }
        int bound = net.shurui.shuruisutilities.client.SUKeybinds.TOGGLE_REGION_OVERLAY.getKey().getValue();
        if (bound == GLFW.GLFW_KEY_UNKNOWN || keyCode != bound)
            return false;
        boolean on = NpcRegionCacheClient.toggleOverlay();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.displayClientMessage(
                    Component.literal("§eNPC region overlay " + (on ? "§ashown" : "§7hidden")), true);
        return false; // the overlay toggle never consumed the key (unchanged)
    }

    // after the map renders: saved outlines, the drawing in progress, live selection box, hover tooltip, hint
    public static void onRender(Screen screen, GuiGraphics g, int mouseX, int mouseY)
    {
        if (!(screen instanceof AccessorXaeroWorldMap a))
            return;
        if (!net.shurui.dev.sdu.api.ClientGate.key())
        {
            NpcRegionMapSelect.render(g, mouseX, mouseY); // a claim box being dragged
            return;
        }
        ResourceKey<Level> dimKey = a.su$mouseBlockDim();
        String dim = dimKey == null ? "" : dimKey.location().toString();
        renderSavedOutlines(a, g, dim, screen.width, screen.height);
        if (NpcRegionMapDraw.active() && mayDraw())
            renderDrawing(a, g, mouseX, mouseY);
        NpcRegionMapSelect.render(g, mouseX, mouseY);
        if (!NpcRegionMapDraw.active())
            NpcRegionMapSelect.renderHoverTooltip(g, mouseX, mouseY, dim, a.su$mouseBlockPosX(), a.su$mouseBlockPosZ());
        NpcRegionMapSelect.renderOverlayHint(g, mouseX, mouseY);
    }

    // freeform selections are drawn as outlines on top of Xaero's block fill, in their region colour
    private static void renderSavedOutlines(AccessorXaeroWorldMap a, GuiGraphics g, String dim, int w, int h)
    {
        if (!NpcRegionCacheClient.outlinesVisible() || dim.isEmpty())
            return;
        for (NpcRegionCacheClient.Entry e : NpcRegionCacheClient.entriesIn(dim))
        {
            if (!e.isOutline())
                continue;
            float[] lo = project(a, e.minX, e.minZ);
            float[] hi = project(a, e.maxX + 1, e.maxZ + 1);
            if (hi[0] < 0 || hi[1] < 0 || lo[0] > w || lo[1] > h)
                continue; // off screen
            int n = e.outline.length / 2;
            float[] xy = new float[2 * n];
            for (int i = 0; i < n; i++)
            {
                float[] s = project(a, e.outline[2 * i] + 0.5, e.outline[2 * i + 1] + 0.5);
                xy[2 * i] = s[0];
                xy[2 * i + 1] = s[1];
            }
            int rgb = e.packedFill >>> 8;
            NpcRegionMapRender.polyline(g, xy, n, true, 0xE0000000 | rgb, 1.5f);
        }
    }

    private static void renderDrawing(AccessorXaeroWorldMap a, GuiGraphics g, int mouseX, int mouseY)
    {
        int[] p = NpcRegionMapDraw.points();
        int n = p.length / 2;
        float[] xy = new float[2 * (n + 1)];
        for (int i = 0; i < n; i++)
        {
            float[] s = project(a, p[2 * i] + 0.5, p[2 * i + 1] + 0.5);
            xy[2 * i] = s[0];
            xy[2 * i + 1] = s[1];
        }
        // the rubber band to the cursor: yellow over the first point (a click closes), red when the point would be
        // refused because its edge crosses the outline
        boolean overFirst = n >= 3 && Math.abs(xy[0] - mouseX) <= CLOSE_PX && Math.abs(xy[1] - mouseY) <= CLOSE_PX;
        boolean bad = !overFirst && n >= 2 && net.shurui.shuruisutilities.util.PolygonXZ.newEdgeCrosses(p,
                a.su$mouseBlockPosX(), a.su$mouseBlockPosZ(), false);
        xy[2 * n] = overFirst ? xy[0] : mouseX;
        xy[2 * n + 1] = overFirst ? xy[1] : mouseY;
        if (n >= 2)
            NpcRegionMapRender.polyline(g, xy, n, false, 0xFF55FF55, 2f);
        if (n >= 1)
        {
            float[] band = {xy[2 * (n - 1)], xy[2 * (n - 1) + 1], xy[2 * n], xy[2 * n + 1]};
            NpcRegionMapRender.polyline(g, band, 2, false, bad ? 0xFFFF5555 : (overFirst ? 0xFFFFFF55 : 0xC0FFFFFF), 1.5f);
        }
        for (int i = 0; i < n; i++)
        {
            boolean first = i == 0;
            int fill = first ? (overFirst ? 0xFFFFFF55 : 0xFFFFAA00) : 0xFF55FF55;
            NpcRegionMapRender.marker(g, xy[2 * i], xy[2 * i + 1], first ? 3 : 2, fill, 0xFF000000);
        }
    }

    /** World X/Z (block units, may be fractional) to GUI pixels on the map, from Xaero's camera and scale. */
    private static float[] project(AccessorXaeroWorldMap a, double wx, double wz)
    {
        Minecraft mc = Minecraft.getInstance();
        double gui = mc.getWindow().getGuiScale();
        double scale = a.su$scale();
        double px = (wx - a.su$cameraX()) * scale + mc.getWindow().getWidth() / 2.0;
        double pz = (wz - a.su$cameraZ()) * scale + mc.getWindow().getHeight() / 2.0;
        return new float[] {(float) (px / gui), (float) (pz / gui)};
    }

    private static String playerDim()
    {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    // regions are created in the PLAYER's dimension (the server uses player.level()), so only the matching map view
    // may place vertices; otherwise the coordinates would belong to another world
    private static boolean viewingPlayerDim(AccessorXaeroWorldMap a)
    {
        ResourceKey<Level> viewed = a.su$mouseBlockDim();
        String d = playerDim();
        return viewed != null && viewed.location().toString().equals(d) && d.equals(NpcRegionMapDraw.dim());
    }
}
