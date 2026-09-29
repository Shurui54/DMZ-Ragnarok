package net.shurui.shuruisutilities.prestige;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server -&gt; client: opens the prestige screen, carrying everything it needs to render the benefits + button. */
public class PacketPrestigeGui implements ISUPacket
{
    public int level;        // current prestige of the active character
    public int max;          // configured maximum prestige
    public int perLevelPct;  // TP bonus % granted per prestige level
    public int permPct;      // extra TP bonus % from the su.tpgain permission
    public boolean canPrestige;

    public PacketPrestigeGui() {}

    public PacketPrestigeGui(int level, int max, int perLevelPct, int permPct, boolean canPrestige)
    {
        this.level = level;
        this.max = max;
        this.perLevelPct = perLevelPct;
        this.permPct = permPct;
        this.canPrestige = canPrestige;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(level);
        buf.writeVarInt(max);
        buf.writeVarInt(perLevelPct);
        buf.writeVarInt(permPct);
        buf.writeBoolean(canPrestige);
    }

    public static PacketPrestigeGui decode(FriendlyByteBuf buf)
    {
        PacketPrestigeGui p = new PacketPrestigeGui();
        p.level = buf.readVarInt();
        p.max = buf.readVarInt();
        p.perLevelPct = buf.readVarInt();
        p.permPct = buf.readVarInt();
        p.canPrestige = buf.readBoolean();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.prestige.client.PrestigeScreen.open(level, max, perLevelPct, permPct, canPrestige));
    }

    public static void handler(final PacketPrestigeGui message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
