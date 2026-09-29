package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks;
import net.shurui.dev.shuruis_raid_bosses.item.ZStat;

import java.util.function.Supplier;

/**
 * C2S: player pressed the Z-Soul "add" button next to a stat in DMZ's stats GUI. The server (the Ragnarok Key,
 * through {@link RaidKeyHooks#get()}) buys as many beyond-cap points as the player can afford at DMZ's TP cost,
 * banks them in {@code ZSoulData}, and re-projects the overlay. All validation is server-side, so a spoofed
 * packet cannot cheat. The codec and id stay here.
 */
public class ZSoulInvestC2S {

    private final int statOrdinal;
    private final int multiplier;

    public ZSoulInvestC2S(ZStat stat, int multiplier) {
        this.statOrdinal = stat.ordinal();
        this.multiplier = multiplier;
    }

    private ZSoulInvestC2S(int statOrdinal, int multiplier) {
        this.statOrdinal = statOrdinal;
        this.multiplier = multiplier;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(statOrdinal);
        buf.writeVarInt(multiplier);
    }

    public static ZSoulInvestC2S decode(FriendlyByteBuf buf) {
        return new ZSoulInvestC2S(buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) return;
            // The purchase itself (validation, TP curve, banking, overlay) is private and lives in the key.
            // Keyless: ignored, as before (Z-Souls were never enabled without the key).
            RaidKeyHooks.get().investZSoul(sp, statOrdinal, multiplier);
        });
        ctx.get().setPacketHandled(true);
    }
}
