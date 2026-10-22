package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.Difficulty;
import com.dragonminez.common.quest.QuestService;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls DragonMineZ 2.2's private {@code QuestService.spawnKillObjectives(ServerPlayer, ResolvedQuest, int, Difficulty)},
 * the spawn {@link QuestServiceSpawnMixin} defers. 2.1 exposed it as the public {@code spawnKillObjectivesForQuest};
 * 2.2 removed that wrapper, and {@code DeferredSpawnHandler} must replay exactly the spawn it held back (same party
 * scaling, owner and quest tags), not re-run a whole quest start.
 */
@Mixin(value = QuestService.class, remap = false)
public interface QuestServiceInvoker {
    @Invoker(value = "spawnKillObjectives", remap = false)
    static void sdu$spawnKillObjectives(ServerPlayer requester, QuestService.ResolvedQuest resolved, int partySize,
                                        Difficulty difficulty) {
        throw new AssertionError("mixin invoker not applied");
    }
}
