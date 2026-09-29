package net.shurui.shuruisutilities.compat.dmz;

import java.util.List;
import java.util.Map;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.corrupted.ShadowDragonStorage;
import net.shurui.shuruisutilities.dragonballbag.DragonBallTotem;
import net.shurui.shuruisutilities.grave.GraveData;
import net.shurui.shuruisutilities.grave.GraveStorage;
import net.shurui.shuruisutilities.guilds.model.ChunkKey;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Answers one question for the guild claim system: does a chunk hold a dragon ball, so a claim must not be allowed to
 * cover it? A scattered set is a shared world event every player must be able to reach; letting a guild wall a chunk
 * that a ball landed in would lock the set away behind the claim's build rules, which is exactly what the ball
 * exemptions everywhere else exist to prevent. This is the read side of that rule (claim creation / expansion); the
 * write side (no PLANTING a ball inside an existing claim) lives in {@code GuildProtectionHandler.onPlace}.
 *
 * <h2>Authoritative positions, never a chunk scan</h2>
 *
 * <p>The claim area can be in UNLOADED chunks, and loading chunks to scan them on the server thread is not allowed
 * (it can freeze the shard, see the barrier-box and gzip-on-thread incidents). So this reads the SAME saved position
 * records the radar is built from, all of which are in-memory {@code SavedData} that cover loaded and unloaded chunks
 * alike:
 * <ul>
 *   <li><b>DMZ's own sets</b> (earth, namek, and the added blackstar / super / cerulean, plus any future data-driven
 *       set): the RAW, unfuzzed active and pending positions from {@link DragonBallSavedData#getActiveBalls(String)}
 *       and {@link DragonBallSavedData#getPendingBalls(String)}. These are what DMZ records on every ball place /
 *       scatter, so they cover a ball the world spawner dropped and a ball a player set down alike. The radar's own
 *       {@code getAllKnownPositionsForRadar} is deliberately NOT used: SU fuzzes its return for the normal sets, and
 *       a claim test needs the exact chunk.</li>
 *   <li><b>SU's corrupted set</b>: those are SU blocks outside DMZ's ball system, tracked in
 *       {@link ShadowDragonStorage#getPlaced()} (they scatter around the overworld spawn), so they are checked there.</li>
 *   <li><b>Ball-holding grave totems</b>: a set a player died or logged out with sits in a totem the radar leads
 *       hunters to ({@link DragonBallTotem#holdsDragonBall(Container)}), so a claim must not cover one of those
 *       either. Enumerated from {@link GraveStorage} for the tested level.</li>
 * </ul>
 *
 * <p>Cross-shard balls are intentionally not consulted: a claim is always in a dimension THIS server hosts, so a ball
 * recorded on another shard cannot be inside the claimed chunk.
 *
 * <p>Fails OPEN: any read error logs once and answers false (claim allowed). Blocking every claim on a transient DMZ
 * read error would be worse than letting one slip, and Rule 1 (no planting a ball in a claim) still holds the future.
 * DragonMineZ is a mandatory dependency, so the direct references here are always resolvable.
 */
public final class DragonBallClaimGuard
{
    private DragonBallClaimGuard()
    {
    }

    private static volatile boolean loggedFailure = false;

    /**
     * True when the chunk named by {@code chunkKey} ({@code "<dimension>;<chunkX>;<chunkZ>"}) holds any dragon ball
     * of any set: a placed / spawned ball block, a corrupted swap block, or a ball-holding grave totem. Never throws.
     */
    public static boolean chunkHoldsDragonBall(MinecraftServer server, String chunkKey)
    {
        if (server == null || chunkKey == null)
        {
            return false;
        }
        try
        {
            ResourceKey<Level> dimKey = ChunkKey.dimensionKeyOf(chunkKey);
            int cx = ChunkKey.chunkXOf(chunkKey);
            int cz = ChunkKey.chunkZOf(chunkKey);
            if (dimKey == null || cx == Integer.MIN_VALUE || cz == Integer.MIN_VALUE)
            {
                return false;
            }
            ServerLevel level = server.getLevel(dimKey);
            if (level == null)
            {
                return false; // the dimension is not hosted here, so nothing of ours is in it
            }

            // 1. DMZ registered sets: raw active + pending positions for this level.
            DragonBallSavedData data = DragonBallSavedData.get(level);
            for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets())
            {
                if (set == null || set.getId() == null)
                {
                    continue;
                }
                String id = set.getId();
                if (anyInChunk(data.getActiveBalls(id), cx, cz) || anyInChunk(data.getPendingBalls(id), cx, cz))
                {
                    return true;
                }
            }

            // 2. SU corrupted swap blocks scatter around the overworld spawn only.
            if (level.dimension().equals(Level.OVERWORLD))
            {
                for (BlockPos pos : ShadowDragonStorage.get(server).getPlaced())
                {
                    if (inChunk(pos, cx, cz))
                    {
                        return true;
                    }
                }
            }

            // 3. Ball-holding grave totems in this level.
            for (GraveData grave : GraveStorage.get(level).all())
            {
                if (inChunk(grave.pos(), cx, cz) && DragonBallTotem.holdsDragonBall(grave.container()))
                {
                    return true;
                }
            }
            return false;
        }
        catch (Throwable t)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                LoggingHandler.sulog.warn("[dragonball] Could not check a chunk for dragon balls during a guild "
                        + "claim; claims will not be ball-guarded. Cause: {}", t.toString());
            }
            return false;
        }
    }

    // True when any position in a per-star position map lies in the given chunk.
    private static boolean anyInChunk(Map<Integer, List<BlockPos>> byStar, int cx, int cz)
    {
        if (byStar == null)
        {
            return false;
        }
        for (List<BlockPos> positions : byStar.values())
        {
            if (positions == null)
            {
                continue;
            }
            for (BlockPos pos : positions)
            {
                if (inChunk(pos, cx, cz))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inChunk(BlockPos pos, int cx, int cz)
    {
        return pos != null && (pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz;
    }
}
