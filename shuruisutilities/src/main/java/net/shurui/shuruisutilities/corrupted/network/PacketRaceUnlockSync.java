package net.shurui.shuruisutilities.corrupted.network;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * server -> client: the set of unlock-gated race ids the RECEIVING player currently owns (the base
 * {@code shadow_dragon} race and any shadow dragon sub-races they have earned). {@code half_saiyan} is a free sub-race
 * and is never gated, so it is not carried here. The client stashes the set in
 * {@link net.shurui.shuruisutilities.corrupted.client.RaceUnlockClient}; the DMZ race-select filter mixin and the SU
 * sub-race screen read it so a player only ever sees what they have earned.
 *
 * <p>Sent from {@code PrestigeManager.sendRaceLock}, which already fires on every login, respawn, dimension change,
 * prestige, and character-slot switch (piggybacked on {@code sendTpMult}), and to a single player when an admin runs
 * {@code /raceunlock grant|revoke} (via that command's {@code refreshOnline}). One send site covers every mutation
 * point.
 *
 * <p>The client cache fails closed: until this packet arrives nothing is considered unlocked, so on single-player, an
 * older server, or before login sync completes, gated races stay hidden and the secret is not leaked by a missing
 * packet.
 */
public class PacketRaceUnlockSync implements ISUPacket
{
    public Set<String> unlocked = new HashSet<>();

    public PacketRaceUnlockSync() {}

    public PacketRaceUnlockSync(Set<String> unlocked)
    {
        if (unlocked != null)
            this.unlocked = new HashSet<>(unlocked);
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(unlocked.size());
        for (String id : unlocked)
            buf.writeUtf(id);
    }

    public static PacketRaceUnlockSync decode(FriendlyByteBuf buf)
    {
        PacketRaceUnlockSync p = new PacketRaceUnlockSync();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.unlocked.add(buf.readUtf());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only: hand the set to the SU client cache. The client-only class is only referenced inside the CLIENT
        // branch so nothing client-side is classloaded on a dedicated server.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.corrupted.client.RaceUnlockClient.apply(unlocked));
    }

    public static void handler(final PacketRaceUnlockSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
