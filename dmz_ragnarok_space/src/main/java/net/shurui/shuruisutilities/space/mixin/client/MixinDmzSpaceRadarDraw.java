package net.shurui.shuruisutilities.space.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.client.space.SuRadarHud;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * The Space half of DragonMineZ's dragon-radar HUD interception, split out of core's {@code MixinDmzRadarDraw} so the
 * Space-dependent draw lives in Space's own package (and, from batch B on, in Space's own module). Inside SU's space
 * and planet-surface dimensions this replaces DMZ's flat radar draw with {@link SuRadarHud}; everywhere else it does
 * nothing and DMZ draws exactly as before.
 *
 * <p>DMZ's {@code RadarRenderEvent.renderRadar} draws every blip from a flat {@code dx = pos.getX() - player.getX()}
 * with no dimension or planet awareness, which is wrong for balls that live on the shared {@code planet_surface}
 * dimension (cells 65,536 blocks apart) or must be pointed at across space (see {@link SuRadarHud}). The companion
 * {@code MixinDmzRadarRender} already widens DMZ's early return so the method is REACHED in our dimensions with the
 * set's ball positions, range and dial corner correctly resolved; here we intercept the draw itself: when the player
 * is in space or on a planet surface we hand those same arguments to {@link SuRadarHud} and cancel DMZ's flat draw.
 *
 * <h2>Relationship to core's {@code MixinDmzRadarDraw} on the same target</h2>
 * Core keeps a second mixin on {@code RadarRenderEvent}: the background-blit redirect that swaps the Super / Cerulean
 * dial in DMZ's OWN draw path (overworld, Namek), which depends only on core's {@code RadarBackgrounds}, not on Space.
 * The two never collide and their order is immaterial: this HEAD inject cancels the whole method BEFORE its body (and
 * thus before the redirected blit) ever runs, but only in SU's space / planet-surface dimensions; in every ordinary
 * dimension this inject does nothing and DMZ's body runs with the core redirect applied. So whichever mixin is applied
 * first, the observable result is unchanged, and no priority pin is needed here.
 *
 * <p>This touches only the HUD OVERLAY. The radar ITEM's model, textures and held/use animations are unrelated (they
 * are item rendering, not this GUI event), so they are left completely alone.
 *
 * <p>{@code remap = false}: the target class and {@code renderRadar} are DragonMineZ's, not Mojmap. {@code require = 0}:
 * if DMZ reshapes or renames the method this degrades to a no-op and DMZ keeps its original draw, rather than crashing
 * the client. The handler is wrapped so a fault in our detection can never break DMZ's render pass either.
 */
@Mixin(targets = "com.dragonminez.client.events.RadarRenderEvent", remap = false)
public abstract class MixinDmzSpaceRadarDraw
{
    @Inject(method = "renderRadar", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$drawPlanetAwareRadar(GuiGraphics gui, Player player, List<BlockPos> targets, int range,
            int centerX, int centerY, boolean showProximity, CallbackInfo ci)
    {
        try
        {
            Level level = player.level();
            if (SpaceKeys.isSpace(level) || SpaceKeys.isSurface(level))
            {
                SuRadarHud.render(gui, player, targets, range, centerX, centerY);
                ci.cancel();
            }
        }
        catch (Throwable ignored)
        {
            // never let our interception break DMZ's overlay; on any error fall through to DMZ's original draw.
        }
    }
}
