package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server companion to the planet-select opener, two jobs chosen by {@code clear}:
 *
 * <ul>
 *   <li>QUERY ({@code clear == false}): report the tracked planet, or nothing. Sent after the chooser opens because
 *       DMZ's own {@code SpacePodScreen} (which we reuse) cannot be cheaply annotated without a second mixin.</li>
 *   <li>CLEAR ({@code clear == true}): stop tracking via the single {@link PlanetCourse#clearCourse} path, so the
 *       compass pip is removed via {@link SpaceCompassBridge} exactly like an on-arrival clear. Never a second
 *       teardown path.</li>
 * </ul>
 *
 * <p>Course state is authoritative server-side (a persistent tag), so both run against it. Nothing to gate by pod,
 * dimension or altitude.
 */
public class PacketSpaceCourse implements ISUPacket
{
    // false = query the current course, true = clear it.
    private boolean clear;

    public PacketSpaceCourse() {}

    public PacketSpaceCourse(boolean clear)
    {
        this.clear = clear;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(clear);
    }

    public static PacketSpaceCourse decode(FriendlyByteBuf buf)
    {
        return new PacketSpaceCourse(buf.readBoolean());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
        {
            return;
        }

        if (clear)
        {
            if (!PlanetCourse.hasCourse(player))
            {
                player.displayClientMessage(
                        Component.translatable("message.dmz_ragnarok.core.space_course_none"), false);
                return;
            }
            Component name = PlanetCourse.courseDisplayName(player);
            PlanetCourse.clearCourse(player);
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_course_cleared", name), false);
            return;
        }

        if (!PlanetCourse.hasCourse(player))
        {
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_course_none"), false);
            return;
        }
        Component name = PlanetCourse.courseDisplayName(player);
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_course_current", name), false);
    }

    public static void handler(final PacketSpaceCourse message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
