package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

import net.shurui.shuruisutilities.client.gui.task.XaeroMinimapAnchor;

/**
 * Captures where Xaero's Minimap draws its coordinate/info block each frame, so SU's quest tracker banner can be
 * anchored directly under it. Reads the public {@code InfoDisplayRenderer.render}, which is the method that draws
 * the info lines UNDER the minimap, and stores its geometry in {@link XaeroMinimapAnchor}.
 *
 * <h2>No compile dependency on Xaero</h2>
 * There is no Gradle dependency on Xaero for this hook, so it is a STRING-form target ({@code @Mixin(targets =
 * "xaero...")}) exactly like {@code MixinDmzStoryToast} targets DMZ. The two Xaero-typed parameters
 * ({@code MinimapSession}, {@code Minimap}) are captured as {@code @Coerce Object} so nothing here imports or
 * classloads an Xaero type; they are not read, only present so the handler's descriptor matches the target. This
 * mirrors {@code MixinDmzAuraRenderer}, which captures DMZ inner types the same way.
 *
 * <h2>Degrades, never crashes</h2>
 * {@code require = 0}: Xaero updates often (this is 26.x), so if a later version reshapes {@code render} the
 * injector simply does not bind, no capture lands, {@link XaeroMinimapAnchor} expires, and the tracker falls back
 * to its configured default anchor. The whole body is wrapped so a capture fault can never fault Xaero's own
 * render pass.
 *
 * <h2>What the parameters mean</h2>
 * From Xaero 26.1.0's {@code InfoDisplayRenderer.render}: {@code scaledX}/{@code scaledY} are the minimap's
 * top-left corner in scaled screen pixels, {@code size} is the (square) on-screen map size, {@code height} is the
 * screen height and {@code mapScale} the GUI-space scale. Xaero itself computes
 * {@code under = scaledY + size/2 < (int)(height*mapScale)/2} to decide whether the info lines sit UNDER the map,
 * and when under it starts them at {@code scaledY + size}, stepping down 10px per line. We replicate exactly that,
 * so the stored top is the true first-info-line Y.
 */
@Mixin(targets = "xaero.hud.minimap.info.render.InfoDisplayRenderer", remap = false)
public abstract class MixinXaeroInfoDisplayRenderer
{
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void su$captureAnchor(GuiGraphics guiGraphics, @Coerce Object session, @Coerce Object minimap,
                                  int height, int size, BlockPos playerPos, int scaledX, int scaledY,
                                  float mapScale, MultiBufferSource.BufferSource renderTypeBuffer, CallbackInfo ci)
    {
        try
        {
            int scaledHeight = (int) ((float) height * mapScale);
            boolean under = scaledY + size / 2 < scaledHeight / 2;
            int coordTop = under ? scaledY + size : scaledY;
            // scaledX/size/coordTop are in Xaero's SCALED space; mapScale lets the reader convert them to GUI space.
            XaeroMinimapAnchor.capture(scaledX, size, coordTop, under, mapScale);
        }
        catch (Throwable ignored)
        {
            // Never let an anchor capture disturb Xaero's own render.
        }
    }
}
