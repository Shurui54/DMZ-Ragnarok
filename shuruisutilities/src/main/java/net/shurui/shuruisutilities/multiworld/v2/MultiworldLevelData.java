package net.shurui.shuruisutilities.multiworld.v2;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/**
 * A {@link DerivedLevelData} carrying its OWN {@link Difficulty} for a multiworld dimension instead of
 * delegating difficulty to the shared server WorldData like vanilla derived data.
 *
 * <p>Everything else (spawn, time, weather, world border, game type, gamerules) is inherited unchanged and
 * still delegates to the shared world data; only difficulty + its lock flag are per-dimension. getGameRules()
 * is NOT overridden, so gamerules like doMobSpawning stay server-wide; only the level's difficulty is
 * independent, which is what Monster.checkMonsterSpawnRules keys off (requires difficulty != PEACEFUL).
 *
 * <p>The interfaces declare only difficulty GETTERS; there are no interface setters (those live on WorldData).
 * setDifficulty/setDifficultyLocked below are our own live-mutation hooks, called by
 * {@link Multiworld#setDifficulty(Difficulty)} so a runtime change takes effect without a restart.
 */
public class MultiworldLevelData extends DerivedLevelData
{
    private Difficulty difficulty;
    private boolean difficultyLocked;

    public MultiworldLevelData(WorldData worldData, ServerLevelData wrapped, Difficulty difficulty)
    {
        super(worldData, wrapped);
        // never coerce null to PEACEFUL: that was the original bug that blocked all MONSTER-category spawns.
        // Callers pass an already-resolved difficulty, but if null slips through we inherit the global, not PEACEFUL.
        this.difficulty = difficulty != null ? difficulty : resolveGlobalDifficulty();
        this.difficultyLocked = false;
    }

    @Override
    public Difficulty getDifficulty()
    {
        return this.difficulty;
    }

    @Override
    public boolean isDifficultyLocked()
    {
        return this.difficultyLocked;
    }

    // live-set the per-dimension difficulty (from Multiworld.setDifficulty)
    public void setDifficulty(Difficulty difficulty)
    {
        // same rule as the ctor: null = inherit the server global, never PEACEFUL
        this.difficulty = difficulty != null ? difficulty : resolveGlobalDifficulty();
    }

    // server global difficulty fallback for null; NORMAL (never PEACEFUL) if no server
    private static Difficulty resolveGlobalDifficulty()
    {
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null)
            return server.getWorldData().getDifficulty();
        return Difficulty.NORMAL;
    }

    public void setDifficultyLocked(boolean difficultyLocked)
    {
        this.difficultyLocked = difficultyLocked;
    }
}
