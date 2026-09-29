package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

import net.shurui.shuruisutilities.grave.GraveData;
import net.shurui.shuruisutilities.grave.GraveStorage;

/**
 * Feeds the dragon radar the grave totems holding balls, so a ball a player died or logged out with is still
 * findable instead of silently leaving the world's radar picture.
 *
 * <h2>Why this is not done at the per-set seam</h2>
 *
 * <p>The obvious place was {@code DragonBallSavedData.getAllKnownPositionsForRadar(setId)}, where SU already fuzzes
 * positions, and it is per set and per level for free. It cannot work. DMZ reaches that method only through the
 * loop in {@code buildRadarPacket}:
 *
 * <pre>
 * for (ResourceLocation dimension : definition.getValidDimensions())
 *     positions.addAll(DragonBallSavedData.get(server.getLevel(dimension)).getAllKnownPositionsForRadar(id));
 * </pre>
 *
 * <p>so a set is only ever asked about the dimensions IT scatters in. Super scatters on planet surfaces only, so a
 * super totem in the overworld is never asked about at all; earth includes the overworld, which is why an earth
 * totem appeared there and a super one did not. Widening the SET's dimensions is not the fix either: that list also
 * drives where DMZ SCATTERS the balls, so adding the overworld to super would seed a second set on Earth.
 *
 * <p>We therefore add totems once, after DMZ has assembled the whole packet, where every level is reachable and the
 * set's own dimension list is irrelevant. DMZ's stored ball data is never written to, so scatter, pickup and the
 * summon scan cannot be affected by any of this.
 *
 * <h2>Fuzzing</h2>
 *
 * <p>Totem positions go through {@link RadarFuzz} exactly like ordinary balls, so the normal sets' totems lead to an
 * approximate area and Super and Black Star stay exact. Without this a totem would be the one position on the whole
 * radar a modified client could read precisely.
 */
public final class RadarTotems
{
    private RadarTotems()
    {
    }

    /** The positions of every ball-holding totem on the server, grouped by ball set id. */
    public static Map<String, List<BlockPos>> collect(MinecraftServer server)
    {
        Map<String, List<BlockPos>> bySet = new HashMap<>();
        if (server == null)
        {
            return bySet;
        }
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
            {
                continue;
            }
            for (GraveData data : GraveStorage.get(level).all())
            {
                Container container = data.container();
                // one grave contributes its position at most once per set, however many balls of that set it holds
                List<String> seen = new ArrayList<>(2);
                for (int slot = 0; slot < container.getContainerSize(); ++slot)
                {
                    String setId = DragonBallSets.setIdOf(container.getItem(slot));
                    if (setId == null || seen.contains(setId))
                    {
                        continue;
                    }
                    seen.add(setId);
                    bySet.computeIfAbsent(setId, key -> new ArrayList<>()).add(data.pos());
                }
            }
        }
        return bySet;
    }

    /**
     * Add every ball-holding totem to the radar collections DMZ just filled.
     *
     * @param positionsBySet the packet's per-set map, which custom radars read
     * @param earthPositions the packet's dedicated earth list, which DMZ's own hardcoded earth radar reads
     * @param namekPositions the same for namek
     */
    public static void addTo(MinecraftServer server, Map<String, List<BlockPos>> positionsBySet,
            List<BlockPos> earthPositions, List<BlockPos> namekPositions)
    {
        Map<String, List<BlockPos>> totems = collect(server);
        for (Map.Entry<String, List<BlockPos>> entry : totems.entrySet())
        {
            String setId = entry.getKey();
            List<BlockPos> positions = RadarFuzz.fuzzForRadar(setId, entry.getValue());

            if (positionsBySet != null)
            {
                // buildRadarPacket seeds an entry for every registered set, so this is normally an add to an
                // existing mutable list; computeIfAbsent covers a set that somehow had none.
                positionsBySet.computeIfAbsent(setId, key -> new ArrayList<>()).addAll(positions);
            }
            // the dedicated lists are COPIES taken before we ran, so they need the same additions or DMZ's own
            // earth/namek radars would disagree with every custom one.
            if (earthPositions != null && "earth".equals(setId))
            {
                earthPositions.addAll(positions);
            }
            if (namekPositions != null && "namek".equals(setId))
            {
                namekPositions.addAll(positions);
            }
        }
    }
}
