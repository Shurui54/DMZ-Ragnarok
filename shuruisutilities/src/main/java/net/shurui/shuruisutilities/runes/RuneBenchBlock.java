package net.shurui.shuruisutilities.runes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The rune bench: an armour piece goes in, runes go in and out of its slots.
 *
 * <p>No block entity on purpose. The bench holds nothing between uses, exactly like a crafting table: its container
 * is built per player when the screen opens and its contents are returned when it closes. A bench that stored items
 * would be a place armour could be forgotten, or lost outright if the block were broken while full.
 */
public class RuneBenchBlock extends Block
{
    private static final Component TITLE = Component.translatable("container.dmz_ragnarok.rune_bench");

    /**
     * The bench's footprint. Its model is a table, not a cube: the legs stop at y3, the top surface is at y12 of 16,
     * and there are gaps between the legs. One solid box up to the table top is what a player expects to walk into
     * and stand on, rather than a shape that lets them fall between the legs or clip through the top.
     */
    private static final VoxelShape SHAPE = Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 0.75D, 1.0D);

    public RuneBenchBlock(Properties props)
    {
        super(props);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit)
    {
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        player.openMenu(provider(level, pos));
        return InteractionResult.CONSUME;
    }

    private MenuProvider provider(Level level, BlockPos pos)
    {
        return new SimpleMenuProvider(
                (id, inv, p) -> new RuneBenchMenu(id, inv, net.minecraft.world.inventory.ContainerLevelAccess.create(level, pos)),
                TITLE);
    }
}
