package net.shurui.shuruisutilities.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A decorative, indestructible portal fill block: a thin plane (like a nether portal) tinted to a
 * {@link #COLOR dye color} on the client. Used by the portal system as a colored alternative to the vanilla
 * nether/end portal fill. {@link #AXIS} orients the plane: X and Z stand it vertical (facing the two
 * horizontal directions), Y lays it flat as a horizontal portal on the floor. It has no collision and
 * doesn't handle dimension travel itself (Shurui's Utilities portals teleport via their own area trigger).
 */
public class ColoredPortalBlock extends Block
{
    public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);
    // Full X/Y/Z axis (not HORIZONTAL_AXIS) so the plane can also lie flat (Y) for a horizontal portal.
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    protected static final VoxelShape X_SHAPE = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);
    protected static final VoxelShape Z_SHAPE = Block.box(6.0, 0.0, 0.0, 10.0, 16.0, 16.0);
    protected static final VoxelShape Y_SHAPE = Block.box(0.0, 6.0, 0.0, 16.0, 10.0, 16.0);

    public ColoredPortalBlock(Properties properties)
    {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(COLOR, DyeColor.PURPLE).setValue(AXIS,
                Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(COLOR, AXIS);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx)
    {
        return switch (state.getValue(AXIS))
        {
            case Y -> Y_SHAPE;
            case Z -> Z_SHAPE;
            default -> X_SHAPE;
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx)
    {
        return Shapes.empty();
    }

    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos)
    {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos)
    {
        return 1.0F;
    }
}
