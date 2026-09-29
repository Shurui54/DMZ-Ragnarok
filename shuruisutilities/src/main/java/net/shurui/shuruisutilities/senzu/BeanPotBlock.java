package net.shurui.shuruisutilities.senzu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blank bean pot: an empty pot with no planting behaviour and no block entity. It is only a crafting stepping
 * stone (blank pot + a bean makes the matching typed pot) and a decoration, so it has no properties at all. It shares
 * the same VoxelShape and self-drop-via-getDrops approach as {@link TypedBeanPotBlock} so no loot-table JSON is needed.
 */
public class BeanPotBlock extends Block
{
    private static final VoxelShape SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 8.0, 12.0);

    public BeanPotBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }

    // drop itself. Overriding getDrops keeps the blank pot loot-table-free like the typed pots.
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params)
    {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(this));
        return drops;
    }
}
