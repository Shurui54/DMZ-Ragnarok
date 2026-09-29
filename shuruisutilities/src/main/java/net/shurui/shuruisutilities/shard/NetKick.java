package net.shurui.shuruisutilities.shard;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Turning a punishment disconnect into a WHOLE-NETWORK disconnect.
 *
 * <h2>The problem</h2>
 * The proxy runs {@code try = ["OW1", "OW2"]} with {@code failover-on-unexpected-server-disconnect = true}. When a
 * backend disconnects a player it is holding, Velocity's default {@code KickedFromServerEvent} handling redirects
 * them to the next server in the try list rather than dropping them. That is exactly what a shard crash or restart
 * wants (keep the player on the network), but it means a staff {@code /kick}, a {@code /ban}, a tempban or an IP
 * ban does nothing: the target simply reconnects to the other shard. The kick reason never even reaches them.
 *
 * <h2>The fix, and why it is two signals</h2>
 * A punishment tells the proxy, on the player's own backend connection, that THIS disconnect is a network-wide
 * removal, so the proxy overrides its failover and disconnects the player from the whole network with the reason.
 * A non-punishment disconnect (a shard restart, a crash, the SU shard hop) says nothing and keeps today's
 * failover. This class carries BOTH signals, and the proxy treats either as sufficient:
 *
 * <ol>
 *   <li><b>A plugin message</b> on the {@code ragnarok:netkick} channel, sent as a clientbound custom payload on the
 *       player's connection exactly the way {@link ShardTransfer} asks the proxy to move a player over the
 *       BungeeCord channel. The proxy intercepts it before the client sees it. It carries the reason text, and it is
 *       sent JUST BEFORE the disconnect on the same server thread and therefore the same TCP stream, so it is
 *       written to the wire ahead of the disconnect packet and the proxy reads it first.</li>
 *   <li><b>An invisible marker on the kick reason component</b> itself: an empty child carrying the insertion string
 *       {@link #MARKER}. The disconnect packet's reason travels to the proxy anyway and arrives INSIDE the same
 *       {@code KickedFromServerEvent} the proxy has to decide, so this signal cannot lose a race with the plugin
 *       message however Velocity schedules its event dispatch. An insertion is never rendered on a disconnect
 *       screen, so a client that reaches the reason directly (no proxy, or an old proxy plugin) shows nothing
 *       extra.</li>
 * </ol>
 *
 * <p>The plugin message is only sent when the shard layer is configured ({@link ShardConfig.Values#enabled}), i.e.
 * on a networked deployment behind the proxy; a lone or singleplayer server skips it and relies on nothing, since
 * there is no proxy to talk to. The component marker is always applied because it is invisible and free.
 */
public final class NetKick
{
    private NetKick() {}

    /** The insertion string the proxy looks for on a kick reason, and the plugin message channel's path. */
    public static final String MARKER = "ragnarok:netkick";

    /** Backend to proxy channel. Registered on the proxy; Velocity delivers it as a PluginMessageEvent, not to the client. */
    private static final ResourceLocation CHANNEL = new ResourceLocation("ragnarok", "netkick");

    /** Payload version, so the proxy can reject a shape it does not understand rather than misread it. */
    private static final byte VERSION = 1;

    /**
     * Disconnect a player as a network-wide punishment: tell the proxy first, then disconnect with a marked reason.
     * Server thread. The message and the disconnect go out on the same connection in that order.
     *
     * @param conn   the target's game connection (never null at a live disconnect)
     * @param reason the disconnect reason shown to the player; a marked copy is what is actually sent
     */
    public static void disconnect(ServerGamePacketListenerImpl conn, Component reason)
    {
        if (conn == null)
            return;
        notifyProxy(conn, reason);
        conn.disconnect(mark(reason));
    }

    /**
     * Wrap a reason with the invisible network-kick marker. Safe to call anywhere; the marker never renders.
     * Exposed so a caller that must run the disconnect itself (an unusual path) can still mark its reason.
     */
    public static Component mark(Component reason)
    {
        Component base = reason == null ? Component.empty() : reason;
        // An empty text child whose only content is the insertion marker. Insertions are used for shift-click in
        // chat and are never drawn on a disconnect screen, so this is invisible to the player.
        MutableComponent marker = Component.literal("").withStyle(style -> style.withInsertion(MARKER));
        return base.copy().append(marker);
    }

    /**
     * Send the netkick plugin message to the proxy on this connection. No-op when the shard layer is off (there is
     * no proxy to receive it) or when anything goes wrong: the component marker still carries the signal.
     */
    private static void notifyProxy(ServerGamePacketListenerImpl conn, Component reason)
    {
        if (!networked())
            return;
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes))
            {
                out.writeByte(VERSION);
                // BungeeCord-style modified UTF-8 with a two byte length, the same wire shape ShardTransfer uses, so
                // the proxy reads it with a plain DataInput. reason.getString() is the plain text of the reason.
                out.writeUTF(reason == null ? "" : reason.getString());
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.toByteArray()));
            conn.send(new ClientboundCustomPayloadPacket(CHANNEL, buf));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[netkick] Could not notify the proxy of a network kick: {}", t.toString());
        }
    }

    /** True when the shard database is configured, which is the networked-behind-the-proxy deployment. */
    private static boolean networked()
    {
        try
        {
            return ShardConfig.get().enabled;
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
