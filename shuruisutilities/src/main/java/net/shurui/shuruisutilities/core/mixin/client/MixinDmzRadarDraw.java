package net.shurui.shuruisutilities.core.mixin.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.client.hud.RadarBackgrounds;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Replaces DragonMineZ's dragon-radar HUD DRAW with ours inside SU's space and planet-surface dimensions, leaving it
 * untouched everywhere else.
 *
 * <p>This class holds ONLY the dial-background redirect ({@link #su$swapRadarBackground}), which depends solely on
 * core's {@link RadarBackgrounds} and stays in core. The Space-dependent HEAD inject that replaced DMZ's whole draw
 * with {@code SuRadarHud} inside SU's space / planet-surface dimensions now lives in
 * {@code net.shurui.shuruisutilities.space.mixin.client.MixinDmzSpaceRadarDraw}, so the Space code leaves core. The two
 * mixins target the same {@code RadarRenderEvent} and never collide: the Space inject cancels the whole method before
 * this redirect's blit ever runs, but only in SU's space / planet-surface dimensions; in every ordinary dimension
 * (Earth, Namek) that inject does nothing and this redirect runs, so their order is immaterial.</p>
 *
 * <p>This touches only the HUD OVERLAY. The radar ITEM's model, textures and held/use animations are unrelated (they are
 * item rendering, not this GUI event), so they are left completely alone.</p>
 *
 * <p>{@link #su$swapRadarBackground} redirects DMZ's hardcoded dial-background blit so the Super and Cerulean radars
 * show their own dial in DMZ's OWN draw path (overworld, Namek). DMZ ignores each radar's
 * {@code radar_background_texture} field and blits a fixed texture, so the background can only be swapped here at the
 * blit itself. The set-to-background mapping lives in {@link RadarBackgrounds}, which SU's own {@code SuRadarHud} calls
 * too, so both draw paths pick the same dial.</p>
 *
 * <p>{@code remap = false}: the target class and {@code renderRadar} are DragonMineZ's, not Mojmap. {@code require = 0}:
 * if DMZ reshapes or renames the method this degrades to a no-op and DMZ keeps its original draw, rather than crashing the
 * client. The handler is wrapped so a fault in our detection can never break DMZ's render pass either.</p>
 */
@Mixin(targets = "com.dragonminez.client.events.RadarRenderEvent", remap = false)
public abstract class MixinDmzRadarDraw
{
    // Latched one-shot so the background redirect logs its runtime bind exactly once, never once per frame.
    private static final AtomicBoolean SU_RADAR_BG_BIND_LOGGED = new AtomicBoolean(false);

    // Redirect the FIRST gui.blit in renderRadar (ordinal 0), which is DMZ's hardcoded dial-background blit
    // (gui.blit(RADAR_TEXTURE, centerX, centerY, 0, 0, 121, 146)); the two later blits in this method draw the dot and
    // the rim arrow. method = the DMZ-only name stays literal (remap = false); the @At is remap = true so
    // GuiGraphics.blit is refmapped to its SRG name against the obfuscated production jar. Trailing params capture
    // renderRadar's own args so we can read the held radar. require = 0 keeps a missed target silent (a DMZ reshape
    // degrades to its own dial) rather than failing the SU mixin config and crashing clients, so the bind log is the
    // only proof it wove. Only in DMZ's own draw path (overworld, Namek); in SU dimensions the HEAD inject cancels first.
    @Redirect(
            method = "renderRadar",
            require = 0,
            remap = false,
            at = @At(
                    value = "INVOKE",
                    ordinal = 0,
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V",
                    remap = true))
    private static void su$swapRadarBackground(GuiGraphics instance, ResourceLocation texture, int x, int y, int u,
            int v, int w, int h, GuiGraphics gui, Player player, List<BlockPos> targets, int range, int centerX,
            int centerY, boolean showProximity)
    {
        if (SU_RADAR_BG_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info(
                        "[Radar] MixinDmzRadarDraw background redirect bound (renderRadar GuiGraphics.blit ordinal 0)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            ResourceLocation custom = RadarBackgrounds.forHeldRadar(player);
            instance.blit(custom != null ? custom : texture, x, y, u, v, w, h);
        }
        catch (Throwable t)
        {
            // cosmetic only: any failure leaves DMZ's own dial texture rather than breaking the overlay.
            instance.blit(texture, x, y, u, v, w, h);
        }
    }
}
