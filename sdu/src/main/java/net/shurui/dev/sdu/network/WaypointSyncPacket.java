package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.waypoint.Waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client. The complete set of a player's active waypoints (manual + current-quest), pushed
 * whenever it changes. It's a full replace: the client swaps its whole list, so a quest waypoint that
 * disappears from the set (quest completed/dropped) is cleared from the HUD without an explicit remove.
 */
public class WaypointSyncPacket {

    private final List<Waypoint> waypoints;

    public WaypointSyncPacket(List<Waypoint> waypoints) {
        this.waypoints = waypoints == null ? List.of() : waypoints;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(waypoints.size());
        for (Waypoint w : waypoints) {
            w.encode(buf);
        }
    }

    public static WaypointSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Waypoint> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(Waypoint.decode(buf));
        }
        return new WaypointSyncPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.dev.sdu.client.ClientWaypoints.accept(waypoints)));
        context.setPacketHandled(true);
    }
}
