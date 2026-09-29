package net.shurui.shuruisutilities.corrupted;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.shurui.shuruisutilities.block.SUBlockEntities;

import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * GeckoLib block entity for a placed swap ball, so the block can draw with DMZ's real dragon ball geo model
 * instead of a flat cube. Mirrors DMZ's own {@code DragonBallBlockEntity} (a {@link GeoBlockEntity} with a
 * singleton instance cache and a single looping idle controller) and sdu's in-workspace GeckoLib idiom.
 *
 * <p>The star number is not stored here: it is read from the owning {@link CorruptedBallBlock} at render time,
 * so this entity carries no extra state and never needs to serialise. Pure cosmetic; safe to be absent server
 * side (nothing here is ever queried off the client render path except the star lookup, which is state-only).
 */
public final class CorruptedBallBlockEntity extends BlockEntity implements GeoBlockEntity
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public CorruptedBallBlockEntity(BlockPos pos, BlockState state)
    {
        super(SUBlockEntities.CORRUPTED_BALL.get(), pos, state);
    }

    // the star (1..7) of the block that owns this entity; defaults to 1 if the state is somehow not our block
    public int getStar()
    {
        if (getBlockState().getBlock() instanceof CorruptedBallBlock ball)
            return ball.getStar();
        return 1;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

    // DMZ's dball animation file names the idle clip "animation.dball1.idle"; ".then" takes the part after
    // "animation.". reused as-is because the geo is a copy of DMZ's, keeping the same bone/animation set.
    private PlayState predicate(AnimationState<CorruptedBallBlockEntity> state)
    {
        state.getController().setAnimation(RawAnimation.begin().then("dball1.idle", Animation.LoopType.LOOP));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }
}
