package net.shurui.shuruisutilities.worldgen;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * A noise generator that only generates inside a box, and leaves everything outside it as void.
 *
 * <h2>What it is for</h2>
 * Two of this suite's dimensions, {@code dmz_ragnarok:namekow} and {@code dmz_ragnarok:kaiow}, are meant to be a
 * 4096x4096 island of terrain in an otherwise empty world: that is what was imported into them. They shipped as
 * plain noise dimensions with a 4000x4000 SU world border laid over the top, which is not the same thing at all.
 * The world carries on generating for ever in every direction and the border is only a fence in the middle of it,
 * so anybody who gets past the fence walks into an endless world, and the server generates and stores chunks
 * nobody is meant to visit.
 *
 * <p>Inside the box this generates exactly what the dimension generated before, so terrain already on disk keeps
 * matching what appears beside it. Outside, it answers "not here" to everything: no terrain, no surface, no
 * carvers, no features, no structures, no mobs.
 *
 * <h2>Why it EXTENDS the noise generator rather than wrapping one</h2>
 * Because the level asks the question by type. {@code ChunkMap} builds the {@link RandomState} that every noise
 * lookup goes through with
 * {@code generator instanceof NoiseBasedChunkGenerator ? RandomState.create(its settings) : RandomState.create(NoiseGeneratorSettings.dummy())},
 * so a wrapper that merely HELD a noise generator got the whole dimension a dummy random state: the density
 * functions returned nothing, and chunks inside the box generated as air with a little water and lava in them,
 * which is worse than the endless world it replaced. Extending the real thing with the inner generator's own biome
 * source and settings is what makes the level hand out the right RandomState.
 *
 * <p>The inner generator is still kept, and still what the codec reads and writes, so the dimension JSON stays a
 * plain nested generator and nothing about the shipped file has to know this detail.
 *
 * <h2>Sharp edge, on purpose</h2>
 * The boundary is a cliff into void, not a taper. A taper would mean generating terrain outside the box, which is
 * the thing being removed. The SU world border sits inside the edge on every side, so a player is stopped while
 * there is still ground under them.
 *
 * <h2>The edge is sealed against fluid</h2>
 * The box cuts through namekow's ocean, and a cut ocean pours off the cliff: water at the exposed face flows into
 * the void one column at a time and falls to the bottom of the world, writing chunks OUTSIDE the box, which is half
 * of what this generator exists to stop. Found on the first bounded world: two chunks past namekow's {@code +x} edge
 * held nothing but a single full-height column of {@code namek_water_liquid_block} each.
 *
 * <p>So the outermost column of the box, on all four sides, has its fluid turned into the settings' default block:
 * a one-block rim of solid ground wherever water meets the edge, dry land left exactly as it was.
 *
 * <p><b>Draining that column to AIR does not work</b>, which is worth knowing before anyone simplifies this. Fluid
 * spreads into adjacent air, so an emptied edge column is refilled by the source one block inside it on the next
 * fluid tick, and then pours over the edge as before. The rim has to be SOLID to be a wall. It is only one block
 * wide because that is all a wall needs to be: the fluid behind it never becomes adjacent to the void.
 *
 * <h2>Chunk granularity</h2>
 * The test is per CHUNK: a chunk generates if it touches the box at all. A chunk is the unit the whole pipeline
 * works in, and a half-generated chunk is not a thing it can express. With the box edges on chunk boundaries this
 * is exact.
 *
 * <h2>Existing worlds</h2>
 * The mod copies its worldgen JSON into {@code <world>/datapacks/dmz_ragnarok_worldgen/} on every start, and a
 * datapack dimension outranks the copy baked into {@code level.dat}, so an existing world picks this up on the
 * second start after updating: one start refreshes the pack, the next loads it.
 * {@code world-tools/bound-dims/migrate.py} does it in one step, and can also delete the chunks the endless
 * generator already wrote outside the box.
 */
