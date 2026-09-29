package net.shurui.shuruisutilities.space.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.BlockGetter;

import net.shurui.shuruisutilities.client.space.PlanetClashCamera;

import com.dragonminez.client.clash.BeamClashCinematicCamera;

/**
 * Reframes DragonMineZ's clash cinematic for OUR planet clash, and only for that.
 *
 * <p>DragonMineZ already runs a third-person cinematic for our struggle (its {@code CameraMixin} applies
 * {@code BeamClashCinematicCamera.computeShot} whenever the cinematic is active, which it is because our attacker is a
 * real {@code ServerPlayer}). The one defect is FRAMING: {@code computeShot} frames the local player against the OPPONENT
 * OWNER, which for us is the invisible {@code PlanetDefenderEntity} 50..300 blocks out at the clash point, so it either
 * frames the player (opponent untracked) or empty space. Rather than run a second camera and fight DragonMineZ every
 * frame, we HIJACK its own {@code computeShot} at the HEAD: for a planet clash we return our shot (of the clash point);
 * for anything else we do nothing and DragonMineZ's normal shot runs untouched. There is exactly one camera write per
 * frame either way, so nothing fights.</p>
 *
 * <p>Enter/exit are entirely DragonMineZ's: it activates and deactivates the cinematic off its own packet, so
 * {@code computeShot} is only ever called while a clash is live. The instant the clash resolves, the ball is destroyed,
 * the player dies or they change dimension, DragonMineZ deactivates and this injection is never called again. Combined
 * with {@code PlanetClashCamera}'s DragonMineZ-active tie and null-anchor fallback, the camera can never be stranded.</p>
 *
 * <h2>Why the handler takes NO target parameters</h2>
 * {@code computeShot(BlockGetter, LocalPlayer, float)} is DragonMineZ's own method, so it must be targeted with
 * {@code remap = false} (its name is not in the Minecraft mappings). But its parameters are MINECRAFT types, and a
 * {@code remap = false} handler that declared them would keep its Mojmap descriptor at runtime and fail to bind against
 * the production (SRG) DragonMineZ class, silently disabling the reframe in prod while it worked in dev. To avoid that
 * trap entirely the handler declares ONLY the {@link CallbackInfoReturnable} (Mixin allows omitting the target args) so
 * there are no Minecraft types in its descriptor, and it fetches the render context from {@link Minecraft} itself.
 *
 * <p>{@code require = 0}: if DragonMineZ renames or reshapes {@code computeShot} in a future version this injection just
 * does not bind and the reframe silently no-ops, degrading to DragonMineZ's own camera rather than crashing. The body is
 * additionally wrapped so no throwable can ever reach the render thread.</p>
 */
@Mixin(targets = "com.dragonminez.client.clash.BeamClashCinematicCamera", remap = false)
public abstract class MixinDmzClashCamera
{
    @Inject(method = "computeShot", at = @At("HEAD"), cancellable = true, require = 0)
    private static void su$reframePlanetClash(CallbackInfoReturnable<BeamClashCinematicCamera.Shot> cir)
    {
        try
        {
            if (!PlanetClashCamera.isPlanetClash())
            {
                // an ordinary player-vs-player clash: leave DragonMineZ's own framing entirely alone.
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null)
            {
                return;
            }
            // the same render context DragonMineZ's computeShot is called with: the local player, its level, and the
            // frame partial tick. Fetching them here (rather than as target parameters) keeps Minecraft types out of the
            // handler descriptor, so the remap=false binding is safe in production. See the class note.
            BlockGetter level = player.level();
            float partialTick = mc.getFrameTime();
            PlanetClashCamera.Shot shot = PlanetClashCamera.computeShot(level, player, partialTick);
            if (shot == null)
            {
                // we declined (anchor gone, or a maths failure): let DragonMineZ's own shot stand rather than a broken one.
                return;
            }
            cir.setReturnValue(new BeamClashCinematicCamera.Shot(shot.pos(), shot.yaw(), shot.pitch()));
        }
        catch (Throwable ignored)
        {
            // never let the reframe break DragonMineZ's camera: on any error fall through to its own computeShot body.
        }
    }
}
