package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * server -> client: "this player is crashing, here is the heading and for how long."
 *
 * <p>Sent for the same reason the dash sends its route: a player's own client is authoritative for their movement, so
 * a crash driven only by server velocity is fought by the client every tick and barely moves the person who started
 * it. The client flies its own body along the heading; the server keeps its own copy and only pulls if the two
 * genuinely part company.
 */
public class PacketSonicCrashState implements ISUPacket
{
    public int entityId;
    public boolean crashing;
    public int ticks;
    public double speed;
    public boolean steered;
    public Vec3 heading = Vec3.ZERO;

    public PacketSonicCrashState() {}

    public static void broadcastStart(ServerPlayer player, Vec3 heading, double speed, int ticks, boolean steered)
    {
        PacketSonicCrashState packet = new PacketSonicCrashState();
        packet.entityId = player.getId();
        packet.crashing = true;
        packet.heading = heading;
        packet.speed = speed;
        packet.ticks = ticks;
        packet.steered = steered;
        send(player, packet);
    }

    public static void broadcastEnd(ServerPlayer player)
    {
        PacketSonicCrashState packet = new PacketSonicCrashState();
        packet.entityId = player.getId();
        packet.crashing = false;
        send(player, packet);
    }

    private static void send(ServerPlayer player, PacketSonicCrashState packet)
    {
        try
        {
            if (!(player.level() instanceof ServerLevel level))
                return;
            // Everyone in the level: a crash covers enough ground that the set of people who will see it before it
            // ends is wider than the set tracking the player at the moment it starts. Same reasoning as the dash.
            for (ServerPlayer viewer : level.players())
                NetworkUtils.sendTo(packet, viewer);
        }
        catch (Throwable ignored)
        {
        }
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(entityId);
        buf.writeBoolean(crashing);
        if (!crashing)
            return;
        buf.writeVarInt(ticks);
        buf.writeDouble(speed);
        buf.writeBoolean(steered);
        buf.writeDouble(heading.x);
        buf.writeDouble(heading.y);
        buf.writeDouble(heading.z);
    }

    public static PacketSonicCrashState decode(FriendlyByteBuf buf)
    {
        PacketSonicCrashState packet = new PacketSonicCrashState();
        packet.entityId = buf.readVarInt();
        packet.crashing = buf.readBoolean();
        if (!packet.crashing)
            return packet;
        packet.ticks = buf.readVarInt();
        packet.speed = buf.readDouble();
        packet.steered = buf.readBoolean();
        packet.heading = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return packet;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient);
    }

    private void applyClient()
    {
        if (crashing)
            net.shurui.shuruisutilities.client.combat.SonicCrashLocalMotion.begin(entityId, heading, speed, ticks,
                    steered);
        else
            net.shurui.shuruisutilities.client.combat.SonicCrashLocalMotion.end(entityId);
    }

    public static void handler(final PacketSonicCrashState message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
