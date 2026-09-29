package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C on right-clicking a raid host empty-handed: every joinable raid (id, name, category, type, live
 * state) so the client builds the categorised browser without server-only state.
 */
public class OpenBrowserPacket {

    /** One browsable raid row. */
    public record Entry(String category, String id, String name, int typeOrdinal, int stateOrdinal) {}

    private final List<Entry> entries;

    public OpenBrowserPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.category());
            buf.writeUtf(e.id());
            buf.writeUtf(e.name());
            buf.writeVarInt(e.typeOrdinal());
            buf.writeVarInt(e.stateOrdinal());
        }
    }

    public static OpenBrowserPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt()));
        }
        return new OpenBrowserPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_raid_bosses.client.ClientPacketHandler.openBrowser(entries)));
        ctx.get().setPacketHandled(true);
    }
}
