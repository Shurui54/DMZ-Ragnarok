package net.shurui.shuruisutilities.util;

import java.util.Map;
import java.util.concurrent.Executor;

import com.google.common.collect.ImmutableList;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Resolve a datapack dimension to a live {@link ServerLevel}, CREATING it if the world never had one.
 *
 * <h2>Why this is needed at all</h2>
 * A dimension declared in {@code data/<ns>/dimension/*.json} is not guaranteed to exist as a ServerLevel.
 * {@code MinecraftServer} only creates levels for the {@code LEVEL_STEM} entries the world knew about when it was
 * created, so a dimension added by a later update is present in the registry and yet has no level: every
 * {@code server.getLevel(key)} returns null, forever, on that world. Every feature that then teleports into it
 * fails, and the usual shape of that failure is a null check that returns quietly, so it reads to a player as "the
 * game just does not let me go there" with nothing in the log.
 *
 * <p>That is exactly what stopped players landing on generated planets: the surface dimension shipped after their
 * world was made, so it did not exist and every landing was refused in silence.
 *
 * <p>This performs the same steps {@code MinecraftServer.createLevels} does for a non-overworld level, which is
 * the approach the dungeon addon has used in production for its themed floor dimensions since they were added.
 */
public final class DynamicLevels
{

    private DynamicLevels()
    {
    }

    /**
     * The level for {@code levelKey}, created and registered if it does not exist yet.
     *
     * <p>Synchronized because two features can ask for the same missing dimension in the same tick and both must
     * get the SAME level: creating two would leave one of them writing to a level the server does not know about.
     *
     * @return the level, or null when the dimension has no {@link LevelStem} (its JSON is missing or failed to
     *         load) or the creation itself failed, both of which are logged
     */
    public static synchronized ServerLevel getOrCreate(MinecraftServer server, ResourceKey<Level> levelKey)
    {
        if (server == null || levelKey == null)
        {
            return null;
        }
        ServerLevel existing = server.getLevel(levelKey);
        if (existing != null)
        {
            return existing;
        }
        try
        {
            return create(server, levelKey);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[Dimensions] Failed to create dimension " + levelKey.location()
                    + " as a ServerLevel. Anything that needs it will be unavailable this session.", t);
            return null;
        }
    }

    /**
     * Whether this dimension is INSTALLED on the server, meaning it either already has a live {@link ServerLevel} or
     * has a {@link LevelStem} in the datapack registry that {@link #getOrCreate} can build one from. This is the
     * question "is this a real place on this server" separated from "has a level been instantiated for it yet", which
     * for a dimension added after the world was created is NOT the same thing (the level is created lazily). Callers
     * that only want to know whether a dimension can ever be reached (planet eligibility, say) must use this rather
     * than {@code server.getLevel(key) != null}, which answers no forever for a not-yet-instantiated dimension and so
     * wrongly hides a planet whose world simply predates it.
     */
    public static boolean isInstalled(MinecraftServer server, ResourceKey<Level> levelKey)
    {
        if (server == null || levelKey == null)
        {
            return false;
        }
        if (server.getLevel(levelKey) != null)
        {
            return true;
        }
        try
        {
            Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
            return stems.containsKey(ResourceKey.create(Registries.LEVEL_STEM, levelKey.location()));
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static ServerLevel create(MinecraftServer server, ResourceKey<Level> levelKey)
    {
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        LevelStem stem = stems.get(ResourceKey.create(Registries.LEVEL_STEM, levelKey.location()));
        if (stem == null)
        {
            // No datapack definition: a missing or broken dimension JSON, or one of the ids it references failed to
            // load. Nothing can be built from that, and guessing would be worse than saying so.
            LoggingHandler.sulog.error("[Dimensions] Dimension {} has no LevelStem in the datapack, so it cannot be"
                    + " created. Check its dimension / dimension_type / worldgen JSON.", levelKey.location());
            return null;
        }

        // The three MinecraftServer internals ServerLevel's constructor needs. ObfuscationReflectionHelper takes SRG
        // names and resolves them in BOTH dev (official) and production (srg), so this needs no access transformer
        // and no name-based reflection that would break on reobf.
        Executor executor = ObfuscationReflectionHelper.getPrivateValue(
                MinecraftServer.class, server, "f_129738_"); // executor
        LevelStorageSource.LevelStorageAccess storage = ObfuscationReflectionHelper.getPrivateValue(
                MinecraftServer.class, server, "f_129744_"); // storageSource
        ChunkProgressListenerFactory listenerFactory = ObfuscationReflectionHelper.getPrivateValue(
                MinecraftServer.class, server, "f_129756_"); // progressListenerFactory

        WorldData worldData = server.getWorldData();
        ServerLevelData overworldData = worldData.overworldData();
        DerivedLevelData derivedData = new DerivedLevelData(worldData, overworldData);
        ChunkProgressListener listener = listenerFactory.create(11);
        long biomeZoomSeed = BiomeManager.obfuscateSeed(worldData.worldGenOptions().seed());

        ServerLevel level = new ServerLevel(
                server,
                executor,
                storage,
                derivedData,
                levelKey,
                stem,
                listener,
                false,               // isDebug
                biomeZoomSeed,
                ImmutableList.of(),  // no custom spawners
                false,               // tickTime
                (RandomSequences) null);

        // Mirror the wiring MinecraftServer.createLevels does for a non-overworld level.
        server.overworld().getWorldBorder().addListener(
                new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder()));

        Map<ResourceKey<Level>, ServerLevel> levelMap = server.forgeGetWorldMap();
        levelMap.put(levelKey, level);
        server.markWorldsDirty();
        MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(level));

        LoggingHandler.sulog.info("[Dimensions] Created {} as a ServerLevel at runtime (this world predates it).",
                levelKey.location());
        return level;
    }
}
