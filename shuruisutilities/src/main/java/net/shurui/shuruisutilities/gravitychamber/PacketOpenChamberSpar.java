package net.shurui.shuruisutilities.gravitychamber;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client: open the guild gravity chamber's sparring menu, carrying the candidate opponents (self, online
 * guildmates and online party members) the player may spawn a scaled copy of.
 */
public class PacketOpenChamberSpar implements ISUPacket
{
    /** One selectable opponent: the member's UUID and the display name shown in the list. */
    public record Candidate(UUID id, String name)
    {
    }

    public BlockPos pos;
    public List<Candidate> candidates = new ArrayList<>();

    public PacketOpenChamberSpar()
    {
    }

    public PacketOpenChamberSpar(BlockPos pos, List<Candidate> candidates)
    {
        this.pos = pos;
        this.candidates = candidates;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBlockPos(pos);
        buf.writeVarInt(candidates.size());
        for (Candidate c : candidates)
        {
            buf.writeUUID(c.id());
            buf.writeUtf(c.name());
        }
    }

    public static PacketOpenChamberSpar decode(FriendlyByteBuf buf)
    {
        PacketOpenChamberSpar p = new PacketOpenChamberSpar();
        p.pos = buf.readBlockPos();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            UUID id = buf.readUUID();
            String name = buf.readUtf();
            p.candidates.add(new Candidate(id, name));
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        final BlockPos p = pos;
        final List<Candidate> list = candidates;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.client.gravitychamber.ChamberSparScreen.open(p, list));
    }

    public static void handler(final PacketOpenChamberSpar message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
