package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_tournaments.api.key.TournamentKeyHooks;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.DmzHooks;

import java.util.function.Supplier;

/**
 * C2S, when the player picks a stat in {@code StatGemScreen}. Carries only the hand and stat key; the amount
 * is NOT trusted from the client, it is read from the gem on the server. The handler rejects a spoofed stat key here;
 * the key, the DMZ character and the held gem are re-checked by the apply logic in the Ragnarok Key
 * ({@link TournamentKeyHooks}) before the gem is applied and consumed.
 */
public class StatGemChoicePacket {

    private final boolean mainHand;
    private final String statKey;

    public StatGemChoicePacket(InteractionHand hand, String statKey) {
        this.mainHand = hand == InteractionHand.MAIN_HAND;
        this.statKey = statKey;
    }

    private StatGemChoicePacket(boolean mainHand, String statKey) {
        this.mainHand = mainHand;
        this.statKey = statKey;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(mainHand);
        buf.writeUtf(statKey);
    }

    public static StatGemChoicePacket decode(FriendlyByteBuf buf) {
        boolean mainHand = buf.readBoolean();
        // cap the key length so a hostile client can't stream an oversized string; real keys are 3 chars
        return new StatGemChoicePacket(mainHand, buf.readUtf(16));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) {
                return;
            }
            // reject a spoofed stat first
            if (!DmzHooks.isCoreStat(statKey)) {
                return;
            }
            // Applying a gem is private: the key re-checks the key gate, the DMZ character and the held gem, then
            // applies the gem's own amount (never a client number) and consumes it. Keyless: the "needs the key"
            // line and the gem is kept, as before.
            InteractionHand hand = mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            TournamentKeyHooks.get().useStatGem(sp, hand, statKey);
        });
        ctx.get().setPacketHandled(true);
    }
}
