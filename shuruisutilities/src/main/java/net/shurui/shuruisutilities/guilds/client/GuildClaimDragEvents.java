package net.shurui.shuruisutilities.guilds.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Shift plus right-click drag across a map screen to claim every chunk the rectangle covers.
 *
 * <p>Driven from Forge's screen events rather than from a mixin on the map's own mouse handling, and it names no
 * map type at all: it acts only on a screen that implements {@link MapCursorChunk}, which is added to Xaero's world
 * map by the compat mixin. With Xaero absent nothing implements it and these handlers do nothing.
 *
 * <p>Shift is what separates this from an ordinary right-click, which already opens the map's own menu with the
 * single-chunk claim in it. The press is cancelled ONLY while shift is held, so that menu is untouched otherwise.
 *
 * <p>The client decides nothing here. The rectangle goes to {@code /guild claimarea} and the server applies the same
 * guild, permission, claim-limit and bank checks it applies to a single claim, chunk by chunk.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GuildClaimDragEvents
{
    private GuildClaimDragEvents() {}

    /** Right mouse button, as GLFW numbers them. */
    private static final int RIGHT_BUTTON = 1;

    @SubscribeEvent
    public static void onPress(ScreenEvent.MouseButtonPressed.Pre event)
    {
        if (event.getButton() != RIGHT_BUTTON || !Screen.hasShiftDown())
            return;
        // Guild claiming is private (Ragnarok Key): a server that did not report guilds gets the map's own shift click.
        if (!net.shurui.dev.sdu.api.ClientGate.feature(net.shurui.shuruisutilities.api.key.GuildHooks.FEATURE_ID))
            return;
        if (!(event.getScreen() instanceof MapCursorChunk map))
            return;
        GuildClaimDrag.begin(map.su$cursorChunkX(), map.su$cursorChunkZ());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRelease(ScreenEvent.MouseButtonReleased.Pre event)
    {
        if (event.getButton() != RIGHT_BUTTON || !GuildClaimDrag.isDragging())
            return;
        if (!(event.getScreen() instanceof MapCursorChunk map))
        {
            // The map went away mid-drag; drop it rather than sending a rectangle whose far corner is unknown.
            GuildClaimDrag.cancel();
            return;
        }
        GuildClaimDrag.finish(map.su$cursorChunkX(), map.su$cursorChunkZ());
        event.setCanceled(true);
    }

    /** A drag that is still running when the screen closes is abandoned, never sent. */
    @SubscribeEvent
    public static void onClose(ScreenEvent.Closing event)
    {
        if (event.getScreen() instanceof MapCursorChunk)
            GuildClaimDrag.cancel();
    }
}
