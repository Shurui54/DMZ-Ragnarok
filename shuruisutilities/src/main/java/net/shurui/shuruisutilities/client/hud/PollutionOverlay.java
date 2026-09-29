package net.shurui.shuruisutilities.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.dragons.DragonMoveHaze;
import net.shurui.shuruisutilities.dragons.PollutedEffect;

/**
 * Tints the screen while the viewing player is inside an area effect: purple for Haze Shenron's gas, orange for
 * Nuova Shenron's heat.
 *
 * <p>Driven off the {@link PollutedEffect} status effect, which vanilla already syncs, so no packet and no way to
 * disagree with the server. Drawn over the hotbar layer, not a post-processing pass, so it costs nothing and cannot
 * touch anyone's shaders. Alpha is low on purpose: loss of sight is blindness's job.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PollutionOverlay
{
    private PollutionOverlay() {}

    /** Alpha of the tint, 0..255. Atmospheric, not blinding. */
    private static final int ALPHA = 0x66;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event)
    {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type())
            return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui)
            return;

        // Haze's purple gas and Nuova's orange heat share this one pass, each a single quad.
        Integer rgb = null;
        if (PollutedEffect.has(player))
            rgb = DragonMoveHaze.CLOUD_RGB;
        else if (net.shurui.shuruisutilities.dragons.ScorchedEffect.has(player))
            rgb = net.shurui.shuruisutilities.dragons.DragonMoveNuova.HEAT_RGB;
        if (rgb == null)
            return;

        GuiGraphics g = event.getGuiGraphics();
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (ALPHA << 24) | (rgb & 0xFFFFFF));
    }
}
