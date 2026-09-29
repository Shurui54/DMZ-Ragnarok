package net.shurui.shuruisutilities.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.shurui.shuruisutilities.client.gui.RagnarokMainMenuScreen;
import net.shurui.shuruisutilities.core.SUConfig;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only installer for {@link RagnarokMainMenuScreen}. Swaps the custom Ragnarok main menu in for Minecraft's
 * vanilla title screen using a Forge screen event (no mixin, matching the precedent in
 * {@link net.shurui.dev.sdu.client.MultiplayerScreenClientEvents}). The whole class is guarded to
 * {@link Dist#CLIENT} so a dedicated server
 * never classloads it or the screen it references.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MainMenuClientEvents {

    private MainMenuClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!SUConfig.customMainMenu) {
            return;
        }
        Screen next = event.getNewScreen();
        // Match the vanilla TitleScreen EXACTLY. Using getClass() == rather than instanceof means we never hijack a
        // TitleScreen subclass another mod may swap in, and our own RagnarokMainMenuScreen (not a TitleScreen) never
        // gets re-swapped, so there is no recursion.
        if (next == null || next.getClass() != TitleScreen.class) {
            return;
        }
        // Defer to vanilla when mods failed to load. Forge routes a broken/warning load to its own loading-issues
        // screen off the title screen; replacing the title screen here would strand the user with no way to see why
        // something broke. isLoadingStateValid() is false on a hard load failure, and a non-empty warning list is
        // the same condition Forge itself uses to raise its loading-error screen. Either one means: keep vanilla.
        if (!ModLoader.isLoadingStateValid() || !ModLoader.get().getWarnings().isEmpty()) {
            return;
        }
        event.setNewScreen(new RagnarokMainMenuScreen());
    }
}
