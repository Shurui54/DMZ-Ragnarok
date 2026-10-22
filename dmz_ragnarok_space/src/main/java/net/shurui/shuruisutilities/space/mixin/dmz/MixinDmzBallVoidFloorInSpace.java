package net.shurui.shuruisutilities.space.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.level.ServerLevel;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * DragonMineZ 2.2 treats a dragon ball item that is DISCARDED more than 64 blocks under the build floor as fallen into
 * the void and respawns the ball. DISCARDED also covers an item being picked up or merged, which is harmless while the
 * test only matches the void. In space the block column is -64..320 but players fly across the whole layout band, so
 * picking up a ball at Y -500 would read as a void loss and duplicate it. In space the floor this check reads is the
 * layout floor instead (MixinSpaceNoVoidKill puts the real void just under it). Every other dimension is untouched.
 * {@code require = 0}: if DMZ changes the handler this silently stops applying.
 */
@Mixin(targets = "com.dragonminez.server.events.DragonBallsHandler", remap = false)
public abstract class MixinDmzBallVoidFloorInSpace
{
    @Redirect(
        method = "onBallItemLeaveLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getMinBuildHeight()I",
            remap = true
        ),
        require = 0,
        remap = false
    )
    private static int dmzr$spaceBallFloor(ServerLevel level)
    {
        if (SpaceKeys.isSpace(level))
        {
            return (int) SpaceKeys.LAYOUT_MIN_Y;
        }
        return level.getMinBuildHeight();
    }
}
