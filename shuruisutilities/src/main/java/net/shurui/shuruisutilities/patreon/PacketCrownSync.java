package net.shurui.shuruisutilities.patreon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

// server -> client: each online player's supporter crown CODEPOINT (0/absent = none), so the client can draw the
// crown above heads and in the tab list. Only the resolved glyph travels, never the tier or the entitlement, so a
// client can never learn or claim anyone's pledge. Loaded into CrownClientCache via DistExecutor.
public class PacketCrownSync implements ISUPacket
{
    public Map<UUID, Integer> crowns = new HashMap<>();

    public PacketCrownSync() {}

    public PacketCrownSync(Map<UUID, Integer> crowns)
    {
        this.crowns = crowns;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(crowns.size());
        for (Map.Entry<UUID, Integer> e : crowns.entrySet())
        {
            buf.writeUUID(e.getKey());
            buf.writeVarInt(e.getValue());
        }
    }

    public static PacketCrownSync decode(FriendlyByteBuf buf)
    {
        PacketCrownSync p = new PacketCrownSync();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            UUID id = buf.readUUID();
            p.crowns.put(id, buf.readVarInt());
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.patreon.client.CrownClientCache.replaceAll(crowns));
    }

    public static void handler(final PacketCrownSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
