package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C, on right-clicking a browser sign-up NPC. Carries every tournament the NPC exposes, already filtered
 * by its group. Picking one sends an {@link OpenSignupRequestPacket} back.
 */
public class OpenBrowserPacket {
    /** One tournament row in the browser. */
    public record Entry(String defId, String name, String group, String formatLabel, boolean signupOpen, int count) {}

    private final List<Entry> entries;

    public OpenBrowserPacket(List<Entry> entries) {
        this.entries = entries == null ? List.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.defId());
            buf.writeUtf(e.name());
            buf.writeUtf(e.group());
            buf.writeUtf(e.formatLabel());
            buf.writeBoolean(e.signupOpen());
            buf.writeVarInt(e.count());
        }
    }

    public static OpenBrowserPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readVarInt()));
        }
        return new OpenBrowserPacket(entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_dmz_tournaments.client.ClientPacketHandler.openBrowser(entries)));
        ctx.get().setPacketHandled(true);
    }
}
