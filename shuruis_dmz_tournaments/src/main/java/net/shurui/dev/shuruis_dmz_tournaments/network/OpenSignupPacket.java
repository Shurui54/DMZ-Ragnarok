package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C, on opening a tournament's sign-up screen. For team formats it also carries the pending-team roster
 * so the screen can render "Join Team".
 */
public class OpenSignupPacket {
    private final String defId;
    private final String name;
    private final boolean signupOpen;
    private final boolean signedUp;
    private final int count;
    private final int stateOrdinal;
    private final int formatOrdinal;
    private final int teamSize;
    private final List<TournamentInstance.TeamView> teams;

    public OpenSignupPacket(String defId, String name, boolean signupOpen, boolean signedUp, int count,
                            int stateOrdinal, int formatOrdinal, int teamSize, List<TournamentInstance.TeamView> teams) {
        this.defId = defId;
        this.name = name;
        this.signupOpen = signupOpen;
        this.signedUp = signedUp;
        this.count = count;
        this.stateOrdinal = stateOrdinal;
        this.formatOrdinal = formatOrdinal;
        this.teamSize = teamSize;
        this.teams = teams == null ? List.of() : teams;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(defId);
        buf.writeUtf(name);
        buf.writeBoolean(signupOpen);
        buf.writeBoolean(signedUp);
        buf.writeVarInt(count);
        buf.writeVarInt(stateOrdinal);
        buf.writeVarInt(formatOrdinal);
        buf.writeVarInt(teamSize);
        buf.writeVarInt(teams.size());
        for (TournamentInstance.TeamView t : teams) {
            buf.writeVarInt(t.id());
            buf.writeUtf(t.name());
            buf.writeVarInt(t.members().size());
            for (String m : t.members()) buf.writeUtf(m);
        }
    }

    public static OpenSignupPacket decode(FriendlyByteBuf buf) {
        String defId = buf.readUtf();
        String name = buf.readUtf();
        boolean signupOpen = buf.readBoolean();
        boolean signedUp = buf.readBoolean();
        int count = buf.readVarInt();
        int stateOrdinal = buf.readVarInt();
        int formatOrdinal = buf.readVarInt();
        int teamSize = buf.readVarInt();
        int n = buf.readVarInt();
        List<TournamentInstance.TeamView> teams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = buf.readVarInt();
            String tname = buf.readUtf();
            int mc = buf.readVarInt();
            List<String> members = new ArrayList<>(mc);
            for (int j = 0; j < mc; j++) members.add(buf.readUtf());
            teams.add(new TournamentInstance.TeamView(id, tname, members));
        }
        return new OpenSignupPacket(defId, name, signupOpen, signedUp, count, stateOrdinal, formatOrdinal, teamSize, teams);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_dmz_tournaments.client.ClientPacketHandler
                                .openSignup(defId, name, signupOpen, signedUp, count, stateOrdinal,
                                        formatOrdinal, teamSize, teams)));
        ctx.get().setPacketHandled(true);
    }
}
