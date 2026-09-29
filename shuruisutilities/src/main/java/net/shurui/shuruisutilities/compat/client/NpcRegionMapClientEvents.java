package net.shurui.shuruisutilities.compat.client;


import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

// client driver for the NPC-region World Map integration: the outline overlay (re-injected once a second) and
// the right-drag draw gesture, both only when Xaero's World Map is present, so a client without it never loads
// a Xaero-referencing class. gesture rides ScreenEvents (not an inject) so it survives runtime obf; the
// Xaero-typed work goes through NpcRegionMapInput, only touched behind the WORLDMAP guard.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcRegionMapClientEvents
{
    private NpcRegionMapClientEvents() {}

    private static final boolean WORLDMAP = ModList.get().isLoaded("xaeroworldmap");
    private static int tick = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !WORLDMAP)
            return;
        if (++tick < 20)
            return;
        tick = 0;
        NpcRegionWorldMapOverlay.tick();
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event)
    {
        // never consume the press: Xaero needs it to record its own right-click pos
        if (WORLDMAP)
            NpcRegionMapInput.onPress(event.getScreen(), event.getMouseX(), event.getMouseY(), event.getButton());
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event)
    {
        // cancel (suppress Xaero's menu) only when a real drag created a region
        if (WORLDMAP
                && NpcRegionMapInput.onRelease(event.getScreen(), event.getMouseX(), event.getMouseY(), event.getButton()))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event)
    {
        if (WORLDMAP)
            NpcRegionMapInput.onRender(event.getScreen(), event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event)
    {
        // toggle the overlay while the world map is open (an in-game keybind can't fire here)
        if (WORLDMAP)
            NpcRegionMapInput.onKey(event.getScreen(), event.getKeyCode());
    }
}
