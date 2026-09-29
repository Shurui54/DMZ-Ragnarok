package net.shurui.dev.shuruis_dmz_tournaments.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C. The full set of title ids the server actually recognises, with each id's display name, so the editor's
 * title reward dropdown reflects the server and not the client's own config file. The title definitions live in
 * {@code shuruis_dmz_tournaments-common.toml}, a COMMON config, and common configs are read per side and never
 * shipped from server to client, so an admin whose local file was stale could not see or pick a title (for example
 * {@code god_of_destruction} or {@code angel}) that existed on the server. This packet is that server at login path.
 *
 * <p>Source is {@link net.shurui.dev.shuruis_dmz_tournaments.reward.TitleManager#definitions()}, so it carries the
 * config rows PLUS the built-in role titles, which is exactly the set an admin may grant. Full replacement each time,
 * not a delta, mirroring the fighter and dormancy syncs. The two lists run in parallel: {@code ids.get(i)} pairs with
 * {@code displays.get(i)}.
 *
 * <p>Client cache is {@link net.shurui.dev.shuruis_dmz_tournaments.client.TitleListClient}, which fails safe by
 * reporting nothing received until the first sync, so single player and an older server fall back to the local config.
 */
public class TitleListSyncPacket {
    private final List<String> ids;
    private final List<String> displays;

    public TitleListSyncPacket(List<String> ids, List<String> displays) {
        this.ids = ids == null ? List.of() : new ArrayList<>(ids);
        this.displays = displays == null ? List.of() : new ArrayList<>(displays);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(ids.size());
        for (String id : ids) buf.writeUtf(id);
        buf.writeVarInt(displays.size());
        for (String d : displays) buf.writeUtf(d);
    }

    public static TitleListSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readUtf());
        int m = buf.readVarInt();
        List<String> displays = new ArrayList<>(m);
        for (int i = 0; i < m; i++) displays.add(buf.readUtf());
        return new TitleListSyncPacket(ids, displays);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // Client only: hand the lists to the addon's client cache. The client-only class is referenced only inside the
        // CLIENT branch so nothing client-side is classloaded on a dedicated server.
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_dmz_tournaments.client.TitleListClient.apply(ids, displays)));
        ctx.get().setPacketHandled(true);
    }
}
