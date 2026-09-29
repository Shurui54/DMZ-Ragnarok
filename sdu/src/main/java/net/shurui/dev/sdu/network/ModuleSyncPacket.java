package net.shurui.dev.sdu.network;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -> client, on login: which module switches this server has turned OFF.
 *
 * <p>The operator per-feature switchboard was removed in batch M, so this always carries an EMPTY set now. The
 * packet and its fixed id (sdu_main 51) are KEPT registered and harmless: dropping a registered id shifts every id
 * after it, and an empty payload hides nothing, which is the safe answer. {@link net.shurui.dev.sdu.api.ClientGate}
 * now answers {@code on(...)} as always-true and gates private UI on the key alone.
 */
public class ModuleSyncPacket {

    private final Set<String> disabled;

    public ModuleSyncPacket(Set<String> disabled) {
        this.disabled = disabled == null ? Set.of() : disabled;
    }

    /** Always empty since batch M removed the operator switchboard: nothing is switched off any more. */
    public static ModuleSyncPacket current() {
        return new ModuleSyncPacket(Set.of());
    }

    /** How many switches are off, for the server-side login log line. */
    public int disabledCount() {
        return disabled.size();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(disabled.size());
        for (String key : disabled) {
            buf.writeUtf(key);
        }
    }

    public static ModuleSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Set<String> out = new HashSet<>(Math.max(4, n));
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf());
        }
        return new ModuleSyncPacket(out);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                    // The switchboard is gone, so there is nothing to record on the client; this packet exists only to
                    // keep the fixed id occupied. Logged here, not in the key packet: the login sends key first then
                    // this (the second, last) packet, so by the time it lands the key flag is settled and the line
                    // reports the real state the client will gate its screens by.
                    org.slf4j.LoggerFactory.getLogger("dmz_ragnarok").info(
                            "[dmz_ragnarok] client gate: key={} disabled={}",
                            net.shurui.dev.sdu.api.ClientGate.key(),
                            disabled.size());
                }));
        ctx.get().setPacketHandled(true);
    }
}
