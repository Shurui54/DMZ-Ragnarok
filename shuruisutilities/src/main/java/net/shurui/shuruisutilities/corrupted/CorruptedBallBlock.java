package net.shurui.shuruisutilities.corrupted;

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

import javax.annotation.Nullable;

/**
 * One of the seven blocks placed by the wish-tracking swap. The star number (1..7) is carried on the instance so
 * the interaction handler can tell a full set apart. Breakable and self-dropping (a normal loot table generated
 * by the asset pipeline). Registered from {@link CorruptedBalls}.
 *
 * <p>Renders through a GeckoLib block entity ({@link CorruptedBallBlockEntity}) so the placed block draws with
 * DMZ's real dragon ball geo model and the recoloured per-star texture, exactly like DMZ's own DragonBallBlock.
 * The render shape is {@code ENTITYBLOCK_ANIMATED}: the vanilla model is suppressed and the block entity
 * renderer draws it. The renderer is bound client side only, so this stays headless-safe on a dedicated server.
 *
 * <p>Like a GeckoLib block it must not behave as a full opaque cube for occlusion culling, otherwise the faces of
 * neighbouring blocks behind and below it get culled and you see straight through into the caves below. DMZ's own
 * DragonBallBlock avoids this with two things only: {@code .noOcclusion()} on its Properties (set in
 * {@link CorruptedBalls}) and a non-full {@link #getShape} override. We match both and add the belt-and-braces
 * skylight/shade/visual-shape overrides so the block never culls a neighbour or blocks skylight in any renderer.
 */
public final class CorruptedBallBlock extends BaseEntityBlock
{
    private final int star;

    // the ball sits on the ground and is roughly 10 of 16 blocks across, mirroring the old placeholder shape
    // (from [3,0,3] to [13,10,13]). the geo model esfera bone spans a similar footprint. this is intentionally
    // NOT a full cube so the block reads as non-opaque for occlusion.
    private static final VoxelShape SHAPE = Shapes.box(3.0D / 16.0D, 0.0D, 3.0D / 16.0D,
            13.0D / 16.0D, 10.0D / 16.0D, 13.0D / 16.0D);

    public CorruptedBallBlock(int star, BlockBehaviour.Properties properties)
    {
        super(properties);
        this.star = star;
    }

    public int getStar()
    {
        return star;
    }

    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    // the actual collision/outline shape: a non-full ball-sized box, exactly the mechanism DMZ's DragonBallBlock
    // uses so a placed ball never occludes the neighbours behind and below it.
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }

    // empty visual shape so no neighbour face is ever culled against this block (belt-and-braces past noOcclusion).
    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return Shapes.empty();
    }

    // let skylight pass straight through so the block never casts the full-cube shadow that darkened the terrain.
    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos)
    {
        return true;
    }

    // full ambient brightness (1.0) so the block does not shade itself or its neighbours like a solid cube.
    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos)
    {
        return 1.0F;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new CorruptedBallBlockEntity(pos, state);
    }
}
