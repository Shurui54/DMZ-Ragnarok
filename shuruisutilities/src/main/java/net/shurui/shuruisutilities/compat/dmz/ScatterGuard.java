package net.shurui.shuruisutilities.compat.dmz;

import java.util.Random;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import net.shurui.shuruisutilities.api.key.GuildHooks;
import net.shurui.shuruisutilities.guilds.model.ChunkKey;
import net.shurui.shuruisutilities.regions.Region;
import net.shurui.shuruisutilities.regions.RegionManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.worldborder.BorderClamp;

/**
 * Answers whether a scattered dragon ball may land on a given column: never inside a guild claim, never inside a
 * region (which includes every {@code /serverclaim}). A scattered set is a shared world event, and a ball dropped on
 * claimed or protected ground is fenced off from every other hunter by that ground's build rules.
 *
 * <p>Pure in-memory lookups (the guild claim index and the region list), so it loads no chunk and is safe on the
 * scatter path. Keyless there are no guild claims ({@link GuildHooks} answers false) and regions still apply.
 * Never throws: a failed lookup answers "allowed", because refusing every spot would stall the scatter.
 */
public final class ScatterGuard
{
    private ScatterGuard() {}

    /** How many fresh picks the scatter may try before it settles for the last one. */
    public static final int MAX_TRIES = 32;

    /** How far inside the border a scattered ball must land, in blocks. */
    public static final int BORDER_MARGIN = 16;

    private static final Random RANDOM = new Random();

    /**
     * The final scatter target for a ball of {@code setId} in {@code level}, from DMZ's own pick {@code (x, z)}: kept
     * inside the dimension's effective border (16 block margin), off guild claims and regions, and for the Namek set
     * moved onto namekow anywhere inside that border ({@link NamekBallHome}). Never loads a chunk, never throws (on
     * any failure DMZ's own pick is returned). Used by {@code MixinDmzDragonBallScatter} and the wrong-dimension sweep.
     */
    public static BlockPos pickTarget(ServerLevel level, String setId, int x, int y, int z)
    {
        try
        {
            if (NamekBallHome.isRelocatedScatter(level, setId))
            {
                double[] box = BorderClamp.innerBox(level, BORDER_MARGIN);
                if (box != null)
                {
                    int bx = 0;
                    int bz = 0;
                    for (int i = 0; i < MAX_TRIES; i++)
                    {
                        bx = (int) Math.floor(box[0] + RANDOM.nextDouble() * (box[1] - box[0]));
                        bz = (int) Math.floor(box[2] + RANDOM.nextDouble() * (box[3] - box[2]));
                        if (!blocked(level, bx, bz))
                            break;
                    }
                    return new BlockPos(bx, y, bz);
                }
            }
            int[] clamped = BorderClamp.clampInside(level, x, z, BORDER_MARGIN);
            if (!blocked(level, clamped[0], clamped[1]))
                return new BlockPos(clamped[0], y, clamped[1]);
            // The pick landed on a guild claim or a region (serverclaims included), where the ground's build rules
            // would fence the ball off from everyone else. Re-pick in DMZ's own square (spawn +/- range), through the
            // same border clamp, a bounded number of times; settle for the last pick if the whole area is claimed.
            DragonBallSetDefinition def = DragonBallDefinitions.getBallSet(setId);
            int range = def == null ? 0 : Math.max(1, def.getSpawnRange());
            BlockPos spawn = level.getSharedSpawnPos();
            for (int i = 0; i < MAX_TRIES && def != null; i++)
            {
                int rx = spawn.getX() + RANDOM.nextInt(range * 2) - range;
                int rz = spawn.getZ() + RANDOM.nextInt(range * 2) - range;
                clamped = BorderClamp.clampInside(level, rx, rz, BORDER_MARGIN);
                if (!blocked(level, clamped[0], clamped[1]))
                    return new BlockPos(clamped[0], y, clamped[1]);
            }
            LoggingHandler.sulog.warn("[dragonball] could not find unclaimed ground for a {} ball after {} tries; "
                    + "placing it at {} {}", setId, MAX_TRIES, clamped[0], clamped[1]);
            return new BlockPos(clamped[0], y, clamped[1]);
        }
        catch (Throwable t)
        {
            return new BlockPos(x, y, z);
        }
    }

    /** A fresh scatter target for {@code setId} in {@code level}, picked the way DMZ does (spawn +/- range). */
    public static BlockPos freshTarget(ServerLevel level, String setId)
    {
        DragonBallSetDefinition def = DragonBallDefinitions.getBallSet(setId);
        int range = def == null ? 1 : Math.max(1, def.getSpawnRange());
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + RANDOM.nextInt(range * 2) - range;
        int z = spawn.getZ() + RANDOM.nextInt(range * 2) - range;
        return pickTarget(level, setId, x, 0, z);
    }

    /** True when a ball must NOT be scattered onto column {@code (x, z)} of {@code level}. */
    public static boolean blocked(ServerLevel level, int x, int z)
    {
        if (level == null)
            return false;
        String dim = level.dimension().location().toString();
        try
        {
            if (GuildHooks.get().isClaimed(ChunkKey.of(dim, x >> 4, z >> 4)))
                return true;
        }
        catch (Throwable ignored)
        {
        }
        try
        {
            RegionManager rm = RegionManager.instance();
            if (rm.isEmpty())
                return false;
            for (Region r : rm.all())
                if (r != null && coversColumn(r, dim, x, z))
                    return true;
        }
        catch (Throwable ignored)
        {
        }
        return false;
    }

    // Whether the region covers this column at ANY height (the scatter only picks X/Z; the ground is found later).
    private static boolean coversColumn(Region r, String dim, int x, int z)
    {
        if (!dim.equals(r.dim))
            return false;
        if (x < r.minX || x > r.maxX || z < r.minZ || z > r.maxZ)
            return false;
        if (r.areas == null || r.areas.isEmpty())
            return true;
        for (Region.Box b : r.areas)
            if (b != null && x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ)
                return true;
        return false;
    }
}
