package net.shurui.dev.sdu.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.quest.QuestMobOwnership;

/**
 * Keeps a DMZ quest kill mob fighting the player it was spawned for, and nobody else.
 *
 * <h2>The problem</h2>
 *
 * <p>{@code QuestService.spawnKillObjectives} spawns the mob, stamps the accepting player's UUID into
 * {@code dmz_quest_owner}, and calls {@code setTarget(requester)} once. That first target is the only thing
 * tying the mob to its owner. From the next tick the mob is an ordinary hostile: its own
 * {@code NearestAttackableTargetGoal} retargets whoever is closest and {@code HurtByTargetGoal} retargets
 * whoever hit it. On a populated server a quest mob therefore drifts onto passers-by within seconds.
 *
 * <p>That is worse than a nuisance, because DMZ's kill credit does NOT drift with it: a kill only counts when
 * the mob's owner is the killer or one of the killer's current party members
 * ({@code QuestEvents.matchesQuestSpawnTags}). So the bystander being chased cannot end the fight in any way
 * that matters, and every hit they land is progress on somebody else's quest that they will never be credited
 * with. Aggro and credit pointing at different people is the actual defect; this makes them agree.
 *
 * <h2>The rule</h2>
 *
 * <p>A quest mob may target the owner, or anyone currently in the owner's party, and no other player. That is
 * deliberately the SAME predicate DMZ credits kills with ({@link QuestMobOwnership#creditsPartyOf}), so the
 * people who can be attacked are exactly the people who can finish the quest. Helping a friend still works,
 * because joining their party is what makes their quest yours in DMZ's eyes too.
 *
 * <h2>What it deliberately leaves alone</h2>
 *
 * <ul>
 *   <li><b>Non-quest mobs.</b> Both tags must be present and usable ({@link QuestMobOwnership#isQuestMob}), so
 *       ordinary hostiles, region NPCs and raid bosses are untouched. Shadow dummies carry an owner tag but no
 *       quest key, so they are out of scope too and keep their current behaviour.</li>
 *   <li><b>Clearing a target.</b> A null new target is always allowed: vetoing that would pin a mob to a target
 *       it is supposed to be forgetting.</li>
 *   <li><b>Non-player targets.</b> Whatever mob-versus-mob logic DMZ or another addon sets up is not ours to
 *       veto. Only PLAYER targets are filtered, because the complaint and the credit rule are both about
 *       players.</li>
 * </ul>
 *
 * <h2>Failure direction</h2>
 *
 * <p>Fails OPEN, at every level. An unknown ownership verdict (null), a non-server player, or any thrown
 * exception all leave the target acquisition alone. A quest mob that aggroes too widely is today's behaviour; a
 * quest mob that cannot acquire a target at all is an unkillable objective and a stuck quest, which is a far
 * worse thing to ship. The handler never cancels except on a definite FALSE.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class QuestMobAggroLock {

    private QuestMobAggroLock() {
    }

    /**
     * Fires for BOTH goal-driven acquisition and hurt retaliation, and is cancelable, which is what makes it the
     * right hook: a quest mob that a stranger punches must not latch onto the stranger either.
     */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        try {
            if (!(event.getEntity() instanceof Mob mob) || !QuestMobOwnership.isQuestMob(mob)) {
                return; // not a DMZ quest kill mob: leave every other entity's aggression alone
            }
            LivingEntity newTarget = event.getNewTarget();
            if (!(newTarget instanceof ServerPlayer player)) {
                return; // clearing the target, or targeting something that is not a player: not ours to veto
            }
            if (Boolean.FALSE.equals(QuestMobOwnership.creditsPartyOf(mob, player))) {
                // Definitely someone else's quest mob. They could not be credited for killing it, so it has no
                // business fighting them.
                event.setCanceled(true);
            }
        } catch (Throwable t) {
            // Fail open: never let this make a quest objective untargetable.
            DmzNpc.LOGGER.debug("[{}] quest-mob aggro lock failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /**
     * Stops a player who is not the owner (nor in the owner's party) from damaging, and so killing, someone
     * else's DMZ quest kill mob.
     *
     * <p>The aggro lock above only decides who the mob may FIGHT; it does nothing about incoming damage, so a
     * bystander could still walk up and beat a quest boss to death. DMZ gives that bystander no kill credit
     * ({@code QuestEvents.matchesQuestSpawnTags}), and the mob's real owner is left with a finished objective
     * they never got, no mob to fight and, for a non-location quest like GIANT MONKEY (saiyan_saga:6), nothing
     * to make it respawn: the owner has to restart or ask staff. Refusing the hit at the source is the clean
     * fix. It also makes damage agree with aggro and with credit, which is the invariant this class exists to
     * hold.
     *
     * <p>Only a definite FALSE from {@link QuestMobOwnership#creditsPartyOf} cancels. A player who owns the mob
     * or is in the owner's party (TRUE), a non-player source (environment, another mob, the mob itself), or an
     * unresolvable owner (null) all pass through untouched: fail OPEN, so a lookup failure can never make a
     * quest mob invulnerable and wedge the objective. Cancelled at {@link LivingAttackEvent}, the earliest
     * cancelable damage hook, so no damage or knockback is applied.
     */
    @SubscribeEvent
    public static void onQuestMobHurt(LivingAttackEvent event) {
        try {
            if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()
                    || !QuestMobOwnership.isQuestMob(mob)) {
                return; // not a DMZ quest kill mob: leave every other entity's damage alone
            }
            Entity attacker = event.getSource().getEntity();
            if (!(attacker instanceof ServerPlayer player)) {
                return; // not a player-dealt hit: not ours to veto
            }
            if (Boolean.FALSE.equals(QuestMobOwnership.creditsPartyOf(mob, player))) {
                // Definitely someone else's quest mob. A kill would credit nobody and strand the owner.
                event.setCanceled(true);
            }
        } catch (Throwable t) {
            // Fail open: never let this make a quest objective invulnerable.
            DmzNpc.LOGGER.debug("[{}] quest-mob damage guard failed: {}", DmzNpc.MODID, t.toString());
        }
    }
}
