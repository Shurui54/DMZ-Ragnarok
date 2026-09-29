package net.shurui.shuruisutilities.commons.network;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkUtils
{
    private static final String PROTOCOL_VERSION = "SU1";

    public static final Logger sunetworklog = LogManager.getLogger("SUnetwork");

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("dmz_ragnarok", "fe-network"), () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION), NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));

    public static void init()
    {
    }

    private static Set<String> registeredMessages = new HashSet<>();

    // client -> server packet
    public static <MSG extends ISUPacket> void registerClientToServer(int index, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler)
    {
        registerMessage(index, type, encoder, decoder, handler, NetworkDirection.PLAY_TO_SERVER);
    }

    // server -> client packet
    public static <MSG extends ISUPacket> void registerServerToClient(int index, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler)
    {
        registerMessage(index, type, encoder, decoder, handler, NetworkDirection.PLAY_TO_CLIENT);
    }

    // both directions on ONE id: the packet itself says which half it is, and its handler must check the reception
    // side (a client can send anything, so a server-bound copy of a client-bound half is ignored, never trusted)
    public static <MSG extends ISUPacket> void registerBothWays(int index, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler)
    {
        registerMessage(index, type, encoder, decoder, handler, null);
    }

    // internal, do not call. A null direction registers the message for both directions.
    private static <MSG extends ISUPacket> void registerMessage(int index, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler, NetworkDirection networkDirection)
    {
        String dirName = networkDirection == null ? "BOTH" : networkDirection.toString();
        if (registeredMessages.contains(index + dirName))
        {
            sunetworklog.error("Tried registering Network Message id:" + Integer.toString(index) + ", Class:"
                    + type.getSimpleName() + ", Direction:" + dirName + " Twice!");
            return;
        }
        else
        {
            sunetworklog.info("Registering Network Message id:" + Integer.toString(index) + ", Class:"
                    + type.getSimpleName() + ", Direction:" + dirName);
            (networkDirection == null ? INSTANCE.messageBuilder(type, index)
                    : INSTANCE.messageBuilder(type, index, networkDirection)).decoder(decoder).encoder(encoder)
                    .consumerNetworkThread(handler).add();
            // INSTANCE.registerMessage(index, type, encoder, decoder, ISUPacket::handler,
            // Optional.of(networkDirection));
            registeredMessages.add(index + dirName);
        }
    }

    // A serverbound custom payload is capped at 32767 bytes: the SERVER's ServerboundCustomPayloadPacket decoder
    // throws "Payload may not be larger than 32767 bytes" and drops the connection when a client sends more (verified
    // in the 1.20.1 recomp jar). Forge does not split. So an oversized client to server packet does not fail
    // gracefully, it disconnects the sender, and because the offending state usually persists (an editor's full list,
    // a grown config) the client reconnects and is kicked again. We stay under the ceiling with headroom for the
    // channel ResourceLocation and the SimpleChannel discriminator that ride ahead of our body.
    private static final int SERVERBOUND_PAYLOAD_LIMIT = 32767;
    private static final int SERVERBOUND_SAFE_BODY = SERVERBOUND_PAYLOAD_LIMIT - 512;

    // client side only
    public static <MSG extends ISUPacket> void sendToServer(MSG msg)
    {
        if (wouldExceedServerboundLimit(msg))
        {
            // Refuse rather than let the server's decoder kick us. The action (usually an editor save) is lost, but a
            // named error beats a disconnect loop; the packet named here is the one that needs chunking. See sdu's
            // DmzNet.ChunkedSavePacket for the pattern its editors already use for large saves.
            return;
        }
        INSTANCE.sendToServer(msg);
    }

    // Measure the encoded body before it goes out, so an oversized client to server packet is caught HERE (with the
    // packet named) instead of on the server's decoder as an anonymous disconnect. Never blocks a send on a probe
    // fault: a failure to measure falls through to the normal send.
    private static <MSG extends ISUPacket> boolean wouldExceedServerboundLimit(MSG msg)
    {
        FriendlyByteBuf probe = new FriendlyByteBuf(Unpooled.buffer());
        try
        {
            msg.encode(probe);
            int size = probe.readableBytes();
            if (size > SERVERBOUND_SAFE_BODY)
            {
                sunetworklog.error("Refusing to send client to server packet {}: its {} byte body exceeds the {} byte "
                        + "serverbound custom-payload limit and would disconnect you. The action was NOT sent; this "
                        + "packet needs to be split into chunks.", msg.getClass().getSimpleName(), size,
                        SERVERBOUND_PAYLOAD_LIMIT);
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            // A probe encode should never throw, but if it does we must not silently swallow a legitimate send: let it
            // proceed and, at worst, surface the real problem on the normal path.
            return false;
        }
        finally
        {
            probe.release();
        }
    }

    // server side only
    public static <MSG extends ISUPacket> void sendTo(MSG msg, ServerPlayer player)
    {
        if (!(player instanceof FakePlayer))
        {
            INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), msg);
        }
    }

    public static void handleGetLog(ISUPacket packet) {
        sunetworklog.debug("Recieved "+packet.getClass().getSimpleName());
    }

    public static void handleNotHandled(ISUPacket packet) {
        sunetworklog.warn(packet.getClass().getSimpleName()+" was not handled properly");
    }
}
