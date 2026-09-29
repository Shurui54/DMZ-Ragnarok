package net.shurui.shuruisutilities.gravitychamber;

import net.shurui.shuruisutilities.api.key.GuildRaidHooks;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Client -&gt; server: save an edited gravity + range on a guild gravity chamber. The server re-checks that the
 * sender is allowed to use the chamber and that it is within reach before applying the clamped config.
 */
public class PacketSaveChamberGravity implements ISUPacket
{
    public BlockPos pos;
    public double gravity;
    public int radius;

    public PacketSaveChamberGravity()
    {
    }

    public PacketSaveChamberGravity(BlockPos pos, double gravity, int radius)
    {
        this.pos = pos;
        this.gravity = gravity;
        this.radius = radius;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBlockPos(pos);
        buf.writeDouble(gravity);
        buf.writeVarInt(radius);
    }

    public static PacketSaveChamberGravity decode(FriendlyByteBuf buf)
    {
        PacketSaveChamberGravity p = new PacketSaveChamberGravity();
        p.pos = buf.readBlockPos();
        p.gravity = buf.readDouble();
        p.radius = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        // the chamber menus are private (Ragnarok Key): keyless no menu opens, so no edit is accepted either.
        if (player == null || !GuildRaidHooks.available())
        {
            return;
        }
        // reach + type check so a spoofed packet cannot edit a chamber the player is nowhere near.
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0)
        {
            return;
        }
        if (!(player.level().getBlockEntity(pos) instanceof GuildGravityChamberBlockEntity chamber))
        {
            return;
        }
        if (!GuildGravityChamber.canUse(player, chamber))
        {
            return;
        }
        chamber.setConfig(gravity, radius);
    }

    public static void handler(final PacketSaveChamberGravity message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
