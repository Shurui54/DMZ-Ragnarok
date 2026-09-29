package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * server -> client: "this player is dashing, here is the route" (or has stopped).
 *
 * <p>Three things need this. The aura, which is laid over the screen rather than stood upright while dashing; the line
 * trail; and, for the dasher's OWN client, the movement itself.
 *
 * <p>That last one is why the whole route is in here rather than just a heading. A dash driven from the server, one
 * velocity packet per tick, is always a round trip behind the player's own input, and every packet overwrites the
 * smooth motion the client had already predicted. It reads as jumpy and late no matter how good the path is. Sending
 * the plan ONCE lets the client fly the curve itself, at its own frame rate, starting the moment the packet lands;
 * the server keeps its own copy of the same curve and only intervenes if the two part company. See {@link DashPath}
 * for the maths both sides share.
 *
 * <p>Sent to everyone tracking the dasher AND to the dasher themselves, because the local player's own aura is
 * reoriented by exactly the same code path as everybody else's.
 */
public class PacketDashState implements ISUPacket
{
    public int entityId;
    public boolean dashing;
    public int durationTicks;

    // Route. Meaningful only while dashing.
    public int modeId;
    public int targetId;
    public int windup;
    public int travelTicks;
    public double maxTravel;
    public Vec3 origin = Vec3.ZERO;
    public Vec3 control = Vec3.ZERO;
    public Vec3 side = Vec3.ZERO;
    public Vec3 heading = Vec3.ZERO;

    public PacketDashState() {}

    /** Tell everyone who can see this player that a dash has started, and how it is going to fly. */
    public static void broadcast(ServerPlayer player, DashService.Route route, int durationTicks)
    {
        if (player == null || route == null)
            return;
        PacketDashState packet = new PacketDashState();
        packet.entityId = player.getId();
        packet.dashing = true;
        packet.durationTicks = durationTicks;
        packet.modeId = route.modeId();
        packet.targetId = route.targetId();
        packet.windup = route.windup();
        packet.travelTicks = route.travelTicks();
        packet.maxTravel = route.maxTravel();
        packet.origin = route.origin() == null ? player.getEyePosition() : route.origin();
        packet.control = route.control() == null ? packet.origin : route.control();
        packet.side = route.side() == null ? player.getLookAngle().scale(-1.0D) : route.side();
        packet.heading = route.heading() == null ? player.getLookAngle() : route.heading();
        send(player, packet);
    }

    /** Tell everyone the dash has ended, so the aura rights itself without waiting for the safety expiry. */
    public static void broadcastEnd(ServerPlayer player)
    {
        if (player == null)
            return;
        PacketDashState packet = new PacketDashState();
        packet.entityId = player.getId();
        packet.dashing = false;
        send(player, packet);
    }

    private static void send(ServerPlayer player, PacketDashState packet)
    {
        try
        {
            if (!(player.level() instanceof ServerLevel level))
                return;
            // Everyone in the level rather than a tracking query: a dash covers up to a hundred blocks, so the set of
            // people who will see it before it ends is wider than the set tracking the player right now.
            for (ServerPlayer viewer : level.players())
            {
                NetworkUtils.sendTo(packet, viewer);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(entityId);
        buf.writeBoolean(dashing);
        if (!dashing)
            return;
        buf.writeVarInt(durationTicks);
        buf.writeVarInt(modeId);
        buf.writeVarInt(targetId);
        buf.writeVarInt(windup);
        buf.writeVarInt(travelTicks);
        buf.writeDouble(maxTravel);
        writeVec(buf, origin);
        writeVec(buf, control);
        writeVec(buf, side);
        writeVec(buf, heading);
    }

    public static PacketDashState decode(FriendlyByteBuf buf)
    {
        PacketDashState packet = new PacketDashState();
        packet.entityId = buf.readVarInt();
        packet.dashing = buf.readBoolean();
        if (!packet.dashing)
            return packet;
        packet.durationTicks = buf.readVarInt();
        packet.modeId = buf.readVarInt();
        packet.targetId = buf.readVarInt();
        packet.windup = buf.readVarInt();
        packet.travelTicks = buf.readVarInt();
        packet.maxTravel = buf.readDouble();
        packet.origin = readVec(buf);
        packet.control = readVec(buf);
        packet.side = readVec(buf);
        packet.heading = readVec(buf);
        return packet;
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 vec)
    {
        Vec3 v = vec == null ? Vec3.ZERO : vec;
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf)
    {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only. The client side classes are named only inside this branch so nothing client side is classloaded
        // on a dedicated server.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient);
    }

    private void applyClient()
    {
        if (dashing)
        {
            net.shurui.shuruisutilities.client.combat.DashAuraState.beginDash(entityId, heading, durationTicks,
                    modeId, targetId, windup, travelTicks, origin, control, side, maxTravel);
        }
        else
        {
            net.shurui.shuruisutilities.client.combat.DashAuraState.end(entityId);
        }
    }

    public static void handler(final PacketDashState message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
