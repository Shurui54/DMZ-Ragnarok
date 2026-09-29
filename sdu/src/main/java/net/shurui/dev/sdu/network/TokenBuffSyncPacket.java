package net.shurui.dev.sdu.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.buff.TokenBuffStore;

/**
 * Server -> client: the buff tokens this player currently has running.
 *
 * <p>A stat-discount token lowers {@code StatsData.getSingleStatCost}, which runs on BOTH sides (server to
 * charge, client to draw the price and grey the button). Tokens live in persistent data that is never sent, so
 * the two disagreed: DMZ told players a purchase was too expensive while quietly willing to sell it.
 *
 * <p>The whole entry list travels, not one sum, because entries expire independently: a summed client would keep
 * showing it after the first token ran out.
 */
public class TokenBuffSyncPacket {

    /**
     * One running buff: how much, and the millisecond it stops counting ON THE CLOCK OF WHOEVER HOLDS IT.
     *
     * <p>It travels as time REMAINING, not as the server's timestamp. Comparing the server's wall clock against the
     * client's own let any client whose clock ran behind keep a discount on screen after the server had expired it,
     * and the server then refused the purchase the screen had just offered.
     */
    public record Entry(double fraction, long expiresAtMillis) {}

    private final List<Entry> stat;
    private final List<Entry> tp;

    public TokenBuffSyncPacket(List<Entry> stat, List<Entry> tp) {
        this.stat = stat == null ? List.of() : stat;
        this.tp = tp == null ? List.of() : tp;
    }

    /** Read a player's live tokens straight off their persistent data. */
    public static TokenBuffSyncPacket of(ServerPlayer player) {
        return new TokenBuffSyncPacket(
                TokenBuffStore.activeEntries(player, TokenBuffStore.Category.STAT),
                TokenBuffStore.activeEntries(player, TokenBuffStore.Category.TP));
    }

    public void encode(FriendlyByteBuf buf) {
        write(buf, stat);
        write(buf, tp);
    }

    private static void write(FriendlyByteBuf buf, List<Entry> entries) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeDouble(e.fraction());
            buf.writeLong(Math.max(0L, e.expiresAtMillis() - System.currentTimeMillis()));
        }
    }

    public static TokenBuffSyncPacket decode(FriendlyByteBuf buf) {
        return new TokenBuffSyncPacket(read(buf), read(buf));
    }

    private static List<Entry> read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(Math.max(4, n));
        for (int i = 0; i < n; i++) {
            double fraction = buf.readDouble();
            out.add(new Entry(fraction, System.currentTimeMillis() + buf.readLong()));
        }
        return out;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        // accept prices the open stat screen off the new discount when, and only when, that discount
                        // changed. A plain purchase resync carries the same tokens, so the screen (and the player's
                        // selected buy multiplier) is left alone.
                        net.shurui.dev.sdu.client.TokenBuffClient.accept(stat, tp)));
        ctx.get().setPacketHandled(true);
    }
}
