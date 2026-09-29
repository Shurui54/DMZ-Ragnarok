package net.shurui.dev.sdu.saga;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.cnpc.CnpcCloneSpawner;
import net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat;

/**
 * Decision point for feature 4.7. Called by {@code QuestServiceSpawnMixin}'s {@code @Redirect} in place of
 * DMZ's {@code entityType.create(level)} inside {@code QuestService.spawnKillObjectives}.
 *
 * <p>When the toggle is on, the CNPC port is present, and the DMZ entity is mapped in
 * {@link SagaNpcMap}, we return a detached Custom NPC clone instead of the native saga mob. DMZ's own code
 * then positions it, stamps every quest NBT tag (key/objective/owner/hp/melee/ki/ai_tier/transform...) and
 * calls {@code addFreshEntity} - so kill-credit and scaling are byte-for-byte what the native boss got. In
 * every other case (toggle off, CNPC absent, unmapped id, or clone build failed) we return DMZ's own entity,
 * so nothing regresses.</p>
 */
public final class SagaCloneSubstitution {

    private SagaCloneSubstitution() {
    }

    /** @return the entity DMZ should proceed with - a mapped clone, or the native {@code type.create(level)}. */
    public static Entity create(EntityType<?> type, Level level) {
        try {
            // Guard first so CnpcCloneSpawner (which references noppes.npcs.*) is only classloaded when CNPC
            // is actually present.
            if (SagaNpcMap.useCustomNpcForSagas() && DmzCnpcCompat.cnpcAvailable()) {
                SagaNpcMap.CloneRef ref = SagaNpcMap.lookup(type);
                if (ref != null) {
                    // Spawn a real DMZ saga fighter (true saga AI) that looks + fights like the saved Custom NPC.
                    Entity fighter = CnpcCloneSpawner.createFighterFromClone(ref.tab(), ref.name(), level);
                    if (fighter != null) {
                        DmzNpc.LOGGER.debug("[{}] Saga spawn: substituting {} with DMZ fighter from clone '{}' (tab {}).",
                                DmzNpc.MODID, EntityType.getKey(type), ref.name(), ref.tab());
                        return fighter;
                    }
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Saga clone substitution failed; using DMZ entity: {}", DmzNpc.MODID, t.toString());
        }
        return type.create(level);
    }
}
