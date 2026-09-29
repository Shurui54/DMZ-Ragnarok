package net.shurui.shuruisutilities.regions;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

// bridges our regions into DMZ's own WorldGuard detection.
// DMZ ships WorldGuardCompat: on server start it reflectively looks for a real (Bukkit/Sponge) WorldGuard
// and if found registers its own ki-griefing (StateFlag) + dmz-gravity (DoubleFlag) flags, then routes
// MainGameRules.canKiGrief() and gravity lookups through an IWorldGuardHandler. on a Forge-only server no
// such WorldGuard exists, so that path is dormant and DMZ ki blasts grief freely.
// we revive it: after DMZ's init(), install a dynamic-proxy IWorldGuardHandler whose canGrief/
// getGravityValue answer from our RegionManager. DMZ then honours our KI_GRIEFING and DMZ_GRAVITY flags at
// the source, so ki blasts don't break blocks in a protected region. replaces the old bespoke ki-blast
// explosion filter.
public final class RegionDmzHook
{
    private RegionDmzHook() {}

    private static final String COMPAT = "com.dragonminez.common.compat.WorldGuardCompat";
    private static final String IFACE = "com.dragonminez.common.compat.WorldGuardCompat$IWorldGuardHandler";

    private static boolean injected = false;

    public static boolean isInjected()
    {
        return injected;
    }

    /** Installs our handler into DMZ's WorldGuardCompat. Safe to call once, after DMZ's own server-start init. */
    public static void inject()
    {
        if (injected)
            return;
        try
        {
            Class<?> compat = Class.forName(COMPAT);
            Class<?> iface = Class.forName(IFACE);

            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface }, new Handler());

            setStatic(compat, "handler", proxy);
            setStatic(compat, "worldGuardAvailable", Boolean.TRUE);

            // Read the fields back: if DMZ (or another mod) later reset them, the hook is silently inert and ki
            // blasts would grief regions freely. This makes that visible in the server log.
            Object installedHandler = getStatic(compat, "handler");
            boolean available = Boolean.TRUE.equals(getStatic(compat, "worldGuardAvailable"));
            if (installedHandler != proxy || !available)
            {
                LoggingHandler.sulog.warn("[Regions] DragonMineZ WorldGuard hook did not take (handler set: {}, available: {}). "
                        + "ki-griefing region flag may be ignored.", installedHandler == proxy, available);
                return;
            }
            injected = true;
            LoggingHandler.sulog.info("[Regions] Hooked DragonMineZ WorldGuard compat: ki-griefing & dmz-gravity now honour regions.");
        }
        catch (ClassNotFoundException e)
        {
            LoggingHandler.sulog.info("[Regions] DragonMineZ WorldGuardCompat not present; ki-griefing region flag disabled.");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Regions] Failed to hook DragonMineZ WorldGuard compat: {}", t.toString());
        }
    }

    private static void setStatic(Class<?> cls, String name, Object value) throws ReflectiveOperationException
    {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Object getStatic(Class<?> cls, String name) throws ReflectiveOperationException
    {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /** Dispatches DMZ's package-private {@code IWorldGuardHandler} calls to our region logic. */
    private static final class Handler implements InvocationHandler
    {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            switch (method.getName())
            {
                case "registerFlags":
                    return null; // nothing to register into a real WorldGuard; we hold the flags ourselves
                case "canGrief":
                    return canGrief((Level) args[0], (BlockPos) args[1], (Entity) args[2]);
                case "getGravityValue":
                    return gravity((Level) args[0], (BlockPos) args[1], (Entity) args[2]);
                default:
                    // Object methods (toString/hashCode/equals) and any future additions
                    return switch (method.getName())
                    {
                        case "toString" -> "SURegionWorldGuardHandler";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null ? null : args[0]);
                        default -> defaultFor(method.getReturnType());
                    };
            }
        }

        private static boolean canGrief(Level level, BlockPos pos, Entity entity)
        {
            if (level == null || pos == null)
                return true;
            String dim = level.dimension().location().toString();
            // default (unset / allow) => griefing permitted; only an explicit deny stops it
            return !RegionEventHandler.DENY.equals(
                    RegionManager.instance().flagAt(dim, pos.getX(), pos.getY(), pos.getZ(), RegionFlag.KI_GRIEFING));
        }

        private static double gravity(Level level, BlockPos pos, Entity entity)
        {
            if (level == null || pos == null)
                return 0.0; // DMZ treats 0.0 as "no override"
            String dim = level.dimension().location().toString();
            String v = RegionManager.instance().flagAt(dim, pos.getX(), pos.getY(), pos.getZ(), RegionFlag.DMZ_GRAVITY);
            if (v == null || v.isBlank())
                return 0.0;
            try
            {
                return Double.parseDouble(v.trim());
            }
            catch (NumberFormatException e)
            {
                return 0.0;
            }
        }

        private static Object defaultFor(Class<?> t)
        {
            if (!t.isPrimitive())
                return null;
            if (t == boolean.class)
                return Boolean.FALSE;
            if (t == double.class)
                return 0.0d;
            if (t == float.class)
                return 0.0f;
            if (t == long.class)
                return 0L;
            if (t == void.class)
                return null;
            return 0;
        }
    }
}
