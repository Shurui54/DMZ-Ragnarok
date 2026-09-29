package net.shurui.shuruisutilities.regen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Puts the NPCs back too.
 *
 * <p>Restoring the ground under a master, a shopkeeper or a raid boss is only half a repair: the blast already dropped
 * them into the hole, so refilling it leaves them buried, standing in the wrong place, or gone entirely if they fell
 * far enough. This remembers who was standing on each block as it was destroyed and sets them back down on it when it
 * comes back.
 *
 * <h2>Who counts</h2>
 * Anything placed on purpose rather than wandering past. That is either an entity that cannot move itself or has been
 * marked to persist, which covers CustomNPCs and most posed mobs, or one belonging to a mod whose entities are all
 * placed deliberately: DragonMineZ's masters and saga fighters, and this suite's own dungeon, raid and tournament NPCs.
 * Ordinary wandering mobs are left alone, because putting a cow back where it was standing thirty seconds ago is not a
 * repair, it is a leash.
 *
 * <h2>Why the lookup is indexed rather than queried per block</h2>
 * A crater is thousands of blocks. Asking the world "is anything standing here" once per block would be thousands of
 * area queries in a tick. Instead every tick in which anything is captured builds ONE list of nearby anchorable
 * entities, keyed by the block each is standing on, and every capture in that tick is a hash lookup against it.
 *
 * <h2>When it declines</h2>
 * An NPC that has walked well away, or been carried off, keeps whatever it is doing. Only one that is still nearby AND
 * has actually been displaced, dropped below where it was or shifted off the spot, is put back. Otherwise a repair
 * half a minute after the fact would yank NPCs out of whatever they had moved on to.
 */
final class NpcAnchors
{
    private NpcAnchors() {}

    // Entities from these mods are placed deliberately, so any of them standing on destroyed ground is worth putting
    // back whether or not the entity itself is marked persistent.
    private static final Set<String> PLACED_BY_HAND = Set.of(
            "dragonminez", "customnpcs", "sdu", "dmz_ragnarok", "shuruisutilities",
            "shuruis_dmz_dungeons", "shuruis_raid_bosses", "shuruis_dmz_tournaments");

    // How far around a captured block the per tick index looks. Comfortably wider than a single blast, so one query
    // covers a whole crater's worth of captures in that tick.
    private static final double INDEX_RADIUS = 64.0D;

    // Furthest an NPC may have got from its old spot and still be put back. Past this it has gone somewhere on its own
    // and the repair is none of our business.
    private static final double MAX_RECOVERY_DISTANCE = 16.0D;

    // How far out of place it has to be to count as displaced at all, so a repair never nudges an NPC that is already
    // standing exactly where it belongs.
    private static final double DISPLACED_EPSILON = 0.35D;

    private record Anchor(UUID id, Vec3 pos, float yRot, float xRot) {}

    // Owed NPC placements, per dimension, keyed by the block that was holding them up.
    private static final Map<ResourceKey<Level>, Map<BlockPos, Anchor>> OWED = new HashMap<>();

    // Per tick index of who is standing on what, rebuilt at most once per level per tick.
    private static final Map<BlockPos, Anchor> INDEX = new HashMap<>();
    private static ResourceKey<Level> indexLevel;
    private static long indexTick = Long.MIN_VALUE;

    /** Remember whoever is standing on this block, as it is being destroyed. Cheap: one hash lookup per block. */
    static void note(ServerLevel level, BlockPos destroyed)
    {
        try
        {
            refreshIndex(level, destroyed);
            Anchor anchor = INDEX.get(destroyed);
            if (anchor == null)
                return;
            // First capture wins, matching how the block snapshot itself is kept: the earliest record is the one that
            // predates the whole fight.
            OWED.computeIfAbsent(level.dimension(), k -> new HashMap<>()).putIfAbsent(destroyed, anchor);
        }
        catch (Throwable ignored)
        {
            // Never let remembering an NPC stop a block from breaking, or stop the block itself being remembered.
        }
    }

