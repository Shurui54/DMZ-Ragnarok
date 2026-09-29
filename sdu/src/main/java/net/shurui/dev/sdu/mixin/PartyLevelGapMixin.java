package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.PartyManager;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.quest.PartyLevelGap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

// Enforces DMZ's party level gap against EVERY member at both join steps. See PartyLevelGap for the two holes in DMZ's
// own check (accept compares the invitee with themselves; invite compares with the leader only). Both answers reuse
// DMZ's own LEVEL_GAP results, so the player gets DMZ's existing "level gap" message. require = 0, and any failure
// falls through to DMZ's own logic.
@Mixin(value = PartyManager.class, remap = false)
public class PartyLevelGapMixin {

    @Inject(method = "requestInvite(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerPlayer;)Lcom/dragonminez/common/quest/PartyManager$InviteRequestResult;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$inviteLevelGap(ServerPlayer inviter, ServerPlayer invitee,
                                          CallbackInfoReturnable<PartyManager.InviteRequestResult> cir) {
        try {
            if (inviter == null || invitee == null || inviter.getUUID().equals(invitee.getUUID())
                    || PartyManager.isInParty(invitee)) {
                return; // DMZ answers these itself
            }
            List<ServerPlayer> members = PartyManager.isInParty(inviter)
                    ? new ArrayList<>(PartyManager.getAllPartyMembers(inviter))
                    : new ArrayList<>(List.of(inviter));
            if (PartyLevelGap.violates(invitee, members)) {
                cir.setReturnValue(PartyManager.InviteRequestResult.LEVEL_GAP);
            }
        } catch (Throwable ignored) {
            // DMZ's own checks still run
        }
    }

    @Inject(method = "acceptInvite(Lnet/minecraft/server/level/ServerPlayer;Z)Lcom/dragonminez/common/quest/PartyManager$InviteAcceptResult;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$acceptLevelGap(ServerPlayer invitee, boolean confirmedDifficultyChange,
                                          CallbackInfoReturnable<PartyManager.InviteAcceptResult> cir) {
        try {
            StatsData stats = DmzForms.stats(invitee);
            PlayerQuestData qd = stats == null ? null : stats.getPlayerQuestData();
            PlayerQuestData.PartyInviteData invite = qd == null ? null : qd.getPendingPartyInviteData();
            if (invite == null || invite.getPartyLeaderId() == null || invitee.getServer() == null) {
                return;
            }
            ServerPlayer leader = invitee.getServer().getPlayerList().getPlayer(invite.getPartyLeaderId());
            if (leader == null) {
                return; // DMZ reports the invite as invalid
            }
            List<ServerPlayer> members = new ArrayList<>(PartyManager.getAllPartyMembers(leader));
            if (!members.contains(leader)) {
                members.add(leader);
            }
            if (PartyLevelGap.violates(invitee, members)) {
                cir.setReturnValue(PartyManager.InviteAcceptResult.LEVEL_GAP);
            }
        } catch (Throwable ignored) {
            // DMZ's own checks still run
        }
    }
}
