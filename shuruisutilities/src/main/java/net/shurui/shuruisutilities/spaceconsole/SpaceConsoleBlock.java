package net.shurui.shuruisutilities.spaceconsole;

import java.util.EnumMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A decorative, horizontally facing sci-fi console. Placed facing the player; the model and the collision/selection
 * shape both rotate with the {@link #FACING} property so the hitbox always tracks the visible geometry.
 *
 * <p>These models overhang their own block cell (the widest reaches well past 16 units on the X axis, the tallest
 * reaches two blocks up). The shape supplied here is authored in the NORTH orientation to match the visible bounds,
 * then rotated for the other three facings in {@link #rotateY(VoxelShape, int)}. Because Level.clip only tests a
 * block's shape while the pick ray is inside that block's OWN cell, the parts that hang into neighbouring cells are
 * only right clickable from the ground cell the block occupies; the base of every one of these sits inside its own
 * cell, so the block is always clickable from the front at ground level. Collision beyond the cell still works, the
 * same way a fence (1.5 blocks tall) collides past its cell.
 *
 * <p>Non full and {@code noOcclusion}, so it never culls the faces of terrain behind or below it.
 */
public class SpaceConsoleBlock extends HorizontalDirectionalBlock
{
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final EnumMap<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    /**
     * @param properties block behaviour
     * @param northShape the collision/selection shape in the NORTH facing, in 0..1 world units (may extend outside
     *                   0..1 where the model overhangs its cell)
     */
    public SpaceConsoleBlock(Properties properties, VoxelShape northShape)
    {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
        // Precompute the four rotations once. NORTH is the authored shape; each further facing is a 90 degree
        // clockwise (viewed from above) turn, matching the blockstate y rotations north:0 east:90 south:180 west:270.
        shapes.put(Direction.NORTH, northShape);
        shapes.put(Direction.EAST, rotateY(northShape, 1));
        shapes.put(Direction.SOUTH, rotateY(northShape, 2));
        shapes.put(Direction.WEST, rotateY(northShape, 3));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        // Face the player: getHorizontalDirection is the way the player looks, so the opposite points the front at them.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation)
    {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror)
    {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return shapes.getOrDefault(state.getValue(FACING), Shapes.block());
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return shapes.getOrDefault(state.getValue(FACING), Shapes.block());
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos)
    {
        // Never occlude neighbours: these are open, detailed props, not solid cubes.
        return Shapes.empty();
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

    /**
     * Rotate a shape a quarter turn clockwise (viewed from above) {@code times} times, about the block centre
     * (0.5, 0.5). Works for shapes that reach outside the 0..1 cell, because the pivot is the centre, not the edge.
     */
    private static VoxelShape rotateY(VoxelShape shape, int times)
    {
        VoxelShape result = shape;
        for (int i = 0; i < times; i++)
        {
            VoxelShape source = result;
            VoxelShape[] acc = new VoxelShape[] { Shapes.empty() };
            source.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                    acc[0] = Shapes.or(acc[0], Shapes.box(1.0D - maxZ, minY, minX, 1.0D - minZ, maxY, maxX)));
            result = acc[0];
        }
        return result;
    }

    @Nullable
    public static VoxelShape box16(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        return Shapes.box(x1 / 16.0D, y1 / 16.0D, z1 / 16.0D, x2 / 16.0D, y2 / 16.0D, z2 / 16.0D);
    }
}
