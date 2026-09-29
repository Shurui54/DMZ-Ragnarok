package net.shurui.dev.shuruis_dmz_tournaments.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.dev.shuruis_dmz_tournaments.character.TournamentFighters;

/**
 * S2C. The full set of players who currently hold a tournament character. Replace, not delta; sent on every
 * change and on login, so the client stat HUD applies the same flat multiplier the server enforces.
 */
public class FightersSyncPacket {
    private final List<UUID> ids;

    public FightersSyncPacket(java.util.Collection<UUID> ids) {
        this.ids = ids == null ? List.of() : new ArrayList<>(ids);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(ids.size());
        for (UUID id : ids) buf.writeUUID(id);
    }

    public static FightersSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<UUID> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUUID());
        return new FightersSyncPacket(out);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> TournamentFighters.setClient(ids)));
        ctx.get().setPacketHandled(true);
    }
}
