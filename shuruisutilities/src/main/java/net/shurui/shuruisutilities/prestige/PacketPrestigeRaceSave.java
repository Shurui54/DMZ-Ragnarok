package net.shurui.shuruisutilities.prestige;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.config.Features;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: admin saves the prestige-gated race map (lowercase DMZ race id -&gt; required prestige
 * level). Admin-gated exactly like {@link PacketPrestigeAdminSave} (re-checks {@link Features#PRESTIGE} and
 * {@code su.prestige.admin}). On success it writes the map into {@link PrestigeSettings}, marks it dirty, and
 * re-pushes the race lock state to every online player via {@link PrestigeManager#sendRaceLockToAll}.
 */
public class PacketPrestigeRaceSave implements ISUPacket
{
    public Map<String, Integer> raceRequired = new HashMap<>();
    /** Races only an operator may pick. Saved in the same edit, so one Save button covers the whole Race tab. */
    public java.util.Set<String> opOnlyRaces = new java.util.HashSet<>();

    public PacketPrestigeRaceSave() {}

    public PacketPrestigeRaceSave(Map<String, Integer> raceRequired, java.util.Set<String> opOnlyRaces)
    {
        if (raceRequired != null)
            this.raceRequired = new HashMap<>(raceRequired);
        if (opOnlyRaces != null)
            this.opOnlyRaces = new java.util.HashSet<>(opOnlyRaces);
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
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

    public static PacketPrestigeRaceSave decode(FriendlyByteBuf buf)
    {
        PacketPrestigeRaceSave p = new PacketPrestigeRaceSave();
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
        // S19b: the handler body is private (prestige is keyed, owner Q1) and lives in the Ragnarok Key. Keyless the
        // hook ignores the packet.
        ServerPlayer sender = context.getSender();
        if (sender != null)
            net.shurui.shuruisutilities.api.key.PrestigeHooks.get().onRaceSave(sender, this);
    }

    public static void handler(final PacketPrestigeRaceSave message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
