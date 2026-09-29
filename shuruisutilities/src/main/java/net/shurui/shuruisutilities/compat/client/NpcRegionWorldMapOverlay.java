package net.shurui.shuruisutilities.compat.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.highlight.AbstractHighlighter;
import xaero.map.highlight.HighlighterRegistry;

// registers NpcRegionWorldMapHighlighter into Xaero's highlighter registry (public accessors; the list field
// is private so we reflect it and swap in a fresh mutable copy). broad catch so a Xaero change disables the
// overlay instead of breaking the mod. only loaded when the World Map is present.
public final class NpcRegionWorldMapOverlay
{
    private NpcRegionWorldMapOverlay() {}

    private static NpcRegionWorldMapHighlighter highlighter;
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
                highlighter = new NpcRegionWorldMapHighlighter();

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
