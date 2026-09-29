package net.shurui.shuruisutilities.combat;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Fighting NPCs close the distance with a dash of their own, and can be met head on in a clash.
 *
 * <p>Before this, dashing was something only players did, which made it read as a player ability rather than as how
 * everyone in this world fights. A saga fighter or a raid boss would walk at you while you dashed circles around it.
 *
 * <h2>When an NPC dashes</h2>
 * Only a mob that is genuinely fighting a player: it has that player as its target, it is in the open at mid range,
 * and it is roughly facing them. Close range is left alone because a dash that starts on top of its target just
 * shoves it, and long range is left alone because a dash across half a chunk reads as a teleport. On top of that it
 * has to pass a per-NPC cooldown and a random roll, so a fight has dashes in it rather than being made of them.
 *
 * <h2>The lunge is server driven, unlike a player's</h2>
 * A player's dash is planned on the server and FLOWN by their own client, because that is the only way to make it
 * feel responsive on the machine holding the keyboard. An NPC has no client, so there is nothing to hand the route
 * to and the server simply moves it. That is also why this is a straight lunge rather than one of the arcs: the arc
 * modes exist so a player can choose where to end up, and an NPC choosing to flank is a decision this is not trying
 * to make for it.
 *
 * <h2>Meeting one head on</h2>
 * While an NPC is lunging, a player dashing back into it is a clash, on the same terms as clashing another player:
 * both are held, the rhythm chart runs, and the loser is thrown. The NPC's side is simulated from its power, since
 * it has no client to play the chart on. See {@link MeleeClashService#beginNpcClash}.
 */
public final class NpcDash
{
    private NpcDash() {}

    // Entities from these mods are the ones that fight like DragonMineZ characters, so they are the ones worth
    // giving a dash to. Everything else keeps whatever movement its own mod gave it.
    private static final Set<String> FIGHTING_MODS = Set.of(
            "dragonminez", "sdu", "dmz_ragnarok", "shuruisutilities",
            "shuruis_dmz_dungeons", "shuruis_raid_bosses", "shuruis_dmz_tournaments");

    /** Mid range band a dash is worth taking from. */
    private static final double MIN_DASH_RANGE = 6.0D;
    private static final double MAX_DASH_RANGE = 26.0D;

    /** How square onto the target an NPC has to be before it commits. */
    private static final double FACING_DOT = 0.55D;

    /** Blocks per tick the lunge travels. Deliberately below a player's dash: an NPC should be catchable. */
    private static final double LUNGE_SPEED = 1.35D;

    /** How long a lunge runs before it is done, whether or not it arrived. */
    private static final int LUNGE_TICKS = 10;

    /** Ticks between dashes for one NPC, and the chance it takes one when it is otherwise able to. */
    private static final int DASH_COOLDOWN_TICKS = 90;
    private static final double DASH_CHANCE = 0.06D;

    /** How close a lunging NPC and a dashing player must be for the two to meet in a clash. */
    private static final double CLASH_DISTANCE = 6.0D;

    /** How opposed the two have to be moving. Matches the player-to-player rule. */
    private static final double CLASH_DOT = -0.6D;

    /** Only scan every few ticks: this walks the entity list and a fight does not change shape within 200ms. */
    private static final int SCAN_INTERVAL_TICKS = 4;

    private record Lunge(UUID target, Vec3 heading, int ticksLeft) {}

    private static final Map<UUID, Lunge> LUNGING = new HashMap<>();
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    private static int scanTimer;

    /**
     * Whether an EntityType fights like a DragonMineZ character, cached per type. The namespace lookup through
     * ForgeRegistries never changes for a given type, and the scan asks it for every mob it considers, so the
     * answer is memoised here. EntityType instances are singletons, so identity keying is exact and cheap.
     * Server-thread only (the scan runs on the server tick).
     */
    private static final Map<EntityType<?>, Boolean> FIGHTER_TYPE = new IdentityHashMap<>();

    /** Whether this entity is mid lunge. */
    public static boolean isLunging(Entity entity)
    {
        return entity != null && LUNGING.containsKey(entity.getUUID());
    }

    public static void clear()
    {
        LUNGING.clear();
        COOLDOWN.clear();
    }

    /** Called once per server tick, after the player dash and clash services. */
    public static void tick(MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        tickCooldowns();
        advanceLunges(server);

        if (--scanTimer > 0)
        {
            return;
        }
        scanTimer = SCAN_INTERVAL_TICKS;
        for (ServerLevel level : server.getAllLevels())
        {
            scan(level);
        }
    }

    private static void tickCooldowns()
    {
        for (Iterator<Map.Entry<UUID, Integer>> it = COOLDOWN.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0)
                it.remove();
            else
                e.setValue(left);
        }
    }

    // Move every lunging NPC along its heading, and look for a player dashing back into it.
    private static void advanceLunges(MinecraftServer server)
    {
        if (LUNGING.isEmpty())
        {
            return;
        }
        for (Iterator<Map.Entry<UUID, Lunge>> it = LUNGING.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Lunge> entry = it.next();
            Lunge lunge = entry.getValue();
            LivingEntity npc = find(server, entry.getKey());
            if (npc == null || !npc.isAlive() || lunge.ticksLeft() <= 0)
            {
                it.remove();
                continue;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(lunge.target());
            if (target == null || !target.isAlive() || target.level() != npc.level())
            {
                it.remove();
                continue;
            }

            if (meetsHeadOn(npc, target, lunge))
            {
                it.remove();
                COOLDOWN.put(npc.getUUID(), DASH_COOLDOWN_TICKS);
                MeleeClashService.beginNpcClash(target, npc);
                continue;
            }

            try
            {
                npc.setDeltaMovement(lunge.heading().scale(LUNGE_SPEED));
                npc.hurtMarked = true;
                npc.fallDistance = 0.0F;
            }
            catch (Throwable ignored)
            {
            }
            entry.setValue(new Lunge(lunge.target(), lunge.heading(), lunge.ticksLeft() - 1));
        }
    }

    /**
     * Whether a lunging NPC and its target are about to meet head on, which is what turns a lunge into a clash.
     *
     * <p>The player has to be dashing too. An NPC lunging at someone standing still is just an attack run, and
     * turning that into a ten second minigame would mean being dragged into a clash without having chosen one.
     */
    private static boolean meetsHeadOn(LivingEntity npc, ServerPlayer target, Lunge lunge)
    {
        if (!DashService.isDashing(target))
        {
            return false;
        }
        if (npc.distanceTo(target) > CLASH_DISTANCE)
        {
            return false;
        }
        Vec3 playerHeading = DashService.heading(target);
        if (playerHeading == null)
        {
            return false;
        }
        return playerHeading.dot(lunge.heading()) <= CLASH_DOT;
    }

    // Look for NPCs in a fight who could take a dash at their target.
    private static void scan(ServerLevel level)
    {
        for (Entity entity : level.getAllEntities())
        {
            if (!(entity instanceof Mob mob) || !mob.isAlive())
            {
                continue;
            }
            // Cheapest meaningful gate first: the vast majority of mobs are not aiming at a player, so this
            // field read rejects them before the map probes and the (cached) fighter-type check run.
            if (!(mob.getTarget() instanceof ServerPlayer target) || !target.isAlive())
            {
                continue;
            }
            UUID id = mob.getUUID();
            if (LUNGING.containsKey(id) || COOLDOWN.containsKey(id) || !isFighter(mob))
            {
                continue;
            }
            double distance = mob.distanceTo(target);
            if (distance < MIN_DASH_RANGE || distance > MAX_DASH_RANGE)
            {
                continue;
            }

            Vec3 toTarget = target.position().subtract(mob.position());
            if (toTarget.lengthSqr() < 1.0E-6D)
            {
                continue;
            }
            toTarget = toTarget.normalize();
            Vec3 facing = mob.getLookAngle();
            if (facing.lengthSqr() < 1.0E-6D || facing.normalize().dot(toTarget) < FACING_DOT)
            {
                continue;
            }
            // The roll is last, so an NPC that fails it has already been confirmed as able to dash and simply chose
            // not to this time. That keeps the cadence irregular without making the conditions themselves random.
            if (level.getRandom().nextDouble() > DASH_CHANCE)
            {
                continue;
            }

            LUNGING.put(id, new Lunge(target.getUUID(), toTarget, LUNGE_TICKS));
            COOLDOWN.put(id, DASH_COOLDOWN_TICKS + LUNGE_TICKS);
        }
    }

    private static boolean isFighter(Mob mob)
    {
        EntityType<?> type = mob.getType();
        Boolean cached = FIGHTER_TYPE.get(type);
        if (cached != null)
        {
            return cached;
        }
        boolean result;
        try
        {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
            result = key != null && FIGHTING_MODS.contains(key.getNamespace());
        }
        catch (Throwable t)
        {
            result = false;
        }
        FIGHTER_TYPE.put(type, result);
        return result;
    }

    private static LivingEntity find(MinecraftServer server, UUID id)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            if (level.getEntity(id) instanceof LivingEntity living)
            {
                return living;
            }
        }
        return null;
    }
}
