package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.corrupted.ShadowDragonDef;
import net.shurui.shuruisutilities.corrupted.ShadowDragonStorage;

/**
 * Resolves a live shadow dragon boss back to the slot definition it was spawned from.
 *
 * <p>A boss is whatever entity type its slot is configured with, so it cannot be recognised by class. The boss
 * manager records every spawned dragon as {@code uuid -> slot} in {@link ShadowDragonStorage}, and that mapping is
 * the only reliable way to tell "this mob is shadow dragon slot 4" from "this mob is an ordinary saga entity of the
 * same type standing next to it".
 */
public final class DragonBossLookup
{
    private DragonBossLookup() {}

    /** The slot index (1..7) this entity was spawned as, or 0 when it is not a live shadow dragon boss. */
    public static int slotFor(LivingEntity entity)
    {
        if (entity == null)
            return 0;
        MinecraftServer server = entity.getServer();
        if (server == null)
            return 0;
        Integer slot = ShadowDragonStorage.get(server).getLiveDragons().get(entity.getUUID());
        return slot == null ? 0 : slot;
    }

    /** The slot definition behind a live boss, or null when this entity is not one. */
    public static ShadowDragonDef defFor(LivingEntity entity)
    {
        int slot = slotFor(entity);
        if (slot <= 0)
            return null;
        MinecraftServer server = entity.getServer();
        return server == null ? null : ShadowDragonStorage.get(server).getDef(slot);
    }

    /** True when this entity is one of the live shadow dragon bosses. */
    public static boolean isBoss(LivingEntity entity)
    {
        return slotFor(entity) > 0;
    }
}
