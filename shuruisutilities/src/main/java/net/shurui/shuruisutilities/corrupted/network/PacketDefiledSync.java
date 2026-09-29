package net.shurui.shuruisutilities.corrupted.network;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * server -> client: the server-wide "defiled balls are physically in the world" flag (mirror of
 * {@code ShadowDragonStorage.hasDefiledBallsPresent}). The client stashes it in
 * {@link net.shurui.shuruisutilities.corrupted.client.DefiledBallsClient}; the radar background helper reads it so the
 * Earth radar shows the shadow-dragon dial while defiled balls are out there to find, and DragonMineZ's stock dial
 * otherwise.
 *
 * <p>Modelled on {@link PacketRaceUnlockSync}. Sent on login and dimension change (from
 * {@link net.shurui.shuruisutilities.corrupted.ShadowDragonForgeHandler}) and broadcast to everyone the moment the
 * defiled state flips (from the arm / disarm / reset paths in the wish-tracking subsystem), so no per-tick polling is
 * needed. The client cache fails closed until the first sync arrives, so a missing packet never reveals the shadow art.
 */
public class PacketDefiledSync implements ISUPacket
{
    // Per-set presence flags: the Earth and Namek radar dials swap independently. earthDefiled is true only while the
    // corrupted-balls abuse event has balls scattered in the overworld; namekDefiled is always false, since the event
    // never places balls on Namek. The 11th-wish ritual "spent" state does NOT feed these: a spent set has no balls to
    // find, so its dial stays on DMZ's stock art.
    public boolean earthDefiled;
    public boolean namekDefiled;

    public PacketDefiledSync() {}

    public PacketDefiledSync(boolean earthDefiled, boolean namekDefiled)
    {
        this.earthDefiled = earthDefiled;
        this.namekDefiled = namekDefiled;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(earthDefiled);
        buf.writeBoolean(namekDefiled);
    }

    public static PacketDefiledSync decode(FriendlyByteBuf buf)
    {
        return new PacketDefiledSync(buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only: hand the flags to the SU client cache. The client-only class is referenced only inside the
        // CLIENT branch so nothing client-side is classloaded on a dedicated server.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.corrupted.client.DefiledBallsClient.apply(earthDefiled, namekDefiled));
    }

    public static void handler(final PacketDefiledSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
