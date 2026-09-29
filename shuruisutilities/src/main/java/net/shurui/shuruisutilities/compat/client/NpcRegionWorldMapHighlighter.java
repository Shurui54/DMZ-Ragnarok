package net.shurui.shuruisutilities.compat.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import xaero.map.highlight.ChunkHighlighter;

// Xaero World Map highlighter that tints each NPC region with a faint BLOCK-precise fill (no outline; a hard
// border renders as ugly solid squares at some zooms). block precision means detached/unaligned selections
// read exactly where they are and touching selections blend into one shape. data from NpcRegionCacheClient.
// tooltips are drawn by the map mixin, so the tooltip hooks return null. references World Map types directly,
// so only loaded when Xaero's World Map is present.
public class NpcRegionWorldMapHighlighter extends ChunkHighlighter
{
    // Xaero colors are packed 0xRRGGBBAA (not ARGB). each region carries its own fill (Entry.packedFill),
    // unset ones use the faint green default.

    public NpcRegionWorldMapHighlighter()
    {
        super(false);
    }

    private static String dim(ResourceKey<Level> key)
    {
        return key.location().toString();
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> key, int regionX, int regionZ)
    {
        return NpcRegionCacheClient.outlinesVisible() && NpcRegionCacheClient.hasAnyIn(dim(key));
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        return NpcRegionCacheClient.outlinesVisible()
                && NpcRegionCacheClient.chunkIntersectsAny(dim(key), chunkX, chunkZ);
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> key, int regionX, int regionZ)
    {
        // any region create/edit/delete bumps the version, forcing a re-render
        return NpcRegionCacheClient.version();
    }

    @Override
    protected int[] getColors(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        if (!NpcRegionCacheClient.outlinesVisible())
            return null;
        String dim = dim(key);
        List<NpcRegionCacheClient.Entry> all = NpcRegionCacheClient.entriesIn(dim);
        if (all.isEmpty())
            return null;

        // selections that could touch this chunk
        int minBX = chunkX << 4, minBZ = chunkZ << 4;
        List<NpcRegionCacheClient.Entry> near = new ArrayList<>();
        for (NpcRegionCacheClient.Entry e : all)
            if (e.minX <= minBX + 15 && e.maxX >= minBX && e.minZ <= minBZ + 15 && e.maxZ >= minBZ)
                near.add(e);
        if (near.isEmpty())
            return null;

        boolean any = false;
        for (int i = 0; i < 16; i++)          // block X within the chunk
            for (int a = 0; a < 16; a++)      // block Z within the chunk
            {
                int bx = minBX + i, bz = minBZ + a;
                for (NpcRegionCacheClient.Entry e : near)
                    if (e.contains(bx, bz))
                    {
                        setResult(i, a, e.packedFill);
                        any = true;
                        break;
                    }
            }
        return any ? resultStore : null;
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        return null; // hover tooltip is drawn by the map mixin instead
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        return null;
    }

    @Override
    public void addMinimapBlockHighlightTooltips(java.util.List<Component> list, ResourceKey<Level> key,
            int chunkX, int chunkZ, int something)
    {
        // no-op: NPC-region info is shown via hover on the world map (see the map mixin)
    }
}
