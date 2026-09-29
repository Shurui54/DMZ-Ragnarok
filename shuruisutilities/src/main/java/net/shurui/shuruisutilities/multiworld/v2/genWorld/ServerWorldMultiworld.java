package net.shurui.shuruisutilities.multiworld.v2.genWorld;

import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Marker {@link ServerLevel} subclass for SU multiworlds, so MultiworldEngine.isMultiWorld can identify SU
 * dimensions by type. On 1.20.1 no behaviour override is needed (portals go through SU's TeleportHelper and
 * ServerLevel builds its own PortalForcer), so this is a thin pass-through to the vanilla ctor whose signature
 * merged DimensionType+ChunkGenerator into LevelStem and added RandomSequences.
 */
public class ServerWorldMultiworld extends ServerLevel
{
    // When >= 0, the gen seed for THIS dimension instead of the shared server seed. Vanilla
    // ServerLevel.getSeed() is hard-coded to worldGenOptions().seed(), and ChunkMap builds its RandomState /
    // structure state from getSeed(), so a per-world gen seed (a "terrain" import that read the source seed) is
    // only honoured by overriding getSeed() here.
    //
    // ChunkMap (which reads getSeed()) is built inside the super(...) ctor, BEFORE any instance field of this
    // subclass can be assigned, so the seed can't live in a normal field and be read back in time. Instead the
    // manager stashes it here right before construction; getSeed() snapshots it (also latched into seedOverride
    // once the ctor finishes, so later calls don't touch the ThreadLocal). -1 for normal worlds -> server seed.
    private static final ThreadLocal<Long> SEED_OVERRIDE = new ThreadLocal<>();

    private long seedOverride = -1L;

    // set the gen seed for the very next ServerWorldMultiworld built on this thread. >= 0 overrides; -1 (or no
    // call) uses the server seed. Manager calls this right before instantiation, clears right after.
    public static void setNextSeedOverride(long seed)
    {
        SEED_OVERRIDE.set(seed);
    }

    public static void clearNextSeedOverride()
    {
        SEED_OVERRIDE.remove();
    }

    public ServerWorldMultiworld(MinecraftServer server, Executor executor,
            LevelStorageSource.LevelStorageAccess levelSave, ServerLevelData levelData,
            ResourceKey<Level> worldKey, LevelStem levelStem, ChunkProgressListener progressListener,
            boolean isDebug, long biomeZoomSeed, List<CustomSpawner> customSpawners, boolean tickTime,
            @Nullable RandomSequences randomSequences)
    {
        super(server, executor, levelSave, levelData, worldKey, levelStem, progressListener, isDebug,
                biomeZoomSeed, customSpawners, tickTime, randomSequences);
        // super() has run (already queried getSeed() via ChunkMap); latch the value so later getSeed() calls
        // don't touch the ThreadLocal
        Long override = SEED_OVERRIDE.get();
        this.seedOverride = override != null ? override : -1L;
    }

    @Override
    public long getSeed()
    {
        // during super() construction seedOverride is still its default; consult the ThreadLocal in that window
        if (seedOverride >= 0)
            return seedOverride;
        Long override = SEED_OVERRIDE.get();
        if (override != null && override >= 0)
            return override;
        return super.getSeed();
    }
}
