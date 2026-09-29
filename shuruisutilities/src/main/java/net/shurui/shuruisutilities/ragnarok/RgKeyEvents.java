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
                // Wait to hear what model pack this client already holds before sending any of it. NOT key gated,
                // unlike the picker above: a client that cannot draw a model it can already see is a render crash,
                // not a withheld feature, so everyone who needs the pack still gets it. They just do not get a
                // second copy of one they already have, which used to be pushed at every join and is heavy enough
                // to time a slow connection out mid transfer.
                RgNpcAssetServer.expectReport(player);
            }
        }
    }

    /** Client side: drop the previous server's answer, so the gate is shut again until the next one speaks. */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
    public static final class Client {

        private Client() {
        }

        /**
         * Tell the server what model pack we already hold, so it sends only what is missing (usually nothing).
         *
         * <p>Sent unprompted on join rather than in answer to a server offer, which saves a round trip and, more
         * usefully, means the server never has to hold a queue open waiting to be asked.
         */
        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            int[] have = net.shurui.shuruisutilities.ragnarok.client.RgNpcAssetCache.have();
            NetworkUtils.sendToServer(new PacketRgNpcAssetsHave(have[0], have[1]));
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            RgKeyClientState.clear();
        }
    }
}
