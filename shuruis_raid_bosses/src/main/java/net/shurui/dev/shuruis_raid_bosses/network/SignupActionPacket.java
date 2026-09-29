package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidInstance;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidManager;

import java.util.function.Supplier;

/**
 * C2S: a sign-up screen button for a specific raid. Server runs the action against that
 * {@link RaidInstance} and replies with chat feedback.
 */
public class SignupActionPacket {
    public static final int JOIN = 0;
    public static final int LEAVE = 1;
    public static final int TP_ARENA = 2;
    public static final int STATUS = 3;

    private final String defId;
    private final int action;

    public SignupActionPacket(String defId, int action) {
        this.defId = defId;
        this.action = action;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(defId);
        buf.writeVarInt(action);
    }

    public static SignupActionPacket decode(FriendlyByteBuf buf) {
        return new SignupActionPacket(buf.readUtf(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) return;
            RaidManager m = RaidManager.get();
            RaidInstance inst = m == null ? null : m.instance(defId);
            if (inst == null) {
                feedback(sp, "command.dmz_ragnarok.raid.no_such_raid");
                return;
            }
            switch (action) {
                case JOIN -> {
                    // A raid on another open world routes the player there instead of joining locally.
                    if (net.shurui.dev.shuruis_raid_bosses.raid.RaidNetwork.routeJoin(sp, defId)) return;
                    switch (inst.join(sp)) {
                        case OK -> feedback(sp, "signup.dmz_ragnarok.raid.joined");
                        case ALREADY -> feedback(sp, "command.dmz_ragnarok.raid.join.already");
                        case CLOSED -> feedback(sp, "signup.dmz_ragnarok.raid.closed");
                        case FULL -> feedback(sp, "signup.dmz_ragnarok.raid.full");
                        case NO_CHARACTER -> feedback(sp, "command.dmz_ragnarok.raid.no_character");
                        case NO_SUCH_RAID -> feedback(sp, "command.dmz_ragnarok.raid.no_such_raid");
                    }
                }
                case LEAVE -> feedback(sp, inst.leave(sp.getUUID())
                        ? "command.dmz_ragnarok.raid.leave.ok" : "command.dmz_ragnarok.raid.leave.not_signed_up");
                case TP_ARENA -> {
                    if (inst.isSignedUp(sp.getUUID()) || inst.isParticipant(sp.getUUID())) {
                        if (!inst.teleportToArena(sp)) feedback(sp, "signup.dmz_ragnarok.raid.arena_not_configured");
                    } else {
                        feedback(sp, "signup.dmz_ragnarok.raid.only_signed_up_tp");
                    }
                }
                case STATUS -> sp.sendSystemMessage(inst.statusSummary());
                default -> { }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void feedback(ServerPlayer sp, String key) {
        sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(key));
    }
}
