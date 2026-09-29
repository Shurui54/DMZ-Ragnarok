package net.shurui.shuruisutilities.devtools.parity;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Fires the server-side parity dump once, after the server has fully started. Registered on the Forge bus under the
 * suite mod id. The handler no-ops immediately unless {@link ParityDump#enabled()} (dev environment plus the
 * {@code dmzr.paritydump} system property), so it is inert in every shipped jar.
 */
@Mod.EventBusSubscriber(modid = ParityDump.SUITE_MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ParityDumpEvents {

    private ParityDumpEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!ParityDump.enabled()) {
            return;
        }
        ParityDump.dumpServer(event.getServer());
        if (ParityDump.stopAfterDump()) {
            // Runs on the server thread; halt() requests a clean shutdown after the current tick.
            ParityDump.log("stopping server after parity dump (DMZR_PARITY_STOP set)");
            event.getServer().halt(false);
        }
    }
}
