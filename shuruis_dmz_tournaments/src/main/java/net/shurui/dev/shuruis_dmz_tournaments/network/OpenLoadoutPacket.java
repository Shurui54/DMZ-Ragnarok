package net.shurui.dev.shuruis_dmz_tournaments.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C. Opens the tournament creation / attack picker with the pickable ki and strike ids (already filtered by the
 * server to what this player could legitimately hold, see TournamentMoveAccess) plus the current selection. Equips
 * exactly
 * {@link net.shurui.dev.shuruis_dmz_tournaments.character.TournamentAttacks#KI_SLOTS} ki and
 * {@link net.shurui.dev.shuruis_dmz_tournaments.character.TournamentAttacks#STRIKE_SLOTS} strike.
 * {@code creation} only changes the screen title.
 */
public class OpenLoadoutPacket {
    private final List<String> kiIds;
    private final List<String> strikeIds;
    private final List<String> selected;
    private final int kiMax;
    private final int strikeMax;
    private final boolean creation;

    public OpenLoadoutPacket(List<String> kiIds, List<String> strikeIds, List<String> selected,
                             int kiMax, int strikeMax, boolean creation) {
        this.kiIds = kiIds == null ? List.of() : kiIds;
        this.strikeIds = strikeIds == null ? List.of() : strikeIds;
        this.selected = selected == null ? List.of() : selected;
        this.kiMax = kiMax;
        this.strikeMax = strikeMax;
        this.creation = creation;
    }

    public void encode(FriendlyByteBuf buf) {
        writeList(buf, kiIds);
        writeList(buf, strikeIds);
        writeList(buf, selected);
        buf.writeVarInt(kiMax);
        buf.writeVarInt(strikeMax);
        buf.writeBoolean(creation);
    }

    public static OpenLoadoutPacket decode(FriendlyByteBuf buf) {
        List<String> ki = readList(buf);
        List<String> strike = readList(buf);
        List<String> sel = readList(buf);
        int kiMax = buf.readVarInt();
        int strikeMax = buf.readVarInt();
        boolean creation = buf.readBoolean();
        return new OpenLoadoutPacket(ki, strike, sel, kiMax, strikeMax, creation);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_dmz_tournaments.client.ClientPacketHandler
                                .openLoadout(kiIds, strikeIds, selected, kiMax, strikeMax, creation)));
        ctx.get().setPacketHandled(true);
    }

    private static void writeList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    private static List<String> readList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUtf());
        return out;
    }
}
