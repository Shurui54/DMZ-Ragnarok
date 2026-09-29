package net.shurui.shuruisutilities.compat.raidbosses;

import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.world.entity.LivingEntity;

/**
 * Reflection bridge to the raid bosses addon, so hakai can refuse to erase a live raid boss.
 *
 * <p>Reflective and null-tolerant like {@code TitleBridge}: with the raid addon absent, or its internals moved,
 * every call answers "not a raid boss" and nothing else changes.
 *
 * <p>FAILURE DIRECTION MATTERS HERE. If the lookup breaks, this returns false and hakai WILL erase a raid boss. The
 * alternative default (treat everything as protected when the bridge is broken) would silently disable hakai
 * entirely, which is the harder failure to notice. Neither default is safe, so the resolution is logged once.
 */
public final class RaidBossBridge
{
    private RaidBossBridge() {}

    /** True when this entity is a live raid boss the raid addon is tracking. */
    public static boolean isRaidBoss(LivingEntity entity)
    {
        resolve();
        if (isBossM == null || entity == null)
            return false;
        try
        {
            Object result = isBossM.invoke(null, entity.getUUID());
            return result instanceof Boolean b && b;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static boolean resolved;
    private static Method isBossM; // RaidDamageTracker.isBoss(UUID) -> boolean

    private static synchronized void resolve()
    {
        if (resolved)
            return;
        resolved = true;
        try
        {
            Class<?> c = Class.forName(
                    "net.shurui.dev.shuruis_raid_bosses.raid.RaidDamageTracker");
            isBossM = c.getMethod("isBoss", UUID.class);
        }
        catch (Throwable t)
        {
            isBossM = null;
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[god] raid boss lookup unavailable, hakai will not protect raid bosses: {}", t.toString());
        }
    }
}
