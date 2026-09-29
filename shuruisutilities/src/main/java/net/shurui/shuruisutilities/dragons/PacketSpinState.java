package net.shurui.shuruisutilities.dragons;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * server -> client: "spin this player's MODEL" (or stop).
 *
 * <h2>Why a packet and not an animation</h2>
 * DMZ's player clips are all poses - {@code ki.animation.json} has a cast and a fire for each technique and nothing
 * that turns the body - so there is no clip to trigger for a player caught in their own hurricane. A rotation applied
 * at render time is the only way to spin the model.
 *
 * <p>It also has to be render-only. Turning the player's actual yaw would drag the CAMERA round with it for the
 * caster, which is unplayable; rotating the pose stack in {@code RenderPlayerEvent} turns the body everyone sees and
 * leaves the view exactly where the player put it.
 *
 * <p>Broadcast to everyone in the level including the caster, because the caster sees their own model in third
 * person and from every other player's screen it has to match.
 */
public class PacketSpinState implements ISUPacket
{
    public int entityId;
    /** How long the spin lasts, in ticks. Zero stops it. */
    public int ticks;

    public PacketSpinState() {}

    /** Start (or refresh) the spin on this player for the given number of ticks. */
    public static void broadcast(ServerPlayer player, int ticks)
    {
        if (player == null)
            return;
        PacketSpinState packet = new PacketSpinState();
        packet.entityId = player.getId();
        packet.ticks = Math.max(0, ticks);
        send(player, packet);
    }

    /** Stop the spin now, rather than waiting for it to lapse. */
    public static void broadcastEnd(ServerPlayer player)
    {
        broadcast(player, 0);
    }

    private static void send(ServerPlayer player, PacketSpinState packet)
    {
        try
        {
            if (!(player.level() instanceof ServerLevel level))
                return;
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
        buf.writeVarInt(ticks);
    }

    public static PacketSpinState decode(FriendlyByteBuf buf)
    {
        PacketSpinState packet = new PacketSpinState();
        packet.entityId = buf.readVarInt();
        packet.ticks = buf.readVarInt();
        return packet;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Named only inside the client branch so a dedicated server never classloads the renderer.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient);
    }

    private void applyClient()
    {
        net.shurui.shuruisutilities.client.dragons.SpinRenderState.set(entityId, ticks);
    }

    public static void handler(final PacketSpinState message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
