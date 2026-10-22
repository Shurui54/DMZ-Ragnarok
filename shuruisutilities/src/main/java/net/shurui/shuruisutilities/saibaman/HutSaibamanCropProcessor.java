package net.shurui.shuruisutilities.saibaman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Grows saibaman crops in a Saibamen hut placed from a template (Vegeta's jigsaw huts), the same contents a hut on a
 * generated planet gets from {@code SurfaceStamp}: an independent chance of ONE growing seed at the floor centre (tend
 * it into a pet) and an independent chance of 1 to 3 more around the floor (break them for the seed item). Each crop
 * is age 0 on rocky_dirt, the block it needs to survive.
 *
 * <p>Runs after the template's other processors (the buried inert seed rule), on the whole placed piece: an interior
 * floor cell is a rocky_dirt (or inert seed) block with air above it, and cells are taken nearest the piece centre
 * first, so the planted seed sits in the middle. The roll is seeded from the piece position, so a hut always grows the
 * same crops.
 */
public final class HutSaibamanCropProcessor extends StructureProcessor
{
    public static final Codec<HutSaibamanCropProcessor> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("planted_chance", 0.33F).forGetter(p -> p.plantedChance),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("harvest_chance", 0.33F).forGetter(p -> p.harvestChance))
            .apply(i, HutSaibamanCropProcessor::new));

    private static final ResourceLocation ROCKY_DIRT_ID = new ResourceLocation("dragonminez", "rocky_dirt");

    private final float plantedChance;
    private final float harvestChance;

    public HutSaibamanCropProcessor(float plantedChance, float harvestChance)
    {
        this.plantedChance = plantedChance;
        this.harvestChance = harvestChance;
    }

    @Override
    protected StructureProcessorType<?> getType()
    {
        return SaibamanCropRegistry.HUT_CROPS_PROCESSOR.get();
    }

    @Override
    public StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos, StructureBlockInfo original,
                                           StructureBlockInfo current, StructurePlaceSettings settings)
    {
        return current;
    }

    @Override
    public List<StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset, BlockPos pos,
                                                       List<StructureBlockInfo> original, List<StructureBlockInfo> processed,
                                                       StructurePlaceSettings settings)
    {
        Block rockyDirt = ForgeRegistries.BLOCKS.getValue(ROCKY_DIRT_ID);
        Block crop = SaibamanCropRegistry.SAIBAMAN_CROP.get();
        Block inert = SaibamanCropRegistry.SAIBAMAN_SEED_INERT.get();
        if (rockyDirt == null || processed.isEmpty())
        {
            return processed;
        }
        RandomSource random = settings.getRandom(pos);
        boolean planted = random.nextFloat() < plantedChance;
        boolean harvest = random.nextFloat() < harvestChance;
        if (!planted && !harvest)
        {
            return processed;
        }
        int count = (planted ? 1 : 0) + (harvest ? 1 + random.nextInt(3) : 0);

        Map<BlockPos, Integer> index = new HashMap<>();
        double cx = 0.0;
        double cz = 0.0;
        for (int i = 0; i < processed.size(); ++i)
        {
            BlockPos p = processed.get(i).pos();
            index.put(p, i);
            cx += p.getX();
            cz += p.getZ();
        }
        cx /= processed.size();
        cz /= processed.size();

        // the lowest layer of the piece is its floor; an interior cell is floor dirt with air above it.
        int floorY = Integer.MAX_VALUE;
        for (StructureBlockInfo info : processed)
        {
            floorY = Math.min(floorY, info.pos().getY());
        }
        List<Integer> floors = new ArrayList<>();
        for (int i = 0; i < processed.size(); ++i)
        {
            StructureBlockInfo info = processed.get(i);
            if (info.pos().getY() != floorY)
            {
                continue;
            }
            Block b = info.state().getBlock();
            if (b != rockyDirt && b != inert)
            {
                continue;
            }
            Integer above = index.get(info.pos().above());
            if (above != null && processed.get(above).state().isAir())
            {
                floors.add(i);
            }
        }
        final double fcx = cx;
        final double fcz = cz;
        floors.sort((a, b) -> Double.compare(dist(processed.get(a).pos(), fcx, fcz), dist(processed.get(b).pos(), fcx, fcz)));

        List<StructureBlockInfo> out = new ArrayList<>(processed);
        BlockState dirtState = rockyDirt.defaultBlockState();
        BlockState cropState = crop.defaultBlockState().setValue(SaibamanCropBlock.AGE, 0);
        for (int n = 0; n < floors.size() && n < count; ++n)
        {
            int i = floors.get(n);
            BlockPos floorPos = processed.get(i).pos();
            out.set(i, new StructureBlockInfo(floorPos, dirtState, null));
            int a = index.get(floorPos.above());
            out.set(a, new StructureBlockInfo(floorPos.above(), cropState, null));
        }
        return out;
    }

    private static double dist(BlockPos p, double cx, double cz)
    {
        double dx = p.getX() - cx;
        double dz = p.getZ() - cz;
        return dx * dx + dz * dz;
    }
}
