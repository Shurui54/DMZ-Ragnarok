package net.shurui.dev.sdu.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lift an entity that has ended up inside solid blocks back to the first safe standing spot above it.
 *
 * <p>This lives in sdu (which imports nothing from shuruisutilities) so every tree that owns an NPC can share the
 * one implementation: sdu's own {@code SduDmzFighter}, shuruisutilities' rgnpc fighters, the raid bosses and the
 * dungeon guardians. Terrain regen already had this exact routine inline; {@code TerrainRegenService.unbury}
 * delegates here now, so a crater sweep and an NPC's own tick share one behaviour rather than two copies.
 *
 * <p>The failure the tick path fixes: terrain regen only sweeps for buried entities ONCE, the moment a crater has
 * finished filling. An NPC that is knocked or dashed into a wall (no crater, so no regen pass), or that wanders or
 * is shoved into restored terrain AFTER that one sweep, is never checked again and simply suffocates. A cheap
 * self-heal on the NPC's own tick closes that gap.
 *
 * <p>SAFE ON THE TICK THREAD: everything read here is in the entity's own column, whose chunk is loaded because
 * the entity is ticking (or, for the regen sweep, because the repair just wrote into it). There is no cross-chunk
 * read, so no synchronous chunk load can be triggered.
 */
public final class NpcUnstuck
{
    private NpcUnstuck() {}

    /** How far up we look for a clear spot before giving up on lifting-in-place and going to the surface. */
    public static final int MAX_LIFT = 24;

    /** Ticks between self-heal checks. One second is snappy enough against suffocation and costs a few block reads. */
    private static final int SELF_HEAL_INTERVAL = 20;

    /**
     * Self-heal for an NPC's tick. A no-op unless the entity is genuinely walled in (the exact condition that
     * suffocates it), so a fighter merely brushing terrain or flying past it is left alone. Throttled internally,
     * so a call site is a single line: {@code NpcUnstuck.tickSelfHeal(this);}
     */
    public static void tickSelfHeal(Entity entity)
    {
        if (entity == null || entity.level().isClientSide())
            return;
        if (!(entity.level() instanceof ServerLevel level))
            return;
        if (entity.tickCount % SELF_HEAL_INTERVAL != 0)
            return;
        if (!entity.isAlive() || entity.isRemoved() || entity.isPassenger())
            return;
        // isInWall is precisely what vanilla tests before dealing suffocation damage, so this fires only when the
        // NPC is actually buried, never when it is standing with a block merely at its feet.
        if (!entity.isInWall())
            return;
        liftOut(level, entity);
    }

    /**
     * Lift the entity straight up to the first spot where its bounding box is clear, keeping it where it was
     * standing. If nothing is clear within {@link #MAX_LIFT} blocks it is under solid terrain rather than in a
     * crater, so it goes to the column's surface, the answer that always exists. Does nothing if it is already in
     * open air. Assumes the entity's own chunk is loaded.
     */
    public static void liftOut(ServerLevel level, Entity entity)
    {
        try
        {
            AABB box = entity.getBoundingBox();
            if (level.noCollision(entity, box))
                return;   // standing in open air: nothing closed in around it
            for (int lift = 1; lift <= MAX_LIFT; lift++)
            {
                if (level.noCollision(entity, box.move(0.0D, lift, 0.0D)))
                {
                    place(entity, entity.getX(), entity.getY() + lift, entity.getZ());
                    return;
                }
            }
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    entity.getBlockX(), entity.getBlockZ());
            if (surface > entity.getY())
                place(entity, entity.getX(), surface, entity.getZ());
        }
        catch (Throwable ignored)
        {
            // One entity failing to be freed must never break the caller (a tick or a batch sweep).
        }
    }

    private static void place(Entity entity, double x, double y, double z)
    {
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0F;
        // A PLAYER MUST BE MOVED THROUGH THEIR CONNECTION. Their own client is authoritative for where they are, so
        // a server side teleportTo is corrected away on the next movement packet and they are simply back in the
        // wall. connection.teleport is what tells the client to accept the new position.
        if (entity instanceof net.minecraft.server.level.ServerPlayer player)
        {
            player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
            return;
        }
        entity.teleportTo(x, y, z);
        // Forces the velocity change out to watchers, so the entity does not keep drifting on their screens.
        entity.hurtMarked = true;
    }
}
