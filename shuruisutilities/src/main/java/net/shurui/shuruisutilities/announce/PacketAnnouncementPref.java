package net.shurui.shuruisutilities.announce;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

// client -> server, one boolean: whether this client wants SU's own on-screen announcements shown. The client sends
// its current SUConfig.suiteAnnouncements on every login (and again whenever the toggle is flipped), and the server
// records it per player in AnnouncementPrefs, an in-memory map cleared on logout. Because the client re-announces on
// every join, the preference follows a player across shards with nothing persisted or synced through the vault: the
// destination shard simply hears it on arrival. See AnnouncementPrefs for the full reasoning on why this is safe.
public class PacketAnnouncementPref implements ISUPacket
{
    private final boolean shown;

    public PacketAnnouncementPref(boolean shown)
    {
        this.shown = shown;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(shown);
    }

    public static PacketAnnouncementPref decode(FriendlyByteBuf buf)
    {
        return new PacketAnnouncementPref(buf.readBoolean());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        AnnouncementPrefs.set(player.getUUID(), shown);
    }

    public static void handler(final PacketAnnouncementPref message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
