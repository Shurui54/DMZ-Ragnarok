package net.shurui.shuruisutilities.prestige;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server -&gt; client: opens the prestige admin config screen (global settings + the clicked NPC's name). */
public class PacketPrestigeAdminGui implements ISUPacket
{
    public int maxPrestige;
    public int tpPerLevel;
    public int capPerLevel;  // stat-cap boost % per prestige level
    public int entityId;   // the prestige NPC being edited (-1 = global settings only)
    public String npcName = "";
    public String model = "";  // entity type id ("model"): the edited NPC's, or the default for new spawns
    /** Current race-&gt;required-prestige gate map, so the Race tab seeds its per-race fields from live settings. */
    public Map<String, Integer> raceRequired = new HashMap<>();
    /** Races only an operator may pick, so the Race tab can show each row's operator toggle already set. */
    public java.util.Set<String> opOnlyRaces = new java.util.HashSet<>();

    public PacketPrestigeAdminGui() {}

    public PacketPrestigeAdminGui(int maxPrestige, int tpPerLevel, int capPerLevel, int entityId, String npcName,
                                  String model, Map<String, Integer> raceRequired,
                                  java.util.Set<String> opOnlyRaces)
    {
        this.maxPrestige = maxPrestige;
        this.tpPerLevel = tpPerLevel;
        this.capPerLevel = capPerLevel;
        this.entityId = entityId;
        this.npcName = npcName == null ? "" : npcName;
        this.model = model == null ? "" : model;
        if (raceRequired != null)
            this.raceRequired = new HashMap<>(raceRequired);
        if (opOnlyRaces != null)
            this.opOnlyRaces = new java.util.HashSet<>(opOnlyRaces);
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(maxPrestige);
        buf.writeVarInt(tpPerLevel);
        buf.writeVarInt(capPerLevel);
        buf.writeVarInt(entityId);
        buf.writeUtf(npcName);
        buf.writeUtf(model);
        buf.writeVarInt(raceRequired.size());
        for (Map.Entry<String, Integer> e : raceRequired.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue() == null ? 0 : e.getValue());
        }
        buf.writeVarInt(opOnlyRaces.size());
        for (String race : opOnlyRaces)
            buf.writeUtf(race);
    }

    public static PacketPrestigeAdminGui decode(FriendlyByteBuf buf)
    {
        PacketPrestigeAdminGui p = new PacketPrestigeAdminGui();
        p.maxPrestige = buf.readVarInt();
        p.tpPerLevel = buf.readVarInt();
        p.capPerLevel = buf.readVarInt();
        p.entityId = buf.readVarInt();
        p.npcName = buf.readUtf();
        p.model = buf.readUtf();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            String race = buf.readUtf();
            p.raceRequired.put(race, buf.readVarInt());
        }
        int opCount = buf.readVarInt();
        for (int i = 0; i < opCount; i++)
            p.opOnlyRaces.add(buf.readUtf());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.prestige.client.PrestigeAdminScreen.open(maxPrestige, tpPerLevel, capPerLevel, entityId, npcName, model,
                        raceRequired, opOnlyRaces));
    }

    public static void handler(final PacketPrestigeAdminGui message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
