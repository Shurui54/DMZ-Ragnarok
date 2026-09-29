package net.shurui.shuruisutilities.timemachine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import net.shurui.shuruisutilities.block.SUBlockEntities;

import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * GeckoLib block entity for the decorative {@link TimeMachineBlock}, so the placed block draws with the same geo the
 * rideable entity uses. Pure cosmetic and stateless (carries no NBT), mirroring {@code CorruptedBallBlockEntity}. The
 * geo has no keyframe animations, so the single controller never plays anything ({@link PlayState#STOP}).
 *
 * <p>Shard note: a placed block is block state in the level's region data, which genuinely does NOT travel between
 * servers (unlike player-carried curios). That is inherent to any block and expected: a time machine block built on
 * one shard stays on that shard. The rideable vehicle is the shard-portable form; this is set dressing.
 */
public final class TimeMachineBlockEntity extends BlockEntity implements GeoBlockEntity
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public TimeMachineBlockEntity(BlockPos pos, BlockState state)
    {
        super(SUBlockEntities.TIME_MACHINE.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

    private PlayState predicate(AnimationState<TimeMachineBlockEntity> state)
    {
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }

    // the life-size model spans past the anchor cell (about +-1.72 blocks horizontally and 5.85 up), so give the
    // renderer a bounding box that covers the whole footprint with margin. Without this the block-entity renderer is
    // frustum-culled the moment the anchor cell leaves view even though most of the model is still on screen.
    @Override
    public AABB getRenderBoundingBox()
    {
        BlockPos p = getBlockPos();
        return new AABB(p.getX() - 3, p.getY(), p.getZ() - 3, p.getX() + 4, p.getY() + 7, p.getZ() + 4);
    }
}
