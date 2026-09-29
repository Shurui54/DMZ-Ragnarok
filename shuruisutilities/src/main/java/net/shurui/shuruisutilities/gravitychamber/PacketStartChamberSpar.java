package net.shurui.shuruisutilities.gravitychamber;

import net.shurui.shuruisutilities.api.key.GuildRaidHooks;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Client -&gt; server: start a chamber spar against a copy of the chosen member. All eligibility (chamber ownership,
 * standing inside the area, cooldown, the member being online) is re-checked server-side by the chamber spar manager,
 * which lives in the Ragnarok Key and is reached through {@link GuildRaidHooks} (keyless nothing starts).
 */
public class PacketStartChamberSpar implements ISUPacket
{
    public BlockPos pos;
    public UUID memberId;

    public PacketStartChamberSpar()
    {
    }

    public PacketStartChamberSpar(BlockPos pos, UUID memberId)
    {
        this.pos = pos;
        this.memberId = memberId;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBlockPos(pos);
        buf.writeUUID(memberId);
    }

    public static PacketStartChamberSpar decode(FriendlyByteBuf buf)
    {
        PacketStartChamberSpar p = new PacketStartChamberSpar();
        p.pos = buf.readBlockPos();
        p.memberId = buf.readUUID();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
        {
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0)
        {
            return;
        }
        GuildRaidHooks.get().startChamberSpar(player, pos, memberId);
    }

    public static void handler(final PacketStartChamberSpar message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
