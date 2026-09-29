package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.race.SuppressedDefaultsClient;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Server -> client. The server's suppressed default race/class id sets
 * ({@link net.shurui.dev.sdu.race.SuppressedDefaultsConfig}), pushed on login and after a suppress/unsuppress.
 * Suppressed defaults are stripped from DMZ's synced maps, so the client can't find them via
 * {@code getLoadedRaces()}; this keeps them visible in the editors (greyed, "click to restore") for undo.
 * Applies to {@link SuppressedDefaultsClient}. Encodes two id-set blocks: races, then classes.
 */
public class SuppressedDefaultsSyncPacket {

    private final Set<String> races;
    private final Set<String> classes;

    public SuppressedDefaultsSyncPacket(Set<String> races, Set<String> classes) {
        this.races = races == null ? Set.of() : races;
        this.classes = classes == null ? Set.of() : classes;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(races.size());
        for (String r : races) {
            buf.writeUtf(r);
        }
        buf.writeVarInt(classes.size());
        for (String c : classes) {
            buf.writeUtf(c);
        }
    }

    public static SuppressedDefaultsSyncPacket decode(FriendlyByteBuf buf) {
        int nr = buf.readVarInt();
        Set<String> races = new LinkedHashSet<>(Math.max(8, nr));
        for (int i = 0; i < nr; i++) {
            races.add(buf.readUtf());
        }
        int nc = buf.readVarInt();
        Set<String> classes = new LinkedHashSet<>(Math.max(8, nc));
        for (int i = 0; i < nc; i++) {
            classes.add(buf.readUtf());
        }
        return new SuppressedDefaultsSyncPacket(races, classes);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SuppressedDefaultsClient.applySynced(races, classes)));
        context.setPacketHandled(true);
    }
}