public class BoundedChunkGenerator extends NoiseBasedChunkGenerator
{
    public static final Codec<BoundedChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ChunkGenerator.CODEC.fieldOf("generator").forGetter(g -> g.inner),
            Codec.INT.fieldOf("center_x").forGetter(g -> g.centerX),
            Codec.INT.fieldOf("center_z").forGetter(g -> g.centerZ),
            Codec.INT.fieldOf("half_extent").forGetter(g -> g.halfExtent))
            .apply(instance, instance.stable(BoundedChunkGenerator::new)));

    private final NoiseBasedChunkGenerator inner;
    private final int centerX;
    private final int centerZ;
    private final int halfExtent;

    /** Box edges in block coordinates, inclusive. Precomputed because they are asked per chunk. */
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public BoundedChunkGenerator(ChunkGenerator inner, int centerX, int centerZ, int halfExtent)
    {
        // The inner generator's own biome source and noise settings, so this IS that generator as far as the level
        // and its RandomState are concerned, and only the box is added on top.
        super(inner.getBiomeSource(), noiseSettingsOf(inner));
        this.inner = (NoiseBasedChunkGenerator) inner;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.halfExtent = Math.max(16, halfExtent);
        this.minX = centerX - this.halfExtent;
        this.maxX = centerX + this.halfExtent - 1;
        this.minZ = centerZ - this.halfExtent;
        this.maxZ = centerZ + this.halfExtent - 1;
    }

    /**
     * The inner generator's noise settings, and the check that there ARE any.
     *
     * <p>A bounded flat or void generator would be pointless (a void world is already void, and a flat one has no
     * expensive generation to stop), and allowing one here would silently produce the dummy-random-state world
     * described above. Refusing outright turns that into a datapack error naming the file, which is a fixable
     * message rather than a dimension that generates wrong.
     */
    private static net.minecraft.core.Holder<net.minecraft.world.level.levelgen.NoiseGeneratorSettings>
            noiseSettingsOf(ChunkGenerator inner)
    {
        if (!(inner instanceof NoiseBasedChunkGenerator noise))
        {
            throw new IllegalArgumentException("dmz_ragnarok:bounded needs a minecraft:noise generator inside it,"
                    + " got " + (inner == null ? "nothing" : inner.getClass().getSimpleName()));
        }
        return noise.generatorSettings();
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec()
    {
        return CODEC;
    }

    public ChunkGenerator inner()
    {
        return inner;
    }

    /** Whether this chunk touches the box at all. A chunk that does generates in full. */
    public boolean generates(ChunkPos pos)
    {
        return pos.getMaxBlockX() >= minX && pos.getMinBlockX() <= maxX
                && pos.getMaxBlockZ() >= minZ && pos.getMinBlockZ() <= maxZ;
    }

    /** Whether this column is inside the box. Used by the height and column queries, which are per block. */
    private boolean inside(int x, int z)
    {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Whether this column is the outermost ring of the box, the one block that has void beside it. */
    private boolean isRimColumn(int x, int z)
    {
        return inside(x, z) && (x == minX || x == maxX || z == minZ || z == maxZ);
    }

    /** Whether this chunk holds any of the box's outermost columns. Most chunks do not, so this is asked first. */
    private boolean touchesRim(ChunkPos pos)
    {
        if (!generates(pos))
        {
            return false;
        }
        boolean xRim = (minX >= pos.getMinBlockX() && minX <= pos.getMaxBlockX())
                || (maxX >= pos.getMinBlockX() && maxX <= pos.getMaxBlockX());
        boolean zRim = (minZ >= pos.getMinBlockZ() && minZ <= pos.getMaxBlockZ())
                || (maxZ >= pos.getMinBlockZ() && maxZ <= pos.getMaxBlockZ());
        return xRim || zRim;
    }

    /**
     * Turn any fluid in the box's outermost columns into solid ground, so a cut ocean has no face to pour out of.
     *
     * <p>Run after every step that can place a fluid: the noise pass (aquifers), the surface pass (surface rules)
     * and decoration (springs and lakes). Cheap for the whole world because all but the perimeter chunks stop at
     * {@link #touchesRim}.
     *
     * <p>Known limit: decoration of one chunk may write into its neighbour, so a water feature placed at the very
     * rim by a chunk decorated LATER is not caught. It leaks a column rather than a lake, and it is past the world
     * border where nobody stands.
     */
    private void sealRimFluids(ChunkAccess chunk)
    {
        ChunkPos pos = chunk.getPos();
        if (!touchesRim(pos))
        {
            return;
        }
        BlockState fill = generatorSettings().value().defaultBlock();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++)
        {
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++)
            {
                if (!isRimColumn(x, z))
                {
                    continue;
                }
                for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++)
                {
                    at.set(x, y, z);
                    if (!chunk.getBlockState(at).getFluidState().isEmpty())
                    {
                        chunk.setBlockState(at, fill, false);
                    }
                }
            }
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random,
                                                        StructureManager structures, ChunkAccess chunk)
    {
        if (!generates(chunk.getPos()))
        {
            // Handing the chunk straight back leaves it exactly as the pipeline made it, which is all air. That is
            // what a void generator produces too, so a void chunk here and a void chunk there are the same thing.
            return CompletableFuture.completedFuture(chunk);
        }
        // The aquifers run in here, so this is the first place a cut ocean can appear at the rim.
        return super.fillFromNoise(executor, blender, random, structures, chunk).thenApply(filled ->
        {
            sealRimFluids(filled);
            return filled;
        });
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk)
    {
        if (generates(chunk.getPos()))
        {
            super.buildSurface(region, structures, random, chunk);
            sealRimFluids(chunk);
        }
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
                             StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step)
    {
        if (generates(chunk.getPos()))
        {
            super.applyCarvers(region, seed, random, biomes, structures, chunk, step);
        }
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures)
    {
        if (generates(chunk.getPos()))
        {
            super.applyBiomeDecoration(level, chunk, structures);
            // Springs and lakes place fluid too, and they run after the surface.
            sealRimFluids(chunk);
        }
    }

    @Override
    public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state,
                                 StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates)
    {
        if (generates(chunk.getPos()))
        {
            super.createStructures(registries, state, structures, chunk, templates);
        }
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk)
    {
        if (generates(chunk.getPos()))
        {
            super.createReferences(level, structures, chunk);
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region)
    {
        if (generates(region.getCenter()))
        {
            super.spawnOriginalMobs(region);
        }
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random)
    {
        // Outside the box the answer is "there is no ground", which is the bottom of the world. Anything that
        // places by height (a spawn search, a structure probe) then finds nothing rather than a phantom surface.
        return inside(x, z) ? super.getBaseHeight(x, z, type, level, random) : level.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random)
    {
        if (inside(x, z))
        {
            return super.getBaseColumn(x, z, level, random);
        }
        BlockState[] air = new BlockState[level.getHeight()];
        java.util.Arrays.fill(air, Blocks.AIR.defaultBlockState());
        return new NoiseColumn(level.getMinBuildHeight(), air);
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos pos)
    {
        super.addDebugScreenInfo(lines, random, pos);
        lines.add("Bounded: " + (inside(pos.getX(), pos.getZ()) ? "inside" : "outside")
                + " [" + minX + "," + minZ + " .. " + maxX + "," + maxZ + "]");
    }
}
