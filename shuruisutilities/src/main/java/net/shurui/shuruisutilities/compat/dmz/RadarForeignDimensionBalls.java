package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.server.world.data.DragonBallSavedData;

/**
 * Feeds the radar the loose balls that sit in a dimension OUTSIDE the set's own scatter list, so every radar can
 * point at every ball wherever it ended up, not just in the set's home dimension.
 *
 * <h2>Why this is needed and where it plugs in</h2>
 *
 * <p>{@code DragonBallsHandler.buildRadarPacket} gathers each set's positions by looping that SET's own valid
 * dimensions and calling {@code DragonBallSavedData.get(level).getAllKnownPositionsForRadar(setId)}. A set is
 * therefore only ever asked about the dimensions it scatters in, so a ball registered somewhere else (a player
 * placed a ball block in a dimension the set does not scatter in, or a ball was restored from a grave into an
 * unusual dimension) is invisible to the radar even though DMZ's own {@code onBlockPlace} recorded it in that level's
 * {@code DragonBallSavedData}. This runs once, at the same {@code buildRadarPacket} RETURN seam as the totem and
 * cross-shard additions, where every hosted level is reachable and the set's own dimension list no longer limits the
 * question.
 *
 * <h2>No double counting, no overlap</h2>
 *
 * <p>For each set, only the dimensions NOT already in that set's valid-dimension list are swept, so DMZ's own gather
 * of the home dimensions is never repeated. Only LOCALLY hosted levels are read ({@code server.getAllLevels()}), so
 * this never overlaps {@link CrossShardRadar}, which adds only dimensions with no local level. Totems are grave
 * containers, a separate source, so {@link RadarTotems} never overlaps this either.
 *
 * <h2>Fuzzing</h2>
 *
 * <p>{@code getAllKnownPositionsForRadar} is rewritten by {@code MixinDmzRadarFuzz} to fuzz the normal sets and leave
 * Super / Black Star exact, so the positions returned here are already fuzzed exactly as DMZ's own home-dimension
 * gather is. Nothing here fuzzes again.
 *
 * <p>Never mutates DMZ's stored ball data; it only reads the radar-facing view and appends to the outgoing packet's
 * collections.
 */
public final class RadarForeignDimensionBalls
{
    private RadarForeignDimensionBalls()
    {
    }

    /**
     * Add every locally hosted, non-home-dimension loose ball to the radar collections DMZ just filled.
     *
     * @param positionsBySet the packet's per-set map, which the custom radars read
     * @param earthPositions the packet's dedicated earth list, which DMZ's own hardcoded earth radar reads
     * @param namekPositions the same for namek
     */
    public static void addTo(MinecraftServer server, Map<String, List<BlockPos>> positionsBySet,
            List<BlockPos> earthPositions, List<BlockPos> namekPositions)
    {
        if (server == null || positionsBySet == null)
        {
            return;
        }
        for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets())
        {
            String setId = set.getId();
            Set<ResourceLocation> home = set.getValidDimensions();
            for (ServerLevel level : server.getAllLevels())
            {
                if (level == null)
                {
                    continue;
                }
                ResourceLocation dim = level.dimension().location();
                if (home.contains(dim))
                {
                    continue; // DMZ's buildRadarPacket already gathered this dimension for this set
                }
                List<BlockPos> known = DragonBallSavedData.get(level).getAllKnownPositionsForRadar(setId);
                if (known == null || known.isEmpty())
                {
                    continue;
                }
                positionsBySet.computeIfAbsent(setId, key -> new ArrayList<>()).addAll(known);
                // the dedicated lists are COPIES taken before we ran, so they need the same additions or DMZ's own
                // earth / namek radars would disagree with every custom one, exactly as RadarTotems handles them.
                if (earthPositions != null && "earth".equals(setId))
                {
                    earthPositions.addAll(known);
                }
                if (namekPositions != null && "namek".equals(setId))
                {
                    namekPositions.addAll(known);
                }
            }
        }
    }
}
