package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentManager;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.function.Supplier;

/**
 * C2S. A sign-up screen button for a specific tournament. Server acts on the {@link TournamentInstance} and
 * replies with chat feedback; team actions also re-send the sign-up screen so its roster refreshes.
 */
public class SignupActionPacket {
    public static final int JOIN = 0;
    public static final int LEAVE = 1;
    public static final int TPWAIT = 2;
    public static final int STATUS = 3;
    public static final int CREATE_TEAM = 4;
    public static final int JOIN_TEAM = 5;
    public static final int LEAVE_TEAM = 6;

    private final String defId;
    private final int action;
    private final String arg;   // team name (CREATE_TEAM)
    private final int extra;    // team id (JOIN_TEAM)

    public SignupActionPacket(String defId, int action) {
        this(defId, action, "", 0);
    }

    public SignupActionPacket(String defId, int action, String arg, int extra) {
        this.defId = defId;
        this.action = action;
        this.arg = arg == null ? "" : arg;
        this.extra = extra;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(defId);
        buf.writeVarInt(action);
        buf.writeUtf(arg);
        buf.writeVarInt(extra);
    }

    public static SignupActionPacket decode(FriendlyByteBuf buf) {
        return new SignupActionPacket(buf.readUtf(), buf.readVarInt(), buf.readUtf(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) return;
            TournamentManager m = TournamentManager.get();
            TournamentInstance inst = m == null ? null : m.instance(defId);
            if (inst == null) {
                feedback(sp, "&cThat tournament no longer exists.");
                return;
            }
            switch (action) {
                case JOIN -> {
                    // a tournament open on another world routes the player there instead
                    if (net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentNetwork.routeJoin(sp, defId)) return;
                    switch (inst.join(sp)) {
                        case OK -> feedback(sp, "&aYou have signed up!");
                        case ALREADY -> feedback(sp, "&eYou are already signed up.");
                        case CLOSED -> feedback(sp, "&cSign-ups are not open.");
                        case FULL -> feedback(sp, "&cThe tournament is full.");
                        case NO_CHARACTER -> feedback(sp, "&cCreate a DragonMineZ character first.");
                        case TITLE_HOLDER -> feedback(sp, net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance.titleHolderRefusal(inst.barringTitlesFor(sp.getUUID())));
                        case NO_SUCH_TOURNAMENT -> feedback(sp, "&cThat tournament no longer exists.");
                    }
                }
                case LEAVE -> feedback(sp, inst.leave(sp.getUUID()) ? "&eYou have withdrawn." : "&cYou were not signed up.");
                case TPWAIT -> {
                    if (inst.isSignedUp(sp.getUUID()) || inst.isContestant(sp.getUUID())) {
                        if (!inst.teleportToWaiting(sp)) feedback(sp, "&cThe waiting area is not configured.");
                    } else {
                        feedback(sp, "&cOnly signed-up fighters can teleport to the waiting area.");
                    }
                }
                case STATUS -> sp.sendSystemMessage(inst.statusSummary());
                case CREATE_TEAM -> {
                    feedback(sp, inst.createTeam(sp, arg) ? "&aTeam created!" : "&cCould not create a team (sign-ups closed or full).");
                    TournamentNpcs.openSignup(sp, defId);
                }
                case JOIN_TEAM -> {
                    feedback(sp, inst.joinTeam(sp, extra) ? "&aJoined the team!" : "&cCould not join that team (full or missing).");
                    TournamentNpcs.openSignup(sp, defId);
                }
                case LEAVE_TEAM -> {
                    inst.leaveTeam(sp.getUUID());
                    feedback(sp, "&eYou left your team.");
                    TournamentNpcs.openSignup(sp, defId);
                }
                default -> { }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void feedback(ServerPlayer sp, String msg) {
        sp.sendSystemMessage(TextUtil.color(msg));
    }
}
