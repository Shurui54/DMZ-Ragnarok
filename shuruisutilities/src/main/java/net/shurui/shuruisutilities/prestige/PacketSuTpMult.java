package net.shurui.shuruisutilities.prestige;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the local player's SU TP-gain bonuses. Two parts are carried:
 * <ul>
 *   <li>{@code pct} - the additive prestige + {@code su.tpgain} bonus percent, and</li>
 *   <li>{@code boost} - a multiplicative factor for the live global {@code /tpboost} window (1.0 = none).</li>
 * </ul>
 * Both are cached in {@link net.shurui.shuruisutilities.prestige.client.SuTpClient} and surfaced as an
 * "SU TP Mult" line in DragonMineZ's TP Multiplier tooltip (and folded into that tooltip's shown Total). Sent
 * on login, on prestige, on character-slot switch, whenever the global TP boost changes, and whenever the
 * client re-requests it on opening the stats screen, so the shown multiplier tracks the TP actually earned.
 */
public class PacketSuTpMult implements ISUPacket
{
    public int pct;
    public double boost = 1.0;

    public PacketSuTpMult() {}

    public PacketSuTpMult(int pct, double boost)
    {
        this.pct = pct;
        this.boost = boost;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(pct);
        buf.writeDouble(boost);
    }

    public static PacketSuTpMult decode(FriendlyByteBuf buf)
    {
        PacketSuTpMult p = new PacketSuTpMult();
        p.pct = buf.readVarInt();
        p.boost = buf.readDouble();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.prestige.client.SuTpClient.set(pct, boost));
    }

    public static void handler(final PacketSuTpMult message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
