package net.shurui.shuruisutilities.guilds.client;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import xaero.map.highlight.ChunkHighlighter;

// Xaero World Map highlighter that tints guild-claimed chunks, using per-viewer colors from
// GuildClaimCacheClient. the World Map has its own highlight system separate from the minimap's, so this
// mirrors GuildChunkHighlighter against xaero.map.*. references World Map types directly, so only loaded
// when Xaero's World Map is installed.
public class GuildWorldMapHighlighter extends ChunkHighlighter
{
    public GuildWorldMapHighlighter()
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
        return GuildClaimCacheClient.hasAnyIn(dim(key));
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        return GuildClaimCacheClient.at(dim(key), chunkX, chunkZ) != null;
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> key, int regionX, int regionZ)
    {
        // Any claim/relation change bumps the cache version, forcing the region to re-render.
        return GuildClaimCacheClient.version();
    }

    @Override
    protected int[] getColors(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        GuildClaimCacheClient.Claim c = GuildClaimCacheClient.at(dim(key), chunkX, chunkZ);
        if (c == null)
            return null;
        for (int a = 0; a < 16; a++)
            for (int i = 0; i < 16; i++)
                setResult(i, a, c.color);
        return resultStore;
    }

    private Component tooltip(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        GuildClaimCacheClient.Claim c = GuildClaimCacheClient.at(dim(key), chunkX, chunkZ);
        return (c != null && c.guild != null && !c.guild.isEmpty()) ? Component.literal("Guild: " + c.guild) : null;
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        return tooltip(key, chunkX, chunkZ);
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> key, int chunkX, int chunkZ)
    {
        return tooltip(key, chunkX, chunkZ);
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> key, int chunkX, int chunkZ,
            int something)
    {
        Component t = tooltip(key, chunkX, chunkZ);
        if (t != null)
            list.add(t);
    }
}
