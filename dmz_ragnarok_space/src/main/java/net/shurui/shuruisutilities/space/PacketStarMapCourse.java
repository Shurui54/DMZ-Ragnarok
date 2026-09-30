package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server "set my course to this body", fired by the SPACE STAR MAP's set-course / engage-autopilot button.
 * Carries only the target body key (a fixed-planet dimension id). The SERVER is authoritative: it resolves the key
 * against the live fixed-planet registry through {@link PlanetCourse#armCourseToBody} and refuses anything that is not a
 * fixed body, so a client can never steer to a generated or super body the autopilot cannot reach, nor to a body that
 * does not exist. It writes only the same transient course/autopilot persistent tags the pod-menu flow already writes:
 * no new persisted state, no schema change, no world migration.
 *
 * <p>The body key, not a friendly name or a screen index, is the payload because the star map already derives it from
 * the same synced layout the server holds (fixed bodies arrive over {@link PacketSpaceLayoutSync}), so the two sides
 * name a body identically.
 */
public class PacketStarMapCourse implements ISUPacket
{
    private String bodyKey = "";

    public PacketStarMapCourse()
    {
    }

    public PacketStarMapCourse(String bodyKey)
    {
        this.bodyKey = bodyKey == null ? "" : bodyKey;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(bodyKey);
    }

    public static PacketStarMapCourse decode(FriendlyByteBuf buf)
    {
        return new PacketStarMapCourse(buf.readUtf());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
        {
            return;
        }
        // fixed bodies are the only course targets (the autopilot resolves its target through the fixed registry only);
        // decline a generated / super / unknown body with a translated notice rather than silently doing nothing.
        if (!PlanetCourse.armCourseToBody(player, bodyKey))
        {
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_starmap_unsupported"), false);
            return;
        }
        // an armed launch autopilot reads differently from a compass-only pip: SpaceAutopilot is active only when the
        // player was aboard a pod in a launch-eligible place, exactly the distinction the pod-menu messages draw.
        Component name = PlanetCourse.courseDisplayName(player);
        String key = SpaceAutopilot.isActive(player)
                ? "message.dmz_ragnarok.core.space_autopilot_set"
                : "message.dmz_ragnarok.core.space_course_set";
        player.displayClientMessage(Component.translatable(key, name), false);
    }

    public static void handler(final PacketStarMapCourse message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
