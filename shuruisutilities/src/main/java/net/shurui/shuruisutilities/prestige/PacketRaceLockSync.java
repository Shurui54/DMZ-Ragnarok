package net.shurui.shuruisutilities.prestige;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

// server -> client: prestige-gated race state for the receiving player. carries the full
// race->required-prestige gate map plus the races currently locked for this player (active-slot prestige
// below the requirement). client handler hands both to sdu's race-select screen cache (reflection, guarded
// by isModLoaded("sdu")) so locked races are greyed/blocked.
// sent whenever prestige/slot changes (piggybacked on sendTpMult: login, prestige, slot switch, respawn,
// dim change) and to every player after an admin edits the gate map.
public class PacketRaceLockSync implements ISUPacket
{
    public Map<String, Integer> raceRequired = new HashMap<>();
    public Set<String> locked = new HashSet<>();

    public PacketRaceLockSync() {}

    public PacketRaceLockSync(Map<String, Integer> raceRequired, Set<String> locked)
    {
        if (raceRequired != null)
            this.raceRequired = new HashMap<>(raceRequired);
        if (locked != null)
            this.locked = new HashSet<>(locked);
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
        buf.writeVarInt(locked.size());
        for (String race : locked)
            buf.writeUtf(race);
    }

    public static PacketRaceLockSync decode(FriendlyByteBuf buf)
    {
        PacketRaceLockSync p = new PacketRaceLockSync();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            String race = buf.readUtf();
            int lvl = buf.readVarInt();
            p.raceRequired.put(race, lvl);
        }
        int m = buf.readVarInt();
        for (int i = 0; i < m; i++)
            p.locked.add(buf.readUtf());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only: forward to sdu's race-lock cache via the reflection compat bridge (no-op without sdu), and keep
        // an SU-local copy of the locked set so SU's sub-race screen can independently refuse to open for a
        // prestige-locked race without classloading any sdu type.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
        {
            net.shurui.shuruisutilities.compat.sdu.SduRaceLockCompat.apply(raceRequired, locked);
            net.shurui.shuruisutilities.prestige.client.RaceLockLocalClient.apply(locked);
        });
    }

    public static void handler(final PacketRaceLockSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
