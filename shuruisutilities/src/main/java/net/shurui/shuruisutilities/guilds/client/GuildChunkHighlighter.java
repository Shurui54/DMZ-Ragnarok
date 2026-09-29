package net.shurui.shuruisutilities.guilds.client;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/**
 * Xaero highlighter that tints guild-claimed chunks on the minimap and world map, using the colors the
 * server pre-resolved per viewer in {@link GuildClaimCacheClient}. References Xaero types directly, so it
 * is only loaded when Xaero is present (see {@link XaeroGuildHighlighter}). Xaero asks for a 16x16 grid of
 * ARGB colors per chunk (the inherited {@code resultStore}); we fill it uniformly with the guild color.
 */
public class GuildChunkHighlighter extends ChunkHighlighter
{
    public GuildChunkHighlighter()
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

    @Override
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> key, int chunkX, int chunkZ,
            int something)
    {
        GuildClaimCacheClient.Claim c = GuildClaimCacheClient.at(dim(key), chunkX, chunkZ);
        if (c != null && c.guild != null && !c.guild.isEmpty())
            compiler.addLine("Guild: " + c.guild);
    }
}
