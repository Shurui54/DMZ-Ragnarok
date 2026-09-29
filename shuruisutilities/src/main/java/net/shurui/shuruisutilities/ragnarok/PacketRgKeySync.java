package net.shurui.shuruisutilities.ragnarok;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * server -&gt; client: whether Shurui's Key is present on the server the client just joined.
 *
 * <p>Sent once per login. The client needs this because the key-gated ragnarok models are offered in a CLIENT
 * screen (the Custom NPCs model editor), and the client cannot work the answer out for itself: the key is a
 * server-side mod, so {@code KeyGate.present()} evaluated on a connected client is always false, and
 * {@code KeyGate.unlocked()} is always true because the client is not a dedicated server. Either one alone gets
 * the answer wrong in one direction or the other, so the server states it.
 *
 * <p>Carries exactly one boolean, and only the same fact the server already reveals through which
 * {@code /su entity} ids it accepts. It gates what an editor OFFERS, not what it may write: an id is still
 * resolved server-side wherever it matters.
 */
public class PacketRgKeySync implements ISUPacket
{
    public boolean unlocked;

    public PacketRgKeySync() {}

    public PacketRgKeySync(boolean unlocked)
    {
        this.unlocked = unlocked;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(unlocked);
    }

    public static PacketRgKeySync decode(FriendlyByteBuf buf)
    {
        return new PacketRgKeySync(buf.readBoolean());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RgKeyClientState.set(unlocked));
    }

    public static void handler(final PacketRgKeySync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
