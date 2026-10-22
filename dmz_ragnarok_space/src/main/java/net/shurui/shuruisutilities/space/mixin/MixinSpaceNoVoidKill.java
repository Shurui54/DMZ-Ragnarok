package net.shurui.shuruisutilities.space.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Moves the void floor in the space dimension from the block column to the layout band.
 *
 * <p>The space dimension's block column is a normal -64..320 one, but its layout (suns, planets, black holes) spans
 * {@link SpaceKeys#LAYOUT_MIN_Y}..{@link SpaceKeys#LAYOUT_MAX_Y} and players and pods fly all through it. Vanilla's
 * {@code checkBelowWorld} calls {@code onBelowWorld} (void damage for living things, discard for the rest) once an
 * entity is 64 blocks under the build floor, which would kill everything below Y -128 in space.
 *
 * <p>In space the floor is instead 64 blocks under the layout band, so loose entities (items, mobs, an empty pod) are
 * cleaned up exactly as they always were below the old tall column. A player, or anything carrying one (a piloted pod),
 * is left to the descend-out path in SpaceTravelModule (return altitude, then its safety floor) and only falls back to
 * vanilla's void far below that, so a broken descend-out can never leave someone falling forever. Every other
 * dimension is untouched.
 */
@Mixin(Entity.class)
public abstract class MixinSpaceNoVoidKill
{
    // how far under the layout floor a player (or their vehicle) may fall before vanilla's void applies after all.
    // SpaceDimension.SAFETY_FLOOR_Y (512 under the floor) is where the descend-out is forced; this is 512 further.
    @Unique
    private static final double DMZR_PLAYER_VOID_DEPTH = 1024.0D;

    @Shadow
    public abstract Level level();

    @Shadow
    public abstract double getY();

    @Shadow
    protected abstract void onBelowWorld();

    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void dmzr$spaceVoidFloor(CallbackInfo ci)
    {
        Level level = this.level();
        if (!SpaceKeys.isSpace(level))
        {
            return;
        }
        ci.cancel();
        double y = this.getY();
        if (y >= SpaceKeys.LAYOUT_MIN_Y - 64.0D)
        {
            return;
        }
        if (dmzr$carriesPlayer((Entity) (Object) this) && y >= SpaceKeys.LAYOUT_MIN_Y - DMZR_PLAYER_VOID_DEPTH)
        {
            return;
        }
        this.onBelowWorld();
    }

    @Unique
    private static boolean dmzr$carriesPlayer(Entity self)
    {
        if (self instanceof Player)
        {
            return true;
        }
        for (Entity passenger : self.getIndirectPassengers())
        {
            if (passenger instanceof Player)
            {
                return true;
            }
        }
        return false;
    }
}
