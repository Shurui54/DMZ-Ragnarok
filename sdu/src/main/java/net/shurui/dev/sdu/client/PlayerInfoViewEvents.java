package net.shurui.dev.sdu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Client-only lifecycle for the {@code /dmzinfo} read-only viewer:
 * <ul>
 *   <li>clears viewing mode the moment the staff member leaves DMZ's character-menu screens (so it survives
 *       the tab switches between the six V-menu screens, which are all in DMZ's {@code ...gui.character}
 *       package, but ends on close or on opening anything else), and</li>
 *   <li>draws an unobtrusive "Viewing: &lt;name&gt;" label on those screens.</li>
 * </ul>
 * Client-only via {@code @Mod.EventBusSubscriber(Dist.CLIENT)}, so nothing here classloads on a dedicated
 * server.
 */
@Mod.EventBusSubscriber(modid = DmzNpc.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerInfoViewEvents {

    /** DMZ's V-menu screens (all six tabs and their sub-screens) live under this package prefix. */
    private static final String DMZ_MENU_PACKAGE = "com.dragonminez.client.gui.character.";

    private PlayerInfoViewEvents() {
    }

    private static boolean isDmzMenu(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith(DMZ_MENU_PACKAGE);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !PlayerInfoView.active()) {
            return;
        }
        // The viewer stays open across tab switches (each switch is a new screen in DMZ's character package);
        // it ends as soon as the current screen is null or anything outside that package.
        if (!isDmzMenu(Minecraft.getInstance().screen)) {
            PlayerInfoView.clear();
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!PlayerInfoView.active() || !isDmzMenu(event.getScreen())) {
            return;
        }
        try {
            GuiGraphics graphics = event.getGuiGraphics();
            Minecraft mc = Minecraft.getInstance();
            Component label = Component.translatable("gui.dmz_ragnarok.dmzinfo.viewing",
                    PlayerInfoView.targetName());
            // Top-left gutter, drawn last so it sits above the menu chrome. Name only: no explanatory subtitle.
            graphics.drawString(mc.font, label, 6, 6, 0xFFF0B000, true);
        } catch (Throwable ignored) {
            // never break DMZ's screen render over a label
        }
    }
}
