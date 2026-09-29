package net.shurui.shuruisutilities.crate.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.shurui.shuruisutilities.block.SUBlockEntities;

import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The block entity behind every SU crate block.
 *
 * <p>One type serves all of them: which crate it is comes from the BLOCK, not from anything stored here, so a
 * raven and a legendary box are the same block entity pointed at different art. It holds nothing but animation
 * state, and only while an opening lasts.
 *
 * <p>The idle loops for ever, so a crate is always moving. {@link #triggerOpen()} plays the open once and holds
 * on its last frame, so a crate that has been opened stays open rather than snapping shut.
 */
public class SuCrateBlockEntity extends BlockEntity implements GeoBlockEntity
{
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation OPEN =
            RawAnimation.begin().then("open", Animation.LoopType.PLAY_ONCE);

    private static final String CONTROLLER = "crate";
    private static final String TRIGGER_OPEN = "open";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public SuCrateBlockEntity(BlockPos pos, BlockState state)
    {
        super(SUBlockEntities.SU_CRATE.get(), pos, state);
    }

    /** Which art this crate wears, taken from the block so nothing has to be stored or synced per crate. */
    public String modelName()
    {
        return getBlockState().getBlock() instanceof SuCrateBlock crate ? crate.modelName() : "toffy_crate_raven";
    }

    public String texturePath()
    {
        return getBlockState().getBlock() instanceof SuCrateBlock crate
                ? crate.texturePath() : "block/crate/toffy_crate_raven";
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, CONTROLLER, 5, state -> state.setAndContinue(IDLE))
                .triggerableAnim(TRIGGER_OPEN, OPEN));
    }

    /** Play the open animation on every client that can see this crate. Server side; GeckoLib carries it over. */
    public void triggerOpen()
    {
        if (this.level != null && !this.level.isClientSide)
        {
            triggerAnim(CONTROLLER, TRIGGER_OPEN);
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }
}
