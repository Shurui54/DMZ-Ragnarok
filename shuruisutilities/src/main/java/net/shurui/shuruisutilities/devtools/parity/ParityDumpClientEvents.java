package net.shurui.shuruisutilities.devtools.parity;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Fires the client-side parity dump once, when the title screen first initialises. Client-only ({@code Dist.CLIENT}),
 * registered on the Forge bus under the suite mod id, and inert unless {@link ParityDump#enabled()}. A dedicated
 * server never loads this class.
 */
@Mod.EventBusSubscriber(modid = ParityDump.SUITE_MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ParityDumpClientEvents {

    private ParityDumpClientEvents() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!ParityDump.enabled()) {
            return;
        }
        if (event.getScreen() instanceof TitleScreen) {
            ParityDump.dumpClient();
        }
    }
}
