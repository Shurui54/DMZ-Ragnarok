package net.shurui.shuruisutilities.senzu;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Backs a planted typed bean pot. Holds nothing but an int growth counter; the visible stage lives in the block's
 * {@code age} blockstate (0..3), which is what the multipart model reads. The counter is advanced ONLY by the
 * server ticker in {@link TypedBeanPotBlock}, never by vanilla random ticks, which is what makes the crop immune to
 * AE2 growth accelerators (they issue extra random ticks; we ignore random ticks entirely).
 *
 * <p>Growth pacing is a config value ({@link SenzuModule#growthTicks()}, default 287000 ticks ~ 4 hours real time, the
 * user's "10x wheat" target), split evenly across the three stage advances (0->1, 1->2, 2->3), so a full grow is the
 * configured total.
 */
public class BeanPotBlockEntity extends BlockEntity
{
    private int counter;

    public BeanPotBlockEntity(BlockPos pos, BlockState state)
    {
        super(SenzuRegistry.BEAN_POT_BE.get(), pos, state);
    }

    // one growth tick. Increments the counter and, once it crosses the per-stage threshold, advances the block's age
    // by one and resets the counter. Only ever called by the server ticker when the pot is planted and below age 3, so
    // there is no early-out to repeat here; the ticker owns the cheap "should we tick at all" gate.
    public static void serverTick(Level level, BlockPos pos, BlockState state, BeanPotBlockEntity be)
    {
        be.counter++;
        int perStage = Math.max(1, SenzuModule.growthTicks() / 3);
        if (be.counter >= perStage)
        {
            be.counter = 0;
            TypedBeanPotBlock.advanceAge(level, pos, state);
        }
        be.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        tag.putInt("Counter", counter);
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        counter = tag.getInt("Counter");
    }

    // reset the growth counter, used when a pot is (re)planted or harvested so a fresh crop starts from zero.
    public void resetCounter()
    {
        counter = 0;
        setChanged();
    }
}
