package net.shurui.shuruisutilities.compat.client;

import net.shurui.shuruisutilities.compat.mixin.AccessorXaeroWorldMap;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// glue between ScreenEvents and the map-agnostic NpcRegionMapSelect. events (fired only when Xaero's World Map
// is installed) hand us the Screen + cursor; we confirm it's Xaero's map via our AccessorXaeroWorldMap interface
// (the mixin makes only GuiMap implement it), read the block coord under the cursor, and run the right-drag.
// only references our accessor interface, never GuiMap itself (that would pull Xaero's ScreenBase in at compile).
public final class NpcRegionMapInput
{
    private NpcRegionMapInput() {}

    // right press: begin a selection (not consumed, so Xaero still records its menu pos)
    public static void onPress(Screen screen, double mouseX, double mouseY, int button)
    {
        if (button != 1 || !net.shurui.dev.sdu.api.ClientGate.key() || !(screen instanceof AccessorXaeroWorldMap a))
            return;
        NpcRegionMapSelect.begin((int) mouseX, (int) mouseY, a.su$mouseBlockPosX(), a.su$mouseBlockPosZ());
    }

    // right release. true if a real drag created a region (caller cancels the event so Xaero's menu stays
    // closed); false for a plain click.
    public static boolean onRelease(Screen screen, double mouseX, double mouseY, int button)
    {
        if (button != 1 || !NpcRegionMapSelect.isDragging() || !(screen instanceof AccessorXaeroWorldMap a))
            return false;
        if (!net.shurui.dev.sdu.api.ClientGate.key())
            return false;
        return NpcRegionMapSelect.finish(a.su$mouseBlockPosX(), a.su$mouseBlockPosZ(), (int) mouseX, (int) mouseY);
    }

    // key pressed while a screen is open. on Xaero's map, if it's the region-overlay toggle keybind, flip the
    // overlay + announce. true when handled.
    public static boolean onKey(Screen screen, int keyCode)
    {
        // NPC regions are private: keyless the toggle key is inert here, like its keybind (PrivateKeybinds)
        if (!net.shurui.dev.sdu.api.ClientGate.key() || !(screen instanceof AccessorXaeroWorldMap))
            return false;
        int bound = net.shurui.shuruisutilities.client.SUKeybinds.TOGGLE_REGION_OVERLAY.getKey().getValue();
        if (bound == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN || keyCode != bound)
            return false;
        boolean on = NpcRegionCacheClient.toggleOverlay();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null)
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "§eNPC region overlay " + (on ? "§ashown" : "§7hidden")), true);
        return true;
    }

    // after the map renders: live selection box + hover tooltip + toggle hint
    public static void onRender(Screen screen, GuiGraphics g, int mouseX, int mouseY)
    {
        if (!net.shurui.dev.sdu.api.ClientGate.key() || !(screen instanceof AccessorXaeroWorldMap a))
            return;
        NpcRegionMapSelect.render(g, mouseX, mouseY);
        ResourceKey<Level> dimKey = a.su$mouseBlockDim();
        String dim = dimKey == null ? "" : dimKey.location().toString();
        NpcRegionMapSelect.renderHoverTooltip(g, mouseX, mouseY, dim, a.su$mouseBlockPosX(), a.su$mouseBlockPosZ());
        NpcRegionMapSelect.renderOverlayHint(g);
    }
}
