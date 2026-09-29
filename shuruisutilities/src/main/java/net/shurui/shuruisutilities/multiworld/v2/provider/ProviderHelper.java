package net.shurui.shuruisutilities.multiworld.v2.provider;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.shurui.shuruisutilities.multiworld.v2.utils.MultiworldException;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Multiworld provider registry, 1.20.1 port.
 *
 * <p>1.16 rebuilt BiomeProvider/ChunkGenerator/DimensionSettings reflectively from mod-scanned holder classes.
 * On 1.20.1 worldgen is registry-driven: dimension types + noise settings live in dynamic registries, biome
 * distribution is a preset parameter-list (climate) model. So resolve everything from {@link RegistryAccess}
 * and build a small fixed catalogue of vanilla biome-source / chunk-generator factories. External-mod provider
 * helpers (TwilightForest/MiddleEarth/BumbleZone) are dropped: those classes are never on the classpath and
 * can't be built generically.</p>
 */
public class ProviderHelper
{
    @FunctionalInterface
    public interface BiomeSourceFactory
    {
        BiomeSource create(RegistryAccess registries);
    }

    @FunctionalInterface
    public interface ChunkGeneratorFactory
    {
        ChunkGenerator create(RegistryAccess registries, BiomeSource biomeSource, Holder<NoiseGeneratorSettings> noiseSettings);
    }

    private final Map<String, Holder<DimensionType>> dimensionTypes = new TreeMap<>();

    // vanilla (minecraft:) dimension types, for the login fix's non-vanilla check
    private final Map<String, DimensionType> vanillaDimensionTypes = new TreeMap<>();

    // NoiseGeneratorSettings ("dimension settings") name -> registry holder
    private final Map<String, Holder<NoiseGeneratorSettings>> dimensionSettings = new TreeMap<>();

    private final Map<String, BiomeSourceFactory> biomeProviders = new TreeMap<>();

    private final Map<String, ChunkGeneratorFactory> chunkGenerators = new TreeMap<>();

