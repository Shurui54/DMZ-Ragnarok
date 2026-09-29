package net.shurui.dev.sdu.passive;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers the custom class passives once the server is up.
 *
 * <p>{@link ServerStartedEvent} rather than anything earlier, for the same reason the dragon-ball rescatter uses
 * it: the race configs have to be readable from disk, and DMZ's own registry has to have finished its static
 * registration, before a scan means anything. Registering earlier would either read no races or race DMZ's own
 * static block.
 *
 * <p>The other entry point is a race SAVE (see SaveRacePacket): a class added in the editor gets its passive
 * without a restart, which is the difference between the feature being usable and being a redeploy each time.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class SduClassPassiveEvents {

    private SduClassPassiveEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        SduClassPassives.refresh();
    }
}
