package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.Difficulty;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.QuestService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.event.DeferredSpawnHandler;
import net.shurui.dev.sdu.saga.SagaCloneSubstitution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Routes DMZ's quest kill-NPC spawn through our location gate. If a quest is "spawn only at quest location"
// and the player hasn't reached the COORDS objective, DeferredSpawnHandler cancels the spawn and replays it
// on arrival. require=0/guarded so a DMZ change can't break quest spawning.
//
// Hooks the private spawnKillObjectives(player, ResolvedQuest, pqd, count, difficulty), the single funnel DMZ
// routes every spawn through (quest accept, resummon, and the public spawnKillObjectivesForQuest we replay
// with). Quest key comes off the ResolvedQuest.
@Mixin(value = QuestService.class, remap = false)
public class QuestServiceSpawnMixin {

    @Inject(method = "spawnKillObjectives(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/quest/QuestService$ResolvedQuest;Lcom/dragonminez/common/quest/PlayerQuestData;ILcom/dragonminez/common/quest/Difficulty;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$deferSpawn(ServerPlayer player, QuestService.ResolvedQuest rq, PlayerQuestData pqd,
                                       int count, Difficulty difficulty, CallbackInfo ci) {
        DeferredSpawnHandler.onSpawnAttempt(player, rq.questKey(), count, difficulty, ci);
    }

    // swap a Custom NPC clone in for the native saga mob at the instantiation point, so DMZ then positions it
    // and stamps the quest NBT onto our NPC (kill-credit + scaling unchanged). Falls through to the DMZ entity
    // when unmapped / toggle off / CNPC absent (see SagaCloneSubstitution). Target is a vanilla method, so its
    // @At remaps to SRG even though the enclosing DMZ method doesn't.
    @Redirect(method = "spawnKillObjectives(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/quest/QuestService$ResolvedQuest;Lcom/dragonminez/common/quest/PlayerQuestData;ILcom/dragonminez/common/quest/Difficulty;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;",
                    remap = true),
            require = 0)
    private static Entity sdu$substituteSagaSpawn(EntityType<?> instance, Level level) {
        return SagaCloneSubstitution.create(instance, level);
    }
}
