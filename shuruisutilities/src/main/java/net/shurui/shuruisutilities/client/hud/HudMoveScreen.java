package net.shurui.shuruisutilities.client.hud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Drag-to-move screen for the movable HUD panels: the NPC region panel and the staff task reminder.
 *
 * <h2>One screen for both</h2>
 * They are the only two panels a player positions, they occupy the same screen, and the whole reason anybody opens
 * this is that one of them is in the way of something. Two separate movers would mean dragging one, closing,
 * opening the other, and discovering they now overlap. Showing both at once is the only way the question being
 * asked ("where do these two sit relative to each other") can actually be answered.
 *
 * <p>Both panels are drawn with the SAME method the real HUD uses, so what is dragged here is exactly what appears
 * in game, at the same size and wrapping. A preview that merely approximated the panel would be worse than none:
 * it would be positioned against a shape that does not exist.
 *
 * <p>Sample text fills in whichever panel has nothing live to show, so there is always something to grab. Without
 * it the staff panel would be invisible to anyone not currently holding a task, which is most people opening this.
 */
public class HudMoveScreen extends Screen
{
    private final RegionHudConfig regionCfg = RegionHudConfig.get();
    private final StaffTaskHudConfig staffCfg = StaffTaskHudConfig.get();

    private int[] regionBounds = {0, 0, 0, 0};
    private int[] staffBounds = {0, 0, 0, 0};

    /** Which panel the cursor is currently dragging, or null. */
    private Dragging dragging;
    private int dragOffsetX, dragOffsetY;

    private enum Dragging { REGION, STAFF }

    public HudMoveScreen()
    {
        super(Component.literal("Move HUD"));
    }

    @Override
    protected void init()
    {
        addRenderableWidget(Button.builder(Component.literal("Reset both"), b -> {
            regionCfg.reset();
            staffCfg.reset();
        }).bounds(width / 2 - 102, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 + 2, height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        g.drawCenteredString(font, "§eDrag either panel to reposition it.", width / 2, 14, 0xFFFFFF);

        regionBounds = RegionHudOverlay.renderPanel(g, sampleRegionTitle(), sampleDifficulty(),
                sampleDescription(), regionCfg, width, height);
        staffBounds = StaffTaskHudOverlay.renderPanel(g, sampleTaskTitle(), sampleTaskDescription(), "",
                staffCfg, width, height);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient.Entry liveEntry()
    {
        var mc = minecraft;
        if (mc == null || mc.player == null || mc.level == null)
            return null;
        return net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient.regionAt(
                mc.level.dimension().location().toString(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    private String sampleRegionTitle()
    {
        var e = liveEntry();
        return e != null ? e.displayTitle() : "Region Name";
    }

    private String sampleDifficulty()
    {
        var e = liveEntry();
        return e != null && !e.difficulty.isBlank() ? e.difficulty : "&6Hard";
    }

    private String sampleDescription()
    {
        var e = liveEntry();
        return e != null && !e.description.isBlank() ? e.description : "Region description appears here.";
    }

    private String sampleTaskTitle()
    {
        return StaffTaskHudState.isActive() && !StaffTaskHudState.title().isBlank()
                ? StaffTaskHudState.title() : "Staff Task";
    }

    private String sampleTaskDescription()
    {
        return StaffTaskHudState.isActive() && !StaffTaskHudState.description().isBlank()
                ? StaffTaskHudState.description() : "What you accepted appears here.";
    }

    private static boolean inside(double mx, double my, int[] b)
    {
        return mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3];
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        if (button == 0)
        {
            // Staff first: it is drawn second, so it is the one on top where the two overlap, and grabbing the
            // panel you can actually see is the only behaviour that will not feel broken.
            if (inside(mx, my, staffBounds))
                return grab(Dragging.STAFF, mx, my, staffBounds);
            if (inside(mx, my, regionBounds))
                return grab(Dragging.REGION, mx, my, regionBounds);
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean grab(Dragging which, double mx, double my, int[] bounds)
    {
        dragging = which;
        // Offset from the panel's own anchor (centre-x, top-y), so the panel does not jump to the cursor on grab.
        dragOffsetX = (int) mx - (bounds[0] + bounds[2] / 2);
        dragOffsetY = (int) my - bounds[1];
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy)
    {
        if (dragging == Dragging.REGION)
        {
            regionCfg.xPct = (mx - dragOffsetX) / Math.max(1, width);
            regionCfg.yPct = (my - dragOffsetY) / Math.max(1, height);
            regionCfg.clamp();
            return true;
        }
        if (dragging == Dragging.STAFF)
        {
            staffCfg.xPct = (mx - dragOffsetX) / Math.max(1, width);
            staffCfg.yPct = (my - dragOffsetY) / Math.max(1, height);
            staffCfg.clamp();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button)
    {
        dragging = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void onClose()
    {
        // Both, always. Only one may have moved, but saving the other writes the value it already had.
        regionCfg.save();
        staffCfg.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
