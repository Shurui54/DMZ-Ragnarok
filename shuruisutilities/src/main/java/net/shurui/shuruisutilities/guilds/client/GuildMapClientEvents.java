package net.shurui.shuruisutilities.guilds.client;


import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

// client-only driver for the Xaero guild-claims overlay. once a second it re-injects the guild highlighter
// into Xaero's current minimap session, but only if Xaero's Minimap is installed, so a client without Xaero
// never loads a Xaero-referencing class. CLIENT dist only.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GuildMapClientEvents
{
    private GuildMapClientEvents() {}

    private static final boolean MINIMAP = ModList.get().isLoaded("xaerominimap");
    private static final boolean WORLDMAP = ModList.get().isLoaded("xaeroworldmap");
    private static int tick = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || (!MINIMAP && !WORLDMAP))
            return;
        if (++tick < 20)
            return;
        tick = 0;
        // each highlighter references only its own Xaero mod's classes, so guard separately, the class
        // isn't loaded unless that mod is present
        if (MINIMAP)
            XaeroGuildHighlighter.tick();
        if (WORLDMAP)
            XaeroWorldMapGuildHighlighter.tick();
    }
}