    private static RegistryAccess registryAccess()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server.registryAccess();
    }

    // DimensionType management

    public void loadDimensionTypes()
    {
        dimensionTypes.clear();
        vanillaDimensionTypes.clear();
        var registry = registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
        for (Map.Entry<ResourceKey<DimensionType>, DimensionType> entry : registry.entrySet())
        {
            String name = entry.getKey().location().toString();
            dimensionTypes.put(name, registry.getHolderOrThrow(entry.getKey()));
            if (entry.getKey().location().getNamespace().equals("minecraft"))
                vanillaDimensionTypes.put(name, entry.getValue());
        }
        LoggingHandler.sulog.info("[Multiworld] Found {} DimensionTypes", dimensionTypes.size());
    }

    public Holder<DimensionType> getDimensionTypeByName(String dimensionType) throws MultiworldException
    {
        Holder<DimensionType> type = dimensionTypes.get(dimensionType);
        if (type == null)
            throw new MultiworldException(MultiworldException.Type.NO_DIMENSION_TYPE);
        return type;
    }

    public Map<String, Holder<DimensionType>> getDimensionTypes()
    {
        return dimensionTypes;
    }

    public Map<String, DimensionType> getVanillaDimensionTypes()
    {
        return vanillaDimensionTypes;
    }

    // NoiseGeneratorSettings ("dimension settings") management

    public void loadDimensionSettings()
    {
        dimensionSettings.clear();
        var registry = registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        for (Map.Entry<ResourceKey<NoiseGeneratorSettings>, NoiseGeneratorSettings> entry : registry.entrySet())
            dimensionSettings.put(entry.getKey().location().toString(), registry.getHolderOrThrow(entry.getKey()));
        LoggingHandler.sulog.info("[Multiworld] Found {} DimensionSettings", dimensionSettings.size());
    }

    public Holder<NoiseGeneratorSettings> getDimensionSettingsByName(String dimensionSetting) throws MultiworldException
    {
        Holder<NoiseGeneratorSettings> type = dimensionSettings.get(dimensionSetting);
        if (type == null)
            throw new MultiworldException(MultiworldException.Type.NO_DIMENSION_SETTINGS);
        return type;
    }

    public Map<String, Holder<NoiseGeneratorSettings>> getDimensionSettings()
    {
        return dimensionSettings;
    }

    // BiomeSource management

    public void loadBiomeProviders()
    {
        biomeProviders.clear();
        biomeProviders.put("minecraft:overworld", ra -> multiNoisePreset(ra, MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        // large biomes shares the overworld climate parameters; the "large" scaling comes from the noise settings
        biomeProviders.put("minecraft:overworld_large", ra -> multiNoisePreset(ra, MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        biomeProviders.put("minecraft:nether", ra -> multiNoisePreset(ra, MultiNoiseBiomeSourceParameterLists.NETHER));
        biomeProviders.put("minecraft:end", ra -> TheEndBiomeSource.create(ra.registryOrThrow(Registries.BIOME).asLookup()));
        biomeProviders.put("minecraft:single", ra -> new FixedBiomeSource(ra.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
        LoggingHandler.sulog.info("[Multiworld] Registered {} BiomeProviders", biomeProviders.size());
    }

    private static BiomeSource multiNoisePreset(RegistryAccess ra, ResourceKey<MultiNoiseBiomeSourceParameterList> preset)
    {
        return MultiNoiseBiomeSource.createFromPreset(
                ra.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getHolderOrThrow(preset));
    }

    public BiomeSource generateBiomeProviderByName(String biomeProviderType, RegistryAccess registries) throws MultiworldException
    {
        BiomeSourceFactory factory = biomeProviders.get(biomeProviderType);
        if (factory == null)
            throw new MultiworldException(MultiworldException.Type.NO_BIOME_PROVIDER);
        BiomeSource source = null;
        try
        {
            source = factory.create(registries);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        if (source == null)
            throw new MultiworldException(MultiworldException.Type.NULL_BIOME_PROVIDER);
        return source;
    }

    public Set<String> getBiomeProviders()
    {
        return biomeProviders.keySet();
    }

    // ChunkGenerator management

    public void loadChunkGenerators()
    {
        chunkGenerators.clear();
        chunkGenerators.put("minecraft:noise", (ra, biomeSource, noise) -> new NoiseBasedChunkGenerator(biomeSource, noise));
        chunkGenerators.put("minecraft:flat", (ra, biomeSource, noise) -> new FlatLevelSource(
                FlatLevelGeneratorSettings.getDefault(
                        ra.registryOrThrow(Registries.BIOME).asLookup(),
                        ra.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),
                        ra.registryOrThrow(Registries.PLACED_FEATURE).asLookup())));
        chunkGenerators.put("minecraft:debug", (ra, biomeSource, noise) -> new DebugLevelSource(
                ra.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
        // Void generator: a superflat with NO layers → an empty (all-air) world. SU places a single safe
        // platform block at 0,0,0 on load (see MultiworldVoidHandler) so players can /mwtp there safely.
        chunkGenerators.put("shuruisutilities:void", (ra, biomeSource, noise) -> {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome =
                    ra.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.THE_VOID);
            FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(java.util.Optional.empty(), biome,
                    java.util.List.of()).withBiomeAndLayers(java.util.List.of(), java.util.Optional.empty(), biome);
            return new FlatLevelSource(settings);
        });
        LoggingHandler.sulog.info("[Multiworld] Registered {} ChunkGenerators", chunkGenerators.size());
    }

    public ChunkGenerator generateChunkGeneratorByName(String chunkGeneratorType, RegistryAccess registries,
            BiomeSource biomeSource, Holder<NoiseGeneratorSettings> noiseSettings) throws MultiworldException
    {
        ChunkGeneratorFactory factory = chunkGenerators.get(chunkGeneratorType);
        if (factory == null)
            throw new MultiworldException(MultiworldException.Type.NO_CHUNK_GENERATOR);
        ChunkGenerator generator = null;
        try
        {
            generator = factory.create(registries, biomeSource, noiseSettings);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        if (generator == null)
            throw new MultiworldException(MultiworldException.Type.NO_CHUNK_GENERATOR);
        return generator;
    }

    public Set<String> getChunkGenerators()
    {
        return chunkGenerators.keySet();
    }
}
