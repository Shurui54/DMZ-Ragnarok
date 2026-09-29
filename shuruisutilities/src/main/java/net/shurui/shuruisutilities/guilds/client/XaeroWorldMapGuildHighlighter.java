package net.shurui.shuruisutilities.guilds.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.highlight.AbstractHighlighter;
import xaero.map.highlight.HighlighterRegistry;

// registers GuildWorldMapHighlighter into Xaero's World Map. registry reached through public accessors
// (getCurrentSession -> getMapProcessor -> getHighlighterRegistry); only the registry's list field is
// private, so like the minimap side we inject a fresh mutable copy (end() makes the list unmodifiable).
// broad catch so a Xaero change disables the overlay instead of breaking us. loaded only when World Map
// is present.
public final class XaeroWorldMapGuildHighlighter
{
    private XaeroWorldMapGuildHighlighter() {}

    private static GuildWorldMapHighlighter highlighter;
    private static Field listField;
    private static boolean broken = false;

    public static void tick()
    {
        if (broken)
            return;
        try
        {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null)
                return;
            MapProcessor processor = session.getMapProcessor();
            if (processor == null)
                return;
            HighlighterRegistry registry = processor.getHighlighterRegistry();
            if (registry == null)
                return;

            if (highlighter == null)
                highlighter = new GuildWorldMapHighlighter();

            @SuppressWarnings("unchecked")
            List<AbstractHighlighter> list = (List<AbstractHighlighter>) listField().get(registry);
            if (list != null && list.contains(highlighter))
                return;

            List<AbstractHighlighter> fresh = new ArrayList<>(list == null ? List.of() : list);
            fresh.add(highlighter);
            listField().set(registry, fresh);
        }
        catch (Throwable t)
        {
            broken = true;
        }
    }

    private static Field listField() throws Exception
    {
        if (listField == null)
        {
            listField = HighlighterRegistry.class.getDeclaredField("highlighters");
            listField.setAccessible(true);
        }
        return listField;
    }
}
