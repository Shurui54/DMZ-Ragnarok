package net.shurui.shuruisutilities.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Puts the Kinetic Hosting banner on the three screens we do NOT own: Minecraft's own title screen, the
 * singleplayer world list and the multiplayer server list.
 *
 * <p>Our Ragnarok menu draws it itself. These three are vanilla, so they are reached through Forge screen events
 * rather than a mixin, matching {@link MainMenuClientEvents} and
 * {@link net.shurui.dev.sdu.client.MultiplayerScreenClientEvents}. The
 * vanilla title screen still matters even though we normally replace it: the replacement is behind a config
 * ({@code SUConfig.customMainMenu}), so with it off the vanilla screen is what players see.</p>
 *
 * <p>Client-only, and the whole class is {@link Dist#CLIENT} so a dedicated server never classloads it.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KineticBannerClientEvents
{
    private KineticBannerClientEvents() {}

    /**
     * Whether this screen should carry the banner.
     *
     * <p>{@code instanceof} rather than {@code getClass() ==} here, unlike the title-screen REPLACEMENT next
     * door: replacing a screen has to be exact so another mod's TitleScreen subclass is never hijacked, but
     * merely drawing a banner on top of one is harmless and should still happen if a subclass shows up.</p>
     */
    private static boolean wants(Screen screen)
    {
        return screen instanceof TitleScreen
                || screen instanceof JoinMultiplayerScreen
                || screen instanceof SelectWorldScreen;
    }

    /**
     * Which corner the banner takes on this screen. Kept right next to {@link #wants(Screen)} on purpose: the
     * two answers ("does this screen get a banner" and "where") have to agree, and both the draw and the click
     * test read this same method, so they cannot end up testing a different rectangle than the one drawn.
     *
     * <p>The title screen keeps it TOP RIGHT, matching our own Ragnarok menu, since there is nothing important
     * up there. The world list and the server list get BOTTOM RIGHT: top-right sat over the first list row and
     * hid its player count and status, which is the content those screens are for.</p>
     */
    private static KineticBanner.Anchor anchorFor(Screen screen)
    {
        if (screen instanceof JoinMultiplayerScreen || screen instanceof SelectWorldScreen)
        {
            return KineticBanner.Anchor.BOTTOM_RIGHT;
        }
        return KineticBanner.Anchor.TOP_RIGHT;
    }

    /**
     * Drawn in Render.POST so it lands on top of the screen's own widgets rather than under them. On the
     * multiplayer list that matters: the server entries are drawn in the same band and would otherwise cover it.
     */
    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event)
    {
        Screen screen = event.getScreen();
        if (wants(screen))
        {
            KineticBanner.render(event.getGuiGraphics(), screen.width, screen.height,
                    event.getMouseX(), event.getMouseY(), anchorFor(screen));
        }
    }

    /**
     * Claim clicks that land on the banner BEFORE the screen underneath sees them, so a click on the banner
     * cannot also press whatever vanilla widget happens to sit behind it.
     */
    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre event)
    {
        Screen screen = event.getScreen();
        if (event.getButton() != 0 || !wants(screen))
        {
            return;
        }
        if (KineticBanner.click(screen, event.getMouseX(), event.getMouseY(),
                screen.width, screen.height, anchorFor(screen)))
        {
            event.setCanceled(true);
        }
    }
}
