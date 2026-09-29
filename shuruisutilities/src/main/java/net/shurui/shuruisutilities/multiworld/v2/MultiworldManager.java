package net.shurui.shuruisutilities.multiworld.v2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import org.apache.commons.io.FileUtils;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.NamedWorldHandler;
import net.shurui.shuruisutilities.api.permissions.SUPermissions;
import net.shurui.shuruisutilities.api.permissions.WorldZone;
import net.shurui.shuruisutilities.api.permissions.Zone;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.multiworld.v2.genWorld.ServerWorldMultiworld;
import net.shurui.shuruisutilities.multiworld.v2.provider.ProviderHelper;
import net.shurui.shuruisutilities.multiworld.v2.utils.MultiworldException;
import net.shurui.shuruisutilities.multiworld.v2.utils.MultiworldException.Type;
import net.shurui.shuruisutilities.util.events.ServerEventHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.Lifecycle;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Runtime dynamic-dimension manager, 1.20.1 port.
 *
 * <p>1.16 made dimensions by mutating {@code DimensionGeneratorSettings} + calling the old {@code ServerWorld}
 * ctor. On 1.20.1 DimensionType+ChunkGenerator became a {@link LevelStem}, the level-stem registry is frozen
 * after load, and the {@link ServerLevel} ctor gained a RandomSequences param. So: build a LevelStem from the
 * registries ({@link ProviderHelper}), briefly unfreeze LEVEL_STEM to add it, build a {@link
 * ServerWorldMultiworld} and shove it into the live world map via {@code forgeGetWorldMap()} +
 * {@code markWorldsDirty()} so it ticks.</p>
 *
 * <p>Level stems are ephemeral: re-registered every boot from SU's per-world JSON, so deletion just drops the
 * JSON (no surgery on the frozen registry's index-linked maps). Per-world custom seeds aren't supported on
 * 1.20.1 (terrain seed derives from the single server world seed), so all multiworlds share the server seed.</p>
 */
public class MultiworldManager extends ServerEventHandler implements NamedWorldHandler
{
    // MinecraftServer private/protected fields needed to build a ServerLevel (SRG names, remapped by ORH)
    private static final String SRG_EXECUTOR = "f_129738_";
    private static final String SRG_STORAGE_SOURCE = "f_129744_";
    private static final String SRG_PROGRESS_LISTENER_FACTORY = "f_129756_";

    public static final String PERM_PROP_MULTIWORLD = SUPermissions.SU_INTERNAL + ".multiworld";

    protected Map<String, Multiworld> worlds = new HashMap<>();

    // world folders marked for deletion
    protected ArrayList<File> worldsFoldersToDelete = new ArrayList<>();

    // worlds marked for removal
    protected ArrayList<ServerLevel> worldsToUnloadAndRemove = new ArrayList<>();

    private NamedWorldHandler parentNamedWorldHandler;

    protected ProviderHelper providerHandler = new ProviderHelper();

    public MultiworldManager()
    {
        parentNamedWorldHandler = APIRegistry.namedWorldHandler;
        APIRegistry.namedWorldHandler = this;
    }

    public void saveAll()
    {
        for (Multiworld world : getWorlds())
        {
            world.save();
        }
    }

    public void load()
    {
        Map<String, Multiworld> loadedWorlds = DataManager.getInstance().loadAll(Multiworld.class);
        for (Multiworld world : loadedWorlds.values())
        {
            if (world.getGeneratorOptions() == null)
            {
                world.setGeneratorOptions("");
            }

            worlds.put(world.getName(), world);
            try
            {
                setupMultiworldData(world);
                loadWorld(world);
            }
            catch (MultiworldException e)
            {
                switch (e.type)
                {
                case NO_BIOME_PROVIDER:
                    LoggingHandler.sulog.error(String.format(e.type.error, world.getBiomeProvider()));
                    break;
                case NO_DIMENSION_TYPE:
                    LoggingHandler.sulog.error(String.format(e.type.error, world.getDimensionType()));
                    break;
                case NO_DIMENSION_SETTINGS:
                    LoggingHandler.sulog.error(String.format(e.type.error, world.getDimensionSetting()));
                    break;
                case NO_CHUNK_GENERATOR:
                    LoggingHandler.sulog.error(String.format(e.type.error, world.getChunkGenerator()));
                    break;
                default:
                    LoggingHandler.sulog.error(e.type.error);
                    break;
                }
            }
        }
    }

    public Collection<Multiworld> getWorlds()
    {
        return worlds.values();
    }

    public ImmutableMap<String, Multiworld> getWorldMap()
    {
        return ImmutableMap.copyOf(worlds);
    }

    public Set<String> getDimensionsNames()
    {
        return worlds.keySet();
    }

    public Multiworld getMultiworld(String name)
    {
        return worlds.get(name);
    }

    // find a loaded multiworld by dimension resource name (shuruisutilities:<internalName>)
    public Multiworld getMultiworldByResourceName(String resourceName)
    {
        for (Multiworld mw : worlds.values())
            if (mw.getResourceName().equals(resourceName))
                return mw;
        return null;
    }

    // Ensure the SU multiworld with this resource name is in the live world map, re-registering on demand if
    // unloaded. Normally a cheap no-op (worlds created eagerly in load()); exists to cover the window where a
    // level is known to SU but marked worldLoaded==false / dropped from the map after an unload, so the respawn
    // flow can recover instead of dumping the player at vanilla spawn. null = unknown to SU, or (re)load failed.
    public ServerLevel ensureWorldLoaded(String resourceName)
    {
        if (resourceName == null)
            return null;
        Multiworld mw = getMultiworldByResourceName(resourceName);
        if (mw == null)
            return null;
        if (!mw.isLoaded())
        {
            try
            {
                loadWorld(mw);
            }
            catch (MultiworldException e)
            {
                LoggingHandler.sulog.warn("[Multiworld] ensureWorldLoaded failed to (re)load '{}': {}", resourceName,
                        e.type.error);
                return null;
            }
        }
        return mw.getWorldServer();
    }

    @Override
    public ServerLevel getWorld(String name)
    {
        ServerLevel world = parentNamedWorldHandler.getWorld(name);
        if (world != null)
            return world;

        Multiworld mw = getMultiworld(name);
        if (mw != null)
            return mw.getWorldServer();

        return null;
    }

    @Override
    public String getWorldName(String dimId)
    {
        Multiworld mw = getMultiworld(dimId);
        if (mw != null)
            return mw.getName();
        return parentNamedWorldHandler.getWorldName(dimId);
    }

    @Override
    public List<String> getWorldNames()
    {
        List<String> names = parentNamedWorldHandler.getWorldNames();
        names.addAll(worlds.keySet());
        return names;
    }

    public ProviderHelper getProviderHandler()
    {
        return providerHandler;
    }

    // register + load a multiworld; not registered if load fails
    public void addWorld(Multiworld world) throws MultiworldException
    {
        if (worlds.containsKey(world.getName()))
            throw new MultiworldException(Type.WORLD_ALREADY_EXISTS);
        setupMultiworldData(world);
        loadWorld(world);
        worlds.put(world.getName(), world);
        world.save();
        refreshCommandTrees();
    }

    // Re-send the command tree to everyone online. Needed after a runtime addWorld so the new dim shows up in
    // tab completion: DimensionArgument bakes its suggestions into the tree only sent on join, so a
    // runtime-added dim is otherwise invisible.
    public void refreshCommandTrees()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        for (var player : server.getPlayerList().getPlayers())
            server.getCommands().sendCommands(player);
    }

    // the world-import drop folder, <SUdir>/import/
    public static File getImportDir()
    {
        return new File(net.shurui.shuruisutilities.core.ShuruisUtilities.getSUDirectory(), "import");
    }

    // make sure <SUdir>/import/ exists (with a README) so admins can drop worlds in before running /mw import.
    // Called at server start; used to only appear the first time /mw import ran.
    public static void ensureImportDir()
    {
        File importDir = getImportDir();
        if (!importDir.exists())
            importDir.mkdirs();
        File readme = new File(importDir, "README.txt");
        if (!readme.exists())
        {
            try
            {
                java.nio.file.Files.writeString(readme.toPath(),
                        "Drop a world folder here to import it as a new multiworld, then run: /mw import\n\n"
                        + "Each sub-folder becomes a shuruisutilities:<foldername> dimension (folder name = world name).\n"
                        + "The folder must contain chunk data (a region/ folder) either directly or in a nested\n"
                        + "world/ or DIM* dimension folder. Folders without region data, or names that already exist,\n"
                        + "are skipped.\n");
            }
            catch (Exception ex)
            {
                LoggingHandler.sulog.warn("[Multiworld] could not write import/README.txt: {}", ex.toString());
            }
        }
    }

    // import/ sub-folders that actually hold importable chunk data (findRegionParent resolves a region/); for tab completion
    public List<String> listImportableFolders()
    {
        List<String> names = new ArrayList<>();
        File importDir = getImportDir();
        File[] folders = importDir.listFiles(File::isDirectory);
        if (folders == null)
            return names;
        for (File folder : folders)
            if (findRegionParent(folder) != null)
                names.add(folder.getName());
        return names;
    }

    // batch-import every import/ sub-folder (folder name = world name) in VOID mode, for the legacy no-arg
    // /mw import path. NOTE: batch is void-mode now (empty air outside imported chunks), not overworld-noise.
    public List<String> importWorlds()
    {
        List<String> imported = new ArrayList<>();
        File importDir = getImportDir();
        if (!importDir.exists())
            importDir.mkdirs();
        File[] folders = importDir.listFiles(File::isDirectory);
        if (folders == null)
            return imported;
        for (File folder : folders)
        {
            String name = folder.getName();
            String result = importWorld(name, name, false);
            if (result == null)
                imported.add(name);
            else
                LoggingHandler.sulog.warn("[Multiworld] import: skipping '{}': {}", name, result);
        }
        return imported;
    }

    // Import one import/ folder as a new multiworld. Dim id/save folder derives from worldName (decoupled from
    // the source folder name); source chunk data is copied into that folder BEFORE the dim is registered so
    // vanilla loads the imported chunks instead of generating fresh.
    //   void mode (continueTerrain==false, default): shuruisutilities:void generator, so imported chunks load
    //     from the copied region files and anything else stays empty air (no seams). Flagged isImportedVoid()
    //     so placeVoidPlatform leaves it alone.
    //   terrain mode (continueTerrain==true): overworld noise gen; the source save's seed from level.dat is
    //     threaded through so new chunks match the imported terrain. Falls back to server seed if unreadable.
    // Returns null on success, else a human-readable refusal reason.
    public String importWorld(String folderName, String worldName, boolean continueTerrain)
    {
        File importDir = getImportDir();
        File folder = new File(importDir, folderName);
        if (!folder.isDirectory())
            return "no import folder named '" + folderName + "' found in " + importDir.getAbsolutePath();

        File regionParent = findRegionParent(folder);
        if (regionParent == null)
            return "'" + folderName + "' has no region/ chunk data (checked the folder, world/, DIM*/ and "
                    + "dimensions/*/*)";

        String sanitized = Multiworld.sanitizeName(worldName);
        if (worlds.containsKey(worldName) || getMultiworldByResourceName(Multiworld.SUNameSpace + ":" + sanitized) != null)
            return "a world named '" + worldName + "' already exists";

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        LevelStorageSource.LevelStorageAccess levelSave = serverField(server, SRG_STORAGE_SOURCE);
        try
        {
            Multiworld mw;
            if (continueTerrain)
            {
                mw = new Multiworld(worldName, "minecraft:overworld", "minecraft:noise",
                        "minecraft:overworld", "minecraft:overworld");
                Long srcSeed = readLevelDatSeed(folder);
                if (srcSeed != null)
                {
                    mw.setSeed(srcSeed);
                    mw.setCustomSeed(true);
                    LoggingHandler.sulog.info("[Multiworld] import: terrain mode using source seed {} for '{}'",
                            srcSeed, worldName);
                }
                else
                {
                    LoggingHandler.sulog.warn("[Multiworld] import: could not read a seed from '{}'/level.dat; "
                            + "terrain mode will use the server seed (expect seams)", folderName);
                }
            }
            else
            {
                // void mode: superflat with NO layers via shuruisutilities:void. Imported chunks come from the
                // copied region files; everything else stays empty air.
                mw = new Multiworld(worldName, "minecraft:single", "shuruisutilities:void",
                        "minecraft:overworld", "minecraft:overworld");
                mw.setImportedVoid(true);
            }

            // copy chunk data to the EXACT folder the dim reads from (from LevelStorageAccess), BEFORE addWorld()
            // registers/loads it
            File dest = levelSave.getDimensionPath(mw.getResourceLocationUnique()).toFile();
            copyWorldData(regionParent, dest);
            LoggingHandler.sulog.info("[Multiworld] import: copied '{}' chunk data into {}", folderName,
                    dest.getAbsolutePath());
            addWorld(mw);
            LoggingHandler.sulog.info("[Multiworld] imported folder '{}' as world '{}' (dimension {}, mode {})",
                    folderName, worldName, mw.getResourceName(), continueTerrain ? "terrain" : "void");
            return null;
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.error("[Multiworld] import failed for folder '" + folderName + "'", e);
            return "import failed: " + e;
        }
    }

    // read the worldgen seed from a source save's level.dat: Data.WorldGenSettings.seed (1.16+), else legacy
    // Data.RandomSeed. null if no level.dat or no seed tag.
    private static Long readLevelDatSeed(File importFolder)
    {
        File levelDat = new File(importFolder, "level.dat");
        if (!levelDat.isFile())
            return null;
        try
        {
            net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(levelDat);
            if (!root.contains("Data", net.minecraft.nbt.Tag.TAG_COMPOUND))
                return null;
            net.minecraft.nbt.CompoundTag data = root.getCompound("Data");
            if (data.contains("WorldGenSettings", net.minecraft.nbt.Tag.TAG_COMPOUND))
            {
                net.minecraft.nbt.CompoundTag wgs = data.getCompound("WorldGenSettings");
                if (wgs.contains("seed", net.minecraft.nbt.Tag.TAG_LONG)
                        || wgs.contains("seed", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC))
                    return wgs.getLong("seed");
            }
            if (data.contains("RandomSeed", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC))
                return data.getLong("RandomSeed");
            return null;
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.warn("[Multiworld] import: failed to read seed from {}: {}",
                    levelDat.getAbsolutePath(), e.toString());
            return null;
        }
    }

    // Find the folder that directly holds a region/ dir. Preference order: the folder itself, world/
    // (Bukkit overworld), DIM* (vanilla nether/end), immediate children, dimensions/<ns>/<name>/ (dimensions
    // tree). Top-level region/ wins when several candidates exist.
    private static File findRegionParent(File folder)
    {
        if (new File(folder, "region").isDirectory())
            return folder;

        // world/region (Bukkit-style overworld export)
        File world = new File(folder, "world");
        if (new File(world, "region").isDirectory())
            return world;

        // DIM*/region (vanilla nether = DIM-1, end = DIM1)
        File[] children = folder.listFiles(File::isDirectory);
        if (children != null)
        {
            for (File c : children)
                if (c.getName().startsWith("DIM") && new File(c, "region").isDirectory())
                    return c;
            // any immediate child holding region/
            for (File c : children)
                if (new File(c, "region").isDirectory())
                    return c;
        }

        // dimensions/<namespace>/<name>/region (a datapack dimensions-tree export)
        File dimensions = new File(folder, "dimensions");
        File[] namespaces = dimensions.listFiles(File::isDirectory);
        if (namespaces != null)
            for (File ns : namespaces)
            {
                File[] dims = ns.listFiles(File::isDirectory);
                if (dims != null)
                    for (File d : dims)
                        if (new File(d, "region").isDirectory())
                            return d;
            }

        return null;
    }

    // copy the chunk-bearing subdirs of a world folder into a multiworld dimension folder
    private static void copyWorldData(File src, File dest) throws IOException
    {
        dest.mkdirs();
        for (String dir : new String[] { "region", "entities", "poi", "data" })
        {
            File s = new File(src, dir);
            if (s.isDirectory())
                copyDir(s.toPath(), new File(dest, dir).toPath());
        }
    }

    private static void copyDir(java.nio.file.Path src, java.nio.file.Path dest) throws IOException
    {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(src))
        {
            walk.forEach(p -> {
                try
                {
                    java.nio.file.Path target = dest.resolve(src.relativize(p));
                    if (java.nio.file.Files.isDirectory(p))
                        java.nio.file.Files.createDirectories(target);
                    else
                        java.nio.file.Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                catch (IOException e)
                {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    protected void setupMultiworldData(Multiworld world) throws MultiworldException
    {
        // Register dimension with last used id if possible if it has default created id
        if (world.getInternalID() < 10)
        {
            int unusedID = 10;
            for (Multiworld knownWorld : worlds.values())
            {
                if (knownWorld.getInternalID() >= unusedID)
                {
                    unusedID = knownWorld.getInternalID() + 1;
                }
            }
            world.setInternalID(unusedID);
        }
        // Handle permission-dim changes
        checkMultiworldPermissions(world);
        APIRegistry.perms.getServerZone().getWorldZone(world.getResourceName())
                .setGroupPermissionProperty(Zone.GROUP_DEFAULT, PERM_PROP_MULTIWORLD, world.getName());
    }

    // build the LevelStem (DimensionType + ChunkGenerator) from the server registries
    private LevelStem dimensionGenerator(MinecraftServer server, Multiworld world) throws MultiworldException
    {
        RegistryAccess registries = server.registryAccess();
        Holder<DimensionType> dimType = providerHandler.getDimensionTypeByName(world.getDimensionType());
        BiomeSource biomeSource = providerHandler.generateBiomeProviderByName(world.getBiomeProvider(), registries);
        Holder<NoiseGeneratorSettings> dimSettings = providerHandler.getDimensionSettingsByName(world.getDimensionSetting());
        ChunkGenerator chunkGenerator = providerHandler.generateChunkGeneratorByName(
                world.getChunkGenerator(), registries, biomeSource, dimSettings);
        return new LevelStem(dimType, chunkGenerator);
    }

    // insert a LevelStem into the frozen LEVEL_STEM registry so vanilla worldgen can resolve it
    private void registerLevelStem(MinecraftServer server, ResourceKey<Level> worldKey, LevelStem levelStem)
    {
        Registry<LevelStem> stemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, worldKey.location());
        if (stemRegistry.containsKey(stemKey))
            return;
        if (stemRegistry instanceof MappedRegistry<LevelStem> mappedRegistry)
        {
            mappedRegistry.unfreeze();
            mappedRegistry.register(stemKey, levelStem, Lifecycle.stable());
            mappedRegistry.freeze();
        }
        else
        {
            LoggingHandler.sulog.error("[Multiworld] LEVEL_STEM registry is not a MappedRegistry; cannot register {}",
                    worldKey.location());
        }
    }

    private static <T> T serverField(MinecraftServer server, String srgName)
    {
        return ObfuscationReflectionHelper.getPrivateValue(MinecraftServer.class, server, srgName);
    }

    private ServerLevel createAndRegisterWorldAndDimension(MinecraftServer server, ResourceKey<Level> worldKey, Multiworld world) throws MultiworldException
    {
        @SuppressWarnings("deprecation")
        Map<ResourceKey<Level>, ServerLevel> map = server.forgeGetWorldMap();

        ServerLevel existingLevel = map.get(worldKey);
        if (existingLevel != null)
            return existingLevel;

        ServerLevel overworld = server.overworld();
        LevelStem levelStem = dimensionGenerator(server, world);
        registerLevelStem(server, worldKey, levelStem);

        Executor executor = serverField(server, SRG_EXECUTOR);
        LevelStorageSource.LevelStorageAccess levelSave = serverField(server, SRG_STORAGE_SOURCE);
        ChunkProgressListenerFactory listenerFactory = serverField(server, SRG_PROGRESS_LISTENER_FACTORY);
        ChunkProgressListener progressListener = listenerFactory.create(11);

        ServerLevelData overworldData = server.getWorldData().overworldData();
        // per-dim level data carrying this Multiworld's OWN difficulty, delegating everything else (spawn, time,
        // weather, gamerules...) to the shared server data like DerivedLevelData. Without this the level's
        // difficulty always equals the overworld's, so a dim set to HARD can't spawn hostiles when the server is
        // PEACEFUL.
        MultiworldLevelData derivedWorldInfo = new MultiworldLevelData(server.getWorldData(), overworldData, world.getDifficulty());
        // a custom-seed world (terrain import that read the source save's seed) generates with that seed so new
        // chunks match the imported terrain; everything else shares the server seed
        long seed = world.hasCustomSeed() ? world.getSeed() : server.getWorldData().worldGenOptions().seed();

        // a per-world gen seed must be visible to ChunkMap, built inside the ServerLevel super-ctor via getSeed().
        // Stash it in a ThreadLocal that the ServerWorldMultiworld ctor + getSeed() read, then clear after ctor.
        if (world.hasCustomSeed())
            ServerWorldMultiworld.setNextSeedOverride(seed);
        else
            ServerWorldMultiworld.setNextSeedOverride(-1L);
        ServerLevel newWorld;
        try
        {
            newWorld = new ServerWorldMultiworld(
                server,
                executor,
                levelSave,
                derivedWorldInfo,
                worldKey,
                levelStem,
                progressListener,
                server.getWorldData().isDebugWorld(),
                BiomeManager.obfuscateSeed(seed),
                List.of(), // no special spawners; non-overworld dims are hardcoded with none in vanilla too
                false,     // tickTime is true only for the overworld
                null);     // RandomSequences derived from overworld storage (vanilla passes null for non-overworld)
        }
        finally
        {
            ServerWorldMultiworld.clearNextSeedOverride();
        }

        overworld.getWorldBorder().addListener(new BorderChangeListener.DelegateBorderChangeListener(newWorld.getWorldBorder()));
        map.put(worldKey, newWorld);

        // update forge's world cache (very important, if we don't do this then the new world won't tick!)
        server.markWorldsDirty();

        // Post LevelEvent.Load (not cancellable)
        MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(newWorld));

        return newWorld;
    }

    protected void loadWorld(Multiworld world) throws MultiworldException
    {
        if (world.worldLoaded)
            return;
        try
        {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            ResourceKey<Level> worldKey = world.getResourceLocationUnique();

            ServerLevel worldServer = createAndRegisterWorldAndDimension(server, worldKey, world);
            // flag loaded BEFORE updateWorldSettings(): it early-returns while !worldLoaded, so the old ordering
            // made its setSpawnSettings() a silent no-op and the dim inherited the server default spawnEnemies
            // (false when spawn-monsters=false) -> hostiles disabled even at HARD. Flag first, then apply
            // per-level spawn settings.
            world.worldLoaded = true;
            world.error = false;
            world.updateWorldSettings();

            // Post LevelEvent.Load
            MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(worldServer));
        }
        catch (Exception e)
        {
            world.error = true;
            throw e;
        }
    }

    // check WorldZone perms for multiworlds and move them to the right dim if it changed
    private static void checkMultiworldPermissions(Multiworld world)
    {
        for (WorldZone zone : APIRegistry.perms.getServerZone().getWorldZones().values())
        {
            String wn = zone.getGroupPermission(Zone.GROUP_DEFAULT, PERM_PROP_MULTIWORLD);
            if (wn != null && wn.equals(world.getName()))
            {
                if (zone.getDimensionID() != world.getResourceName())
                {
                    WorldZone newZone = APIRegistry.perms.getServerZone().getWorldZone(world.getResourceName());
                    // Swap the permissions of the multiworld with the one
                    // that's currently taking up it's dimID
                    zone.swapPermissions(newZone);
                }
                return;
            }
        }
    }

    public File unregisterWorld(Multiworld world)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = world.getWorldServer();
        File folder = null;
        if (level != null)
        {
            LevelStorageSource.LevelStorageAccess levelSave = serverField(server, SRG_STORAGE_SOURCE);
            folder = levelSave.getDimensionPath(level.dimension()).toFile();
        }
        world.worldLoaded = false;
        world.removeAllPlayersFromWorld();
        worldsToUnloadAndRemove.add(server.getLevel(world.getResourceLocationUnique()));
        worlds.remove(world.getName());
        return folder;
    }

    // unload a world and delete its data once unloaded
    public void deleteWorld(Multiworld world)
    {
        File deleting = unregisterWorld(world);
        if (deleting != null)
            worldsFoldersToDelete.add(deleting);
        world.delete();
    }

    // remove dims + clear multiworld data on server stop (integrated server)
    public void serverStopping()
    {
        saveAll();
        for (Multiworld world : worlds.values())
        {
            world.worldLoaded = false;
        }
        worlds.clear();
    }

    // Unloading and deleting of worlds

    @SubscribeEvent
    public void serverTickEvent(ServerTickEvent event)
    {
        unregisterDimensions();
        deleteDimensionFolder();
    }

    @SubscribeEvent
    public void worldUnloadEvent(LevelEvent.Unload event)
    {
        if (!(event.getLevel() instanceof ServerLevel serverLevel))
            return;
        Multiworld mw = getMultiworld(serverLevel.dimension().location().toString());
        if (mw != null)
            mw.worldLoaded = false;
    }

    // Unload all worlds marked for removal. The LevelStem is left in the frozen registry (removing an entry
    // means reflecting several index-linked maps); SU re-registers stems each boot from its JSON and deletion
    // drops that JSON, so the stem just won't be recreated next start.
    protected void unregisterDimensions()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        for (Iterator<ServerLevel> it = worldsToUnloadAndRemove.iterator(); it.hasNext();)
        {
            ServerLevel world = it.next();
            if (world == null)
            {
                it.remove();
                continue;
            }
            if (server.getLevel(world.dimension()) != null)
            {
                try
                {
                    LoggingHandler.sulog.info("[MultiWorld] Saving chunks for level '{}'/{}", world,
                            world.dimension().location());
                    world.noSave = true;
                    world.save(null, true, true);
                    try
                    {
                        MinecraftForge.EVENT_BUS.post(new LevelEvent.Unload(world));
                        world.close();
                    }
                    catch (IOException ioexception1)
                    {
                        LoggingHandler.sulog.error("Exception closing the level", (Throwable) ioexception1);
                    }
                    @SuppressWarnings("deprecation")
                    Map<ResourceKey<Level>, ServerLevel> map = server.forgeGetWorldMap();
                    map.remove(world.dimension());
                    server.markWorldsDirty();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    LoggingHandler.sulog.error("FAILED TO UNLOAD WORLD: " + world.dimension().location()
                            + ". Its LevelStem entry remains in the frozen registry until restart; SU world data has "
                            + "been removed so it will not be recreated.");
                }
                it.remove();
            }
        }
    }

    protected void deleteDimensionFolder()
    {
        for (Iterator<File> it = worldsFoldersToDelete.iterator(); it.hasNext();)
        {
            File folder = it.next();
            try
            {
                FileUtils.deleteDirectory(folder);
            }
            catch (IOException e)
            {
                LoggingHandler.sulog.error("Exception deleting the level", (Throwable) e);
                e.printStackTrace();
            }
            it.remove();
        }
    }
}
