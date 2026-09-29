package net.shurui.shuruisutilities.ritual;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The placed idol: the artist's carved dragon behind glass on a gold plinth.
 *
 * <p>Drawn from an ordinary block model rather than through the block entity. It used to borrow DragonMineZ's live
 * Shenron rig and shrink it, which meant the idol inherited whatever DMZ did to that model and cost a GeckoLib
 * animation cache per placed block for a statue that never moves. The artist's model is plain block geometry, so it
 * costs nothing to draw and looks the same on every client.
 *
 * <p>{@link #DRAGON} chooses which dragon is in the case. The block entity stays registered and attached even though
 * nothing renders through it any more: it is already present in saved chunks, and dropping a registered block entity
 * type is how you lose the blocks that carry it.
 *
 * <p>The occlusion overrides are not optional decoration. A block that reports itself as a full opaque cube culls the
 * faces of everything behind and below it, which is how you end up seeing through the terrain around it. DragonMineZ's
 * own dragon ball block solves it with {@code noOcclusion()} plus a non-full shape; this matches both and adds the
 * skylight and shade overrides so no renderer treats it as solid.
 */
public final class ShenronIdolBlock extends BaseEntityBlock
{
    // A statue on a small plinth: narrower than a block and only about two thirds of one tall, so it reads as an
    // object sitting on the ground rather than as terrain.
    private static final VoxelShape SHAPE = Shapes.box(0.25D, 0.0D, 0.25D, 0.75D, 0.7D, 0.75D);

    /**
     * Which dragon the case holds. Defaults to Shenron so every idol already in a world, and every state saved
     * before this property existed, reads back exactly as it was.
     */
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<IdolDragon> DRAGON =
            net.minecraft.world.level.block.state.properties.EnumProperty.create("dragon", IdolDragon.class);

    /** The dragons an idol can depict. The name is the blockstate value and the model file's prefix. */
    public enum IdolDragon implements net.minecraft.util.StringRepresentable
    {
        SHENRON("shenron"),
        PORUNGA("porunga");

        private final String name;

        IdolDragon(String name)
        {
            this.name = name;
        }

        @Override
        public String getSerializedName()
        {
            return name;
        }
    }

    public ShenronIdolBlock(BlockBehaviour.Properties properties)
    {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(DRAGON, IdolDragon.SHENRON));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block,
                    BlockState> builder)
    {
        builder.add(DRAGON);
    }

    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new ShenronIdolBlockEntity(pos, state);
    }
}
