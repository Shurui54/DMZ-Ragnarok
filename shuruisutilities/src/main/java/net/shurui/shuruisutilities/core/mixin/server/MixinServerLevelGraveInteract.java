package net.shurui.shuruisutilities.core.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.grave.GraveAccess;

/**
 * Lets a right click OPEN a grave totem that stands inside the server.properties spawn-protection radius (or, in
 * principle, outside the world border), which vanilla otherwise forbids because ServerLevel#mayInteract returns false
 * there and every SU protection handler (region / guild / world-zone) sits downstream of that gate. A player who logs
 * out (or swaps character slots) holding dragon balls at spawn drops a ball totem others are meant to collect, so the
 * one interaction is exempted: a grave the player may open (their own grave, or any totem holding a dragon ball,
 * decided by GraveAccess#mayOpen, the same question every other handler asks) is let through.
 *
 * <p><b>Why a method level @Inject and NOT a call site @Redirect.</b> This used to be an @Redirect on the
 * ServerLevel#mayInteract call inside ServerGamePacketListenerImpl#handleUseItemOn. Lootr (lootr-forge) @Redirects
 * that SAME call site (LootrAllowInteractSpawnProtection). Two @Redirects on one call site cannot coexist: mixin skips
 * whichever applies second, and a skipped injector under the default require = 1 is a HARD BOOT CRASH. That took ow1
 * down on 2026-09-17 when a jar rename reordered mixin application and Lootr won. Dropping our require to 0 stopped the
 * crash but meant OUR injector was the one skipped on any server carrying Lootr, so the feature was silently dead there.
 * @WrapOperation would let both mods compose, but MixinExtras is not on the dev classpath (only present at runtime
 * because another mod bundles it), so that version failed to boot the dev server outright. A method level @Inject has
 * no such conflict: any number of mods can @Inject into one method. DO NOT convert this back to a call site redirect.
 *
 * <p><b>Scope: OPEN only, breaking stays protected.</b> ServerLevel#mayInteract is called from more than one place, and
 * a HEAD return here fires for every caller, not just the open path. The one caller that could destructively act on a
 * grave block is ServerPlayerGameMode#handleBlockBreakAction (START_DESTROY_BLOCK), because the grave's own oak fence /
 * player head is what a break targets and GraveEventHandler#onBreak lets the break proceed after cleanup. That break is
 * kept blocked at spawn by a companion guard in GraveEventHandler#onBreak, which re-checks spawn protection directly, so
 * this exemption never makes a totem breakable at spawn. The item paths (bucket / bottle / spawn egg) that also call
 * mayInteract are unreachable at a grave: handleUseItemOn fires Forge's RightClickBlock first and GraveEventHandler
 * cancels it for grave positions, so item.useOn never runs there. The remaining callers (armor stand and item frame
 * attacks, TNT / powder snow / campfire / dripstone / chorus / cauldron) all require a different block or entity to
 * occupy the exact grave position, which the grave block itself precludes. GraveAccess#mayOpen is the tight gate: it
 * returns true only at a position registered in GraveStorage (pos or pos.below), and only for the grave's owner or a
 * totem that holds a dragon ball, so no ordinary container or block at spawn is affected.
 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevelGraveInteract
{
    @Inject(method = "mayInteract", at = @At("HEAD"), cancellable = true)
    private void su$allowGraveTotemUnderSpawnProtection(Player player, BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        ServerLevel level = (ServerLevel) (Object) this;
        if (GraveAccess.mayOpen(player, level, pos))
            cir.setReturnValue(true);
    }
}
