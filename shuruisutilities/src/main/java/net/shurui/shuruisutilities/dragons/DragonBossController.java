package net.shurui.shuruisutilities.dragons;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Makes each live shadow dragon boss use its OWN signature move.
 *
 * <p>A tick-driven controller rather than an AI goal, because a boss is whatever entity type its slot is configured
 * with (the default is a DMZ saga entity, but an admin may set any living entity). There is no fixed class to attach
 * a goal to, and rewriting an arbitrary mob's goal set at spawn would fight whatever AI it already has. Driving the
 * casts from outside leaves each boss's own AI to handle movement and melee exactly as it does now.
 *
 * <p>MOVE PER SLOT, NOT THE WHOLE KIT. {@link DragonMove#bySlot} gives each boss only its own dragon's move. A
 * player who transforms into Omega inherits every dragon's move; a boss never does, including the Omega boss, whose
 * one move is the Minus Energy Power Ball.
 *
 * <p>COOLDOWN, NOT MALICE. Bosses have no energy bar, so the pacing here is a per-boss cooldown. It is deliberately
 * long relative to the player cost: a boss casts continuously for the whole fight, where a player is limited by a
 * bar they have to earn back.
 */
public final class DragonBossController
{
    /** Ticks between a boss's casts. */
    private static final int CAST_COOLDOWN_TICKS = 200;

    /** How often the controller looks at the bosses at all, in ticks. */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** A boss only casts when something it could hit is within this far, so it does not cast at an empty arena. */
    private static final double ENGAGE_RANGE = 24.0;

    // boss uuid -> the game time its cooldown expires. Runtime only: a restart despawns the fight anyway.
    private static final Map<UUID, Long> nextCast = new HashMap<>();

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (++tickCounter < SCAN_INTERVAL_TICKS)
            return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null)
            return;

        Map<UUID, Integer> live = net.shurui.shuruisutilities.corrupted.ShadowDragonStorage.get(server)
                .getLiveDragons();
        if (live.isEmpty())
        {
            // Nothing live: drop any cooldown rows so the map cannot grow across repeated encounters.
            if (!nextCast.isEmpty())
                nextCast.clear();
            return;
        }

        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, Integer> entry : live.entrySet())
        {
            DragonMove move = DragonMove.bySlot(entry.getValue());
            if (move == null)
                continue;
            LivingEntity boss = findLiving(server, entry.getKey());
            if (boss == null || !boss.isAlive())
                continue;
            Long ready = nextCast.get(entry.getKey());
            if (ready != null && now < ready)
                continue;
            if (!hasSomethingToHit(boss))
                continue;

            try
            {
                DragonMoveEffects.cast(boss, move);
            }
            catch (Throwable t)
            {
                net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                        "[dragons] boss slot {} failed to cast {}: {}", entry.getValue(), move.id, t.toString());
            }
            nextCast.put(entry.getKey(), now + CAST_COOLDOWN_TICKS);
        }
    }

    /**
     * A boss casts only when it has a live target, or failing that anything it is allowed to hit nearby, so an
     * unattended arena is silent instead of pulsing effects at nobody.
     */
    private static boolean hasSomethingToHit(LivingEntity boss)
    {
        if (boss instanceof Mob mob)
        {
            LivingEntity target = mob.getTarget();
            if (target != null && target.isAlive() && !DragonMoveEffects.isImmuneShadowDragon(target))
                return true;
        }
        return boss.level() instanceof ServerLevel level
                && !DragonMoveEffects.targets(boss, level, ENGAGE_RANGE).isEmpty();
    }

    /** Find a live boss by uuid across the server's levels. */
    private static LivingEntity findLiving(MinecraftServer server, UUID id)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity living)
                return living;
        }
        return null;
    }
}
