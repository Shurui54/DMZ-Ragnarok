package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client: the KEY of the body this player's space course points at ("" for none). The course itself stays
 * a server-side persistent tag ({@link PlanetCourse}); the client needs only the key so the in-world target pip
 * ({@code client.space.SpaceTargetPip}) can follow the body's LIVE orbital position, which it derives from the same
 * synced layout the renderer uses. The compass marker alone carries a position frozen at the moment the course was set.
 *
 * <p>Sent when a course is set or cleared and on login. Tiny (one string), never per tick.
 */
public class PacketSpaceCourseSync implements ISUPacket
{
    // the client's copy, read by the pip. Written on the client main thread by the handler, cleared on logout.
    private static volatile String clientKey = "";

    private String key = "";

    public PacketSpaceCourseSync()
    {
    }

    public PacketSpaceCourseSync(String key)
    {
        this.key = key == null ? "" : key;
    }

    /** Push this player's current course key to their client. */
    public static void send(ServerPlayer player)
    {
        try
        {
            NetworkUtils.sendTo(new PacketSpaceCourseSync(PlanetCourse.courseKey(player)), player);
        }
        catch (Throwable ignored)
        {
            // a client without the space module simply has no pip.
        }
    }

    /** CLIENT: the body key the local player's course points at, or "" for none. */
    public static String clientKey()
    {
        return clientKey;
    }

    /** CLIENT: forget the course (logout). */
    public static void clearClient()
    {
        clientKey = "";
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(key, 256);
    }

    public static PacketSpaceCourseSync decode(FriendlyByteBuf buf)
    {
        return new PacketSpaceCourseSync(buf.readUtf(256));
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        clientKey = key;
    }

    public static void handler(final PacketSpaceCourseSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
