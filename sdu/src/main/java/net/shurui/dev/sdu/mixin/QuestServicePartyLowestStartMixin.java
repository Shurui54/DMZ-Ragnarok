package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.QuestService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.quest.PartyLowestSaga;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Party rule: a saga line is run at the LOWEST step any online same-server member is still on, until everyone has
// caught up. DMZ routes every party quest through the LEADER as sole controller (QuestService.startQuest ->
// PartyManager.resolveQuestController), so a mixed-progress party could not spawn saga NPCs: starting the leader's
// step returned "already active" for a behind member (the state is the leader's), and starting the behind step
// through the leader was refused the same way, so acceptQuest + spawnKillObjectives never ran. See PartyLowestSaga.
//
// This intercepts the PUBLIC startQuest(requester, questKey), the single funnel QuestActionC2S(START) uses. For a
// party saga quest that is ahead of the party's lowest step (or that the leader is ahead of), PartyLowestSaga steers
// the start onto the lowest step with the behind member as the effective controller (PartyControllerOverrideMixin),
// so DMZ's own start path accepts and spawns it. Solo, non-saga and already-aligned parties fall through to DMZ.
//
// require = 0 so a DMZ rename no-ops (fails to today's behaviour, not a crash); remap = false, DMZ official names.
// Everything in PartyLowestSaga is guarded and fails open, so a steering error also degrades to DMZ's own start.
@Mixin(value = QuestService.class, remap = false)
public abstract class QuestServicePartyLowestStartMixin {

    @Inject(
            method = "startQuest(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"),
            require = 0,
            cancellable = true,
            remap = false)
    private static void sdu$partyLowestStep(ServerPlayer requester, String questKey,
                                            CallbackInfoReturnable<Component> cir) {
        // When PartyLowestSaga takes over, it calls the consumer (cir::setReturnValue), which also cancels DMZ's
        // own start, so the quest is not started twice. When it declines, nothing is set and DMZ runs unchanged.
        PartyLowestSaga.handleStart(requester, questKey, cir::setReturnValue);
    }
}
