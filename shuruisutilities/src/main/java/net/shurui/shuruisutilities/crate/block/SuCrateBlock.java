package net.shurui.shuruisutilities.crate.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A crate block for the SU crate system: the animated furniture a crate config is bound to.
 *
 * <p>Which of the models it wears is fixed per block rather than stored per position, because these are chosen
 * by an admin placing a specific crate, not rolled. The crate CONFIG (its rewards and its key) is still bound to
 * the position by {@code CrateManager}, exactly as it is for any other block, so this changes how a crate LOOKS
 * and nothing about how it works.
 */
public class SuCrateBlock extends Block implements EntityBlock
{
    /** Sixteen 22.5 degree steps, like a banner: furniture should sit at an angle into a corner, not snap square. */
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 15);

    private final String modelName;
    private final String texturePath;

    public SuCrateBlock(Properties properties, String modelName, String texturePath)
    {
        super(properties);
        this.modelName = modelName;
        this.texturePath = texturePath;
        registerDefaultState(this.stateDefinition.any().setValue(ROTATION, 0));
    }

    /** Geometry and animation name. Several crates share one, since the Lootcrates boxes differ only by skin. */
    public String modelName()
    {
        return modelName;
    }

    /** Texture path under assets/dmz_ragnarok/textures/, without the extension. */
    public String texturePath()
    {
        return texturePath;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(ROTATION);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        float yaw = context.getPlayer() == null ? 0f : context.getPlayer().getYRot();
        return defaultBlockState().setValue(ROTATION, Math.floorMod(Math.round(yaw / 22.5f), 16));
    }

    /** The crate's turn in degrees, for the renderer. */
    public static float rotationDegrees(BlockState state)
    {
        return (state.hasProperty(ROTATION) ? state.getValue(ROTATION) : 0) * -22.5f;
    }

    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new SuCrateBlockEntity(pos, state);
    }
}
