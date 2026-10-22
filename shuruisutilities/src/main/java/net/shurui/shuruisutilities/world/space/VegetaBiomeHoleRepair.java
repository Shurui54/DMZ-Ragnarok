package net.shurui.shuruisutilities.world.space;

import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Planet Vegeta's capital ships as pre-generated region files that store no section above the build where a column is
 * all air. Minecraft fills every missing section with the PLAINS biome when it loads such a chunk, so the open air
 * over the city's streets read as minecraft:plains. Plains is in DragonMineZ's has_robots and has_sabertooth tags, so
 * Red Ribbon soldiers, robots, sabertooths and bandits spawned in the city, and nowhere else on Vegeta.
 *
 * <p>On every Vegeta chunk load this rewrites any plains cell to the nearest real biome below it in the same column
 * (the city biome, normally) and marks the chunk dirty, so the fix also heals worlds already seeded. Vegeta never has
 * a genuine plains cell, so nothing legitimate is touched. A chunk with no plains cell costs one read per biome cell.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class VegetaBiomeHoleRepair
{
    private VegetaBiomeHoleRepair()
    {
    }

    // the live dimension and the pre-migration one, so an unmigrated world is healed too.
    private static final Set<ResourceLocation> VEGETA_DIMENSIONS = Set.of(
            SpaceKeys.PLANET_VEGETA_ID, new ResourceLocation("shuruisutilities", "planet_vegeta"));
    private static final ResourceKey<Biome> FALLBACK = ResourceKey.create(Registries.BIOME,
            new ResourceLocation("dmz_ragnarok", "vegeta_city"));

    private static boolean warned = false;

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event)
    {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)
                || !VEGETA_DIMENSIONS.contains(level.dimension().location()))
        {
            return;
        }
        try
        {
            repair(level, chunk);
        }
        catch (Throwable t)
        {
            if (!warned)
            {
                warned = true;
                LoggingHandler.sulog.warn("[Vegeta] Could not repair plains biome cells in chunk {}.", chunk.getPos(), t);
            }
        }
    }

    private static void repair(ServerLevel level, LevelChunk chunk)
    {
        LevelChunkSection[] sections = chunk.getSections();
        // per 4x4 column of biome cells, the last real (non-plains) biome seen walking up from the bottom.
        @SuppressWarnings("unchecked")
        Holder<Biome>[] below = new Holder[16];
        boolean changed = false;
        int minQuartX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int minQuartZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        for (int s = 0; s < sections.length; s++)
        {
            LevelChunkSection section = sections[s];
            boolean hasPlains = false;
            // read the whole section first; a column's below-biome only advances on real cells.
            @SuppressWarnings("unchecked")
            Holder<Biome>[] cells = new Holder[64];
            for (int y = 0; y < 4; y++)
            {
                for (int z = 0; z < 4; z++)
                {
                    for (int x = 0; x < 4; x++)
                    {
                        Holder<Biome> b = section.getBiomes().get(x, y, z);
                        cells[(y * 4 + z) * 4 + x] = b;
                        if (b.is(Biomes.PLAINS))
                        {
                            hasPlains = true;
                        }
                        else
                        {
                            below[z * 4 + x] = b;
                        }
                    }
                }
            }
            if (!hasPlains)
            {
                continue;
            }
            Holder<Biome> fallback = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(FALLBACK);
            Holder<Biome>[] column = below.clone();
            int minQuartY = QuartPos.fromSection(chunk.getSectionYFromSectionIndex(s));
            section.fillBiomesFromNoise((qx, qy, qz, sampler) ->
            {
                int x = qx - minQuartX;
                int y = qy - minQuartY;
                int z = qz - minQuartZ;
                Holder<Biome> b = cells[(y * 4 + z) * 4 + x];
                if (!b.is(Biomes.PLAINS))
                {
                    return b;
                }
                Holder<Biome> under = column[z * 4 + x];
                return under != null ? under : fallback;
            }, null, minQuartX, minQuartY, minQuartZ);
            changed = true;
        }
        if (changed)
        {
            chunk.setUnsaved(true);
        }
    }
}
