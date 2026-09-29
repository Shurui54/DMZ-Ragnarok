package net.shurui.shuruisutilities.prestige;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the local player's SU prestige stat-cap boost percent (prestige level x cap%/level).
 * Cached in {@link net.shurui.shuruisutilities.prestige.client.SuCapClient} and read by the two DragonMineZ
 * cap mixins so the client-side stat clamp and +stat button gating match the widened server cap. Sent
 * alongside {@link PacketSuTpMult} (login, prestige, character-slot switch).
 */
public class PacketSuCapMult implements ISUPacket
{
    public int pct;

    public PacketSuCapMult() {}

    public PacketSuCapMult(int pct)
    {
        this.pct = pct;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(pct);
    }

    public static PacketSuCapMult decode(FriendlyByteBuf buf)
    {
        PacketSuCapMult p = new PacketSuCapMult();
        p.pct = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.prestige.client.SuCapClient.setPct(pct));
    }

    public static void handler(final PacketSuCapMult message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
