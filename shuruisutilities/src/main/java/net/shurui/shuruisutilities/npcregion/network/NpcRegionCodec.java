package net.shurui.shuruisutilities.npcregion.network;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.npcregion.NpcSpawnConfig;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Shared buffer helpers for streaming a region's list of {@link NpcSpawnConfig}s (a length-prefixed run of the
 * per-NPC codec). Used by both {@link PacketOpenNpcRegionEditor} (server -&gt; client) and
 * {@link PacketSaveNpcRegion} (client -&gt; server) so the wire format lives in one place.
 */
final class NpcRegionCodec
{
    private NpcRegionCodec() {}

    static void encodeList(FriendlyByteBuf buf, List<NpcSpawnConfig> npcs)
    {
        buf.writeVarInt(npcs == null ? 0 : npcs.size());
        if (npcs != null)
            for (NpcSpawnConfig c : npcs)
                c.encode(buf);
    }

    static List<NpcSpawnConfig> decodeList(FriendlyByteBuf buf)
    {
        int n = buf.readVarInt();
        List<NpcSpawnConfig> npcs = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++)
            npcs.add(NpcSpawnConfig.decode(buf));
        return npcs;
    }
}
