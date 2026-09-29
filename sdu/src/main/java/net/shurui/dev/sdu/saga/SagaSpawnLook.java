package net.shurui.dev.sdu.saga;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.compat.ragnarok.RagnarokLook;

/**
 * Dresses a quest-spawned entity in the ragnarok NPC character its KILL objective asked for.
 *
 * <p>The objective's own picker cannot do this by itself: the whole ragnarok cast, the ninjin characters
 * included, shares ONE registered entity type, so an objective pointed at it spawns the default look no matter
 * which character the editor chose. {@link SagaSpawnBindings} keeps the chosen character indexed by
 * {@code dmz_quest_key|dmz_quest_objective_index}, and this handler is where that index meets the spawn.
 *
 * <p>Why the join event rather than the substitution hook: DMZ stamps every {@code dmz_quest_*} tag inside
 * {@code QuestService#spawnKillObjectives} BEFORE it calls {@code addFreshEntity} (the tags are written at
 * bytecode offset 360, the add at 789), so by the time the join fires the objective index is already on the
 * entity. The substitution redirect earlier in that method sees only an {@code EntityType} and could not tell
 * one objective from another. Joining is also still early enough that the character rides the entity's FIRST
 * sync packet, so nobody watches a default model for a tick.
 *
 * <p>Everything is guarded and fail-open: a look is cosmetic and must never stop a quest boss spawning. Only
 * genuine quest spawns carry the tags, so a hand-placed NPC is never touched.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class SagaSpawnLook {

    private SagaSpawnLook() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        try {
            Entity entity = event.getEntity();
            String modelId = SagaSpawnBindings.matchLook(entity.getPersistentData());
            if (modelId != null) {
                RagnarokLook.apply(entity, modelId);
            }
        } catch (Throwable ignored) {
            // Cosmetic only: never interfere with the spawn itself.
        }
    }
}
