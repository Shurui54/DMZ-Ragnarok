package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.Difficulty;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.HardSagaGate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Server-side gate on the HARD saga difficulty: a player may only lock a saga to HARD after prestiging at
// least once. Eligibility comes from HardSagaGate, the SAME hook the client screen consults, so the screen
// (which hides HARD) and this refusal can never disagree; a crafted packet asking for HARD is dropped here.
//
// DMZ runs the difficulty write inside the enqueueWork lambda (synthetic lambda$handle$1(Supplier)), NOT
// handle(), so we inject there where the sender resolves. We cancel BEFORE DMZ's cooldown map, party
// resolution and PlayerQuestData.setDifficulty run. EASY and NORMAL are never touched, and an already-running
// hard saga is left alone: this only intercepts the one-time selection packet.
//
// Player self-selection gate only. An operator or the saga editor sets difficulty through DMZ's own
// server-side paths, not this packet, so the admin path is untouched.
//
// remap=false, require=0. A broken binding silently drops the gate, so launch-test, do not trust a green build.
@Mixin(targets = "com.dragonminez.common.network.C2S.SetStoryDifficultyC2S", remap = false)
public abstract class SetStoryDifficultyC2SMixin {

    // DMZ's own final field carrying the difficulty the client asked for. Not a vanilla member, so no remap.
    @Shadow(remap = false)
    @Final
    private Difficulty difficulty;

    @Inject(
        method = "lambda$handle$1(Lnet/minecraftforge/network/NetworkEvent$Context;)V",
        at = @At("HEAD"),
        require = 0,
        cancellable = true,
        remap = false
    )
    private void sdu$gateHardDifficulty(NetworkEvent.Context ctx, CallbackInfo ci) {
        if (difficulty != Difficulty.HARD)
            return;
        ServerPlayer player = ctx.getSender();
        if (player == null || HardSagaGate.mayUseHard(player))
            return;
        player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.saga.hard_locked"));
        ci.cancel();
    }
}
