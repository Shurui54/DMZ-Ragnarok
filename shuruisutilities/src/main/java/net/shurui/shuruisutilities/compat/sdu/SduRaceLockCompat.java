package net.shurui.shuruisutilities.compat.sdu;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

// client-only bridge to sdu's race-lock cache. sdu owns RaceLockClient + its race-select mixin; SU pushes the
// per-player lock state so locked races grey out in the DMZ race screen.
// reflection + isLoaded compat (names no sdu types, classload-safe when absent). fixed contract:
//   net.shurui.dev.sdu.race.RaceLockClient.apply(Map<String,Integer> raceRequired, Set<String> locked)
// invoked reflectively so a missing/renamed sdu class degrades to a no-op.
@OnlyIn(Dist.CLIENT)
public final class SduRaceLockCompat
{
    private static final String RACE_LOCK_CLIENT = "net.shurui.dev.sdu.race.RaceLockClient";

    // resolved once + cached; null when sdu absent or the method isn't found
    private static Method applyMethod;
    private static boolean resolved;

    private SduRaceLockCompat() {}

    // push race-lock state to sdu's client cache. no-op unless sdu is loaded and the method resolves.
    public static void apply(Map<String, Integer> raceRequired, Set<String> lockedForLocalPlayer)
    {
        // sdu is now part of this same container; resolve() still fails soft if the API ever drifts.
        Method m = resolve();
        if (m == null)
            return;
        try
        {
            m.invoke(null, raceRequired, lockedForLocalPlayer);
        }
        catch (Throwable ignored)
        {
            // a contract mismatch / sdu error must never crash the client packet handler
        }
    }

    private static Method resolve()
    {
        if (resolved)
            return applyMethod;
        resolved = true;
        try
        {
            Class<?> cls = Class.forName(RACE_LOCK_CLIENT);
            applyMethod = cls.getMethod("apply", Map.class, Set.class);
        }
        catch (Throwable ignored)
        {
            applyMethod = null;
        }
        return applyMethod;
    }
}
