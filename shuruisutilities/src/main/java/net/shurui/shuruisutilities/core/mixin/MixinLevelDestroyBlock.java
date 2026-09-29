package net.shurui.shuruisutilities.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.protection.BlockBreakGuard;

/**
 * Makes block protection apply to blocks destroyed DIRECTLY, without a Forge break event.
 *
 * <h2>The hole this closes</h2>
 * SU enforces region flags and guild claims from {@code BlockEvent.BreakEvent}. A mod that calls
 * {@code Level.destroyBlock} itself never raises that event, so every one of those handlers is skipped and the block
 * breaks whoever owns the land. Tinkers' slime staffs do exactly this, which is how a staff could mine straight
 * through a claim; DMZ's ki grief was the same bug through a different door
 * ({@code MixinDmzKiGrief}).
 *
 * <p>Patching each offending mod separately does not converge - the next tool that direct-destroys reopens it. This
 * guards the destroy call itself, so ANY caller is covered, including mods not installed yet and ones we have no
 * compile dependency on.
 *
 * <h2>Why this does not double-enforce normal mining</h2>
 * Ordinary mining fires the break event first, and only calls through here once the handlers have allowed it. This
 * check then asks the SAME systems the same question and necessarily reaches the same answer, so an allowed break
 * stays allowed. The cost is one redundant lookup on a path that already early-outs unless a player is responsible.
 *
 * <h2>Failure direction</h2>
 * {@link BlockBreakGuard} answers "allowed" on any internal error, so a protection lookup that throws degrades to
 * vanilla behaviour rather than making the world unbreakable.
 *
 * <p>Vanilla target, so it remaps normally and keeps the config's default require: {@code Level.destroyBlock} is not
 * going to move underneath us.
 */
@Mixin(Level.class)
public abstract class MixinLevelDestroyBlock
{
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void su$protectClaimedBlocks(BlockPos pos, boolean dropBlock, Entity breaker, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir)
    {
        Level self = (Level) (Object) this;
        if (!BlockBreakGuard.mayBreak(self, pos, breaker))
        {
            // false = "nothing was destroyed", which is what a caller expects when the block survives.
            cir.setReturnValue(false);
        }
    }
}
