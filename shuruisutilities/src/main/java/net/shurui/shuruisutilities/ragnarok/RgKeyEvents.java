package net.shurui.shuruisutilities.ragnarok;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Keeps the client's copy of the server key state ({@link RgKeyClientState}) honest: the server states it at
 * login, and the client forgets it on disconnect so one server's answer never applies to the next.
 *
 * <p>Only the ragnarok model picker reads it. Everything the key actually protects is still enforced where it
 * was, server-side.
 */
public final class RgKeyEvents {

    private RgKeyEvents() {
    }

    /** Server side: state the key status to each player as they join. */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok")
    public static final class Server {

        private Server() {
        }

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                // The same key flag DmzNet sends: the key installed its core hooks, not merely marked itself present.
                NetworkUtils.sendTo(new PacketRgKeySync(net.shurui.dev.sdu.network.DmzNet.clientKeyPresent()), player);
                // The rgnpc model pack is no longer streamed (it ships in the jar again as of September 2026), so
                // there is no join handshake to start here. Packet ids 93/97 remain registered as inert holes.
            }
        }
    }

    /** Client side: drop the previous server's answer, so the gate is shut again until the next one speaks. */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
    public static final class Client {

        private Client() {
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            RgKeyClientState.clear();
        }
    }
}
