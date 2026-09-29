package net.shurui.shuruisutilities.npcregion;

import net.minecraft.server.MinecraftServer;

import net.shurui.dev.sdu.api.ContentImportHook;
import net.shurui.shuruisutilities.data.v2.DataManager;

/**
 * The core-SU side of {@link ContentImportHook}: parses an NPC region from JSON (an event bundle's template and
 * eventOnly regions, e.g. Halloween's {@code event_halloween_haunt} and {@code event_pumpkin_patch}) and stores it
 * through {@link NpcRegionManager}. Registered once from the SU mod constructor. The JSON is the same shape the
 * region file uses ({@link DataManager#getGson()}), so a region authored in-game can be exported and re-imported.
 *
 * <p>Only the region set changes; nothing here mutates an existing region, so a normal region's JSON stays
 * byte-identical. An imported region carries whatever {@code eventOnly} the JSON sets (a template or event region
 * ships {@code true}), which the region system honours through the event hooks.
 */
public final class NpcRegionImport implements ContentImportHook.Provider
{
    private NpcRegionImport() {}

    /** Register the provider with core. Call once from the SU mod constructor. */
    public static void register()
    {
        ContentImportHook.register(new NpcRegionImport());
    }

    @Override
    public boolean importRegion(MinecraftServer server, String json)
    {
        if (server == null || json == null || json.isBlank())
            return false;
        NpcRegion region;
        try
        {
            region = DataManager.getGson().fromJson(json, NpcRegion.class);
        }
        catch (Exception e)
        {
            return false;
        }
        if (region == null || region.name == null || region.name.isBlank())
            return false;
        region.sanitize();
        NpcRegionManager.instance().put(region);
        return true;
    }
}
