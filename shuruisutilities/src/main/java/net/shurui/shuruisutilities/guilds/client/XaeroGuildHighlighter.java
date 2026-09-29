package net.shurui.shuruisutilities.guilds.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.MinimapProcessor;
import xaero.common.minimap.highlight.AbstractHighlighter;
import xaero.common.minimap.highlight.HighlighterRegistry;
import xaero.common.minimap.write.MinimapWriter;

// registers GuildChunkHighlighter into Xaero's minimap (and via Xaero's bridge, world map).
// Xaero 26.1.0 has no public API to add a highlighter: the per-session HighlighterRegistry is a private
// field of MinimapWriter and end() makes its list unmodifiable. so we reach the writer through public
// accessors (getCurrentSession -> getMinimapProcessor -> getMinimapWriter) and reflect the two private
// fields, injecting our highlighter into a fresh mutable copy. broad catch so a Xaero internal change just
// disables the overlay instead of breaking us. only loads when Xaero is present (ModList.isLoaded guard).
public final class XaeroGuildHighlighter
{
    private XaeroGuildHighlighter() {}

    private static GuildChunkHighlighter highlighter;
    private static Field writerRegistryField;
    private static Field registryListField;
    private static boolean broken = false;

    // makes sure our highlighter is in the current session's registry. cheap to call periodically.
    public static void tick()
    {
        if (broken)
            return;
        try
        {
            XaeroMinimapSession session = XaeroMinimapSession.getCurrentSession();
            if (session == null)
                return;
            MinimapProcessor processor = session.getMinimapProcessor();
            if (processor == null)
                return;
            MinimapWriter writer = processor.getMinimapWriter();
            if (writer == null)
                return;

            HighlighterRegistry registry = registry(writer);
            if (registry == null)
                return;

            if (highlighter == null)
                highlighter = new GuildChunkHighlighter();

            @SuppressWarnings("unchecked")
            List<AbstractHighlighter> list = (List<AbstractHighlighter>) listField().get(registry);
            if (list != null && list.contains(highlighter))
                return; // already registered

            List<AbstractHighlighter> fresh = new ArrayList<>(list == null ? List.of() : list);
            fresh.add(highlighter);
            listField().set(registry, fresh);
        }
        catch (Throwable t)
        {
            // Xaero internals differ from what we compiled against, disable rather than spam errors
            broken = true;
        }
    }

    private static HighlighterRegistry registry(MinimapWriter writer) throws Exception
    {
        if (writerRegistryField == null)
        {
            writerRegistryField = MinimapWriter.class.getDeclaredField("highlighterRegistry");
            writerRegistryField.setAccessible(true);
        }
        return (HighlighterRegistry) writerRegistryField.get(writer);
    }

    private static Field listField() throws Exception
    {
        if (registryListField == null)
        {
            registryListField = HighlighterRegistry.class.getDeclaredField("highlighters");
            registryListField.setAccessible(true);
        }
        return registryListField;
    }
}
