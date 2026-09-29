package net.shurui.shuruisutilities.dragons;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block appearing to jump, without the block ever moving.
 *
 * <p>A {@link net.minecraft.world.entity.Display.BlockDisplay} is a pure render: the client draws the block model at
 * the entity's position and the server keeps no block state of its own. So a copy of the ground hovering slightly
 * above the real ground reads as the ground heaving, while the world underneath is untouched - no block updates, no
 * dropped items, no chance of losing a build to a quake that ended badly.
 *
 * <p>The copy sits directly over the real block, so there is never a hole under it: what is seen is the block lifting
 * clear of itself and dropping back.
 */
final class QuakeHop
{
    private QuakeHop() {}

    /** Spawn a hop over {@code ground}, rising {@code height} blocks and lasting {@code ticks}. */
    static void spawn(ServerLevel level, BlockPos ground, BlockState state, double height, int ticks)
    {
        try
        {
            // Built from NBT rather than with a setter: BlockDisplay.setBlockState is private, and this is the same
            // path /summon takes, so the block state is applied by the entity's own load.
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "minecraft:block_display");
            tag.put("block_state", NbtUtils.writeBlockState(state));
            // LIT FROM THE AIR ABOVE, NOT FROM WHERE IT STANDS. A display with no brightness override samples the
            // light at its own position, and its position is INSIDE the solid block it is copying, where the light
            // level is zero - so every hopping block came out almost pure black. Baking in the light of the open
            // block above is what makes it match the ground it left.
            BlockPos lit = ground.above();
            CompoundTag brightness = new CompoundTag();
            brightness.putInt("block", level.getBrightness(LightLayer.BLOCK, lit));
            brightness.putInt("sky", level.getBrightness(LightLayer.SKY, lit));
            tag.put("brightness", brightness);
            Entity display = EntityType.loadEntityRecursive(tag, level, entity ->
            {
                entity.moveTo(ground.getX(), ground.getY(), ground.getZ(), 0.0f, 0.0f);
                return entity;
            });
            if (display == null)
                return;
            level.addFreshEntity(display);
            DragonEffectTicker.add(new Hop(display, ground, height, ticks));
        }
        catch (Throwable ignored)
        {
            // A display entity is decoration; losing it must never cost the quake its damage.
        }
    }

    /** Drives one hop up and back down, then removes the display. */
    private static final class Hop implements DragonEffectTicker.ActiveEffect
    {
        private final Entity display;
        private final BlockPos ground;
        private final double height;
        private final int ticks;
        private int age;

        Hop(Entity display, BlockPos ground, double height, int ticks)
        {
            this.display = display;
            this.ground = ground;
            this.height = height;
            this.ticks = Math.max(2, ticks);
        }

        @Override
        public boolean tick()
        {
            age++;
            if (age >= ticks || !display.isAlive())
            {
                display.discard();
                return false;
            }
            // A single arc up and back: sin over the life, so it leaves the ground and lands on it.
            double lift = Math.sin(Math.PI * age / (double) ticks) * height;
            display.setPos(ground.getX(), ground.getY() + lift, ground.getZ());
            return true;
        }

        @Override
        public void cancel()
        {
            display.discard();
        }
    }
}
