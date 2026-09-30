package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.PartyManager;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.quest.PartyLowestSaga;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The other half of the party lowest-saga rule (see PartyLowestSaga / QuestServicePartyLowestStartMixin). DMZ's
// resolveQuestController always returns the party LEADER, so a saga step can only ever be run on the leader's quest
// data. While PartyLowestSaga is re-invoking startQuest to run the party's LOWEST step, it needs that step accepted
// and spawned against the member who is actually on it (the behind member), not the leader who may have finished it.
//
// This returns PartyLowestSaga's controller override for exactly that in-flight start (a ThreadLocal, set only for
// the duration of the one synchronous re-invocation on the server thread) and otherwise leaves DMZ's answer alone.
// So syncPartyQuestState and every other resolveQuestController caller behave exactly as before whenever we are not
// steering. All merges DMZ then runs are raise-only, so no member's progress is ever lowered.
//
// require = 0 (a DMZ rename no-ops, degrading to DMZ's own controller, never a crash); remap = false, official names.
@Mixin(value = PartyManager.class, remap = false)
public abstract class PartyControllerOverrideMixin {

    @Inject(
            method = "resolveQuestController(Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At("HEAD"),
            require = 0,
            cancellable = true,
            remap = false)
    private static void sdu$overrideController(ServerPlayer player, CallbackInfoReturnable<ServerPlayer> cir) {
        try {
            ServerPlayer override = PartyLowestSaga.controllerOverride();
            if (override != null) {
                cir.setReturnValue(override);
            }
        } catch (Throwable ignored) {
            // Never break controller resolution: on any error, DMZ's own logic decides.
        }
    }
}
