package net.shurui.shuruisutilities.prestige;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** Client -&gt; server: admin saves prestige settings (+ optionally renames the edited NPC). Admin-gated. */
public class PacketPrestigeAdminSave implements ISUPacket
{
    public int maxPrestige;
    public int tpPerLevel;
    public int capPerLevel;  // stat-cap boost % per prestige level
    public int entityId;
    public String npcName = "";
    public String model = "";
    public boolean spawnHere;  // true = also spawn a prestige NPC at the player's location

    public PacketPrestigeAdminSave() {}

    public PacketPrestigeAdminSave(int maxPrestige, int tpPerLevel, int capPerLevel, int entityId, String npcName,
                                   String model)
    {
        this(maxPrestige, tpPerLevel, capPerLevel, entityId, npcName, model, false);
    }

    public PacketPrestigeAdminSave(int maxPrestige, int tpPerLevel, int capPerLevel, int entityId, String npcName,
                                   String model, boolean spawnHere)
    {
        this.maxPrestige = maxPrestige;
        this.tpPerLevel = tpPerLevel;
        this.capPerLevel = capPerLevel;
        this.entityId = entityId;
        this.npcName = npcName == null ? "" : npcName;
        this.model = model == null ? "" : model;
        this.spawnHere = spawnHere;
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
        buf.writeBoolean(spawnHere);
    }

    public static PacketPrestigeAdminSave decode(FriendlyByteBuf buf)
    {
        PacketPrestigeAdminSave p = new PacketPrestigeAdminSave();
        p.maxPrestige = buf.readVarInt();
        p.tpPerLevel = buf.readVarInt();
        p.capPerLevel = buf.readVarInt();
        p.entityId = buf.readVarInt();
        p.npcName = buf.readUtf();
        p.model = buf.readUtf();
        p.spawnHere = buf.readBoolean();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // S19b: the handler body is private (prestige is keyed, owner Q1) and lives in the Ragnarok Key. Keyless the
        // hook ignores the packet.
        ServerPlayer sender = context.getSender();
        if (sender != null)
            net.shurui.shuruisutilities.api.key.PrestigeHooks.get().onAdminSave(sender, this);
    }

    public static void handler(final PacketPrestigeAdminSave message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