    /** Set the NPC owed for this block back on top of it. Called after the block itself has been put back. */
    static void restore(ServerLevel level, BlockPos pos)
    {
        Map<BlockPos, Anchor> owed = OWED.get(level.dimension());
        if (owed == null || owed.isEmpty())
            return;
        Anchor anchor = owed.remove(pos);
        if (anchor == null)
            return;
        try
        {
            Entity entity = level.getEntity(anchor.id());
            if (entity == null || !entity.isAlive() || entity.isRemoved())
                return;
            Vec3 target = anchor.pos();
            Vec3 current = entity.position();
            if (current.distanceTo(target) > MAX_RECOVERY_DISTANCE)
                return; // gone somewhere of its own accord
            if (current.distanceTo(target) < DISPLACED_EPSILON)
                return; // already where it belongs
            entity.teleportTo(target.x, target.y, target.z);
            entity.setYRot(anchor.yRot());
            entity.setXRot(anchor.xRot());
            if (entity instanceof LivingEntity living)
            {
                living.setYBodyRot(anchor.yRot());
                living.setYHeadRot(anchor.yRot());
            }
            entity.setDeltaMovement(Vec3.ZERO);
            entity.fallDistance = 0.0F;
            entity.hurtMarked = true;
        }
        catch (Throwable ignored)
        {
        }
    }

    static void forget(ServerLevel level)
    {
        if (level != null)
        {
            OWED.remove(level.dimension());
            if (level.dimension().equals(indexLevel))
                invalidateIndex();
        }
    }

    static void forgetAll()
    {
        OWED.clear();
        invalidateIndex();
    }

    static int owedCount(ServerLevel level)
    {
        Map<BlockPos, Anchor> owed = level == null ? null : OWED.get(level.dimension());
        return owed == null ? 0 : owed.size();
    }

    private static void refreshIndex(ServerLevel level, BlockPos near)
    {
        long tick = level.getGameTime();
        if (tick == indexTick && level.dimension().equals(indexLevel))
            return;
        indexTick = tick;
        indexLevel = level.dimension();
        INDEX.clear();
        List<Entity> nearby = level.getEntities((Entity) null,
                new AABB(near).inflate(INDEX_RADIUS), NpcAnchors::isAnchorable);
        for (Entity entity : nearby)
        {
            INDEX.put(supportingBlock(entity),
                    new Anchor(entity.getUUID(), entity.position(), entity.getYRot(), entity.getXRot()));
        }
    }

    private static void invalidateIndex()
    {
        INDEX.clear();
        indexLevel = null;
        indexTick = Long.MIN_VALUE;
    }

    // The block an entity is standing ON, which is the one below its feet rather than the one its feet are in.
    private static BlockPos supportingBlock(Entity entity)
    {
        return new BlockPos(Mth.floor(entity.getX()), Mth.floor(entity.getY() - 0.1D), Mth.floor(entity.getZ()));
    }

    private static boolean isAnchorable(Entity entity)
    {
        if (entity == null || entity instanceof Player)
            return false;   // a player is dug out where they stand, not carried back (see unburyEntities)
        // Placed by hand and unable to walk away, so where it stands IS its position: an armour stand posed on a
        // roof, a minecart on its rails, a boat at a dock. These are not LivingEntity at all, which is why the old
        // living-only test dropped every one of them and left them in the hole.
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                || entity instanceof net.minecraft.world.entity.decoration.HangingEntity
                || entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                || entity instanceof net.minecraft.world.entity.vehicle.Boat)
            return true;
        // Something that cannot walk off, or has been told to stay, was put where it is on purpose.
        if (entity instanceof Mob mob && (mob.isNoAi() || mob.isPersistenceRequired()))
            return true;
        // A loose item, an arrow or an XP orb belongs to whatever just happened, not to the scenery, so the
        // namespace rule below is only allowed to speak for things that are alive or were placed.
        if (!(entity instanceof LivingEntity))
            return false;
        try
        {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            return id != null && PLACED_BY_HAND.contains(id.getNamespace());
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
