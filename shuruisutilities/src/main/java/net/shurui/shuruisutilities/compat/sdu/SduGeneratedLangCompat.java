package net.shurui.shuruisutilities.compat.sdu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.ModList;

// bridge to sdu's runtime lang overlay store. sdu owns config/sdu/generated_lang.json, a merge-only key->value map
// whose ONLY delete path is GeneratedLangStore.remove(String...). SU calls this when it upgrades a bundled race so
// stale hand-typed overlay entries for ITS OWN races (e.g. a "benis" desc) stop masking the shipped bilingual lang.
// reflection + isLoaded compat (names no sdu types, classload-safe when absent). fixed contract:
//   net.shurui.dev.sdu.lang.GeneratedLangStore.all()             -> Map<String,String> snapshot
//   net.shurui.dev.sdu.lang.GeneratedLangStore.remove(String...) // saves to disk if anything changed
// invoked reflectively so a missing/renamed sdu class degrades to a no-op.
public final class SduGeneratedLangCompat
{
    private static final String GENERATED_LANG_STORE = "net.shurui.dev.sdu.lang.GeneratedLangStore";

    // resolved once + cached; null when sdu absent or a method isn't found
    private static Method allMethod;
    private static Method removeMethod;
    private static boolean resolved;

    private SduGeneratedLangCompat() {}

    // remove every generated_lang key scoped to raceId: the bare "race.dragonminez.<raceId>" plus anything under
    // "race.dragonminez.<raceId>.". The trailing "." boundary keeps shadow_dragon from matching shadow_dragon_2star,
    // and no DMZ or foreign race id can match, so an admin's rename of, say, saiyan survives. no-op unless sdu is
    // loaded and both methods resolve. Persistence is sdu's remove() (it saves when anything changed).
    public static void removeForRace(String raceId)
    {
        if (raceId == null || raceId.isEmpty())
            return;
        // sdu is now part of this same container; resolve() still fails soft if the API ever drifts.
        if (!resolve())
            return;
        try
        {
            @SuppressWarnings("unchecked")
            Map<String, String> entries = (Map<String, String>) allMethod.invoke(null);
            if (entries == null || entries.isEmpty())
                return;
            String exact = "race.dragonminez." + raceId;
            String prefix = exact + ".";
            List<String> toRemove = new ArrayList<>();
            for (String key : entries.keySet())
                if (key != null && (key.equals(exact) || key.startsWith(prefix)))
                    toRemove.add(key);
            if (!toRemove.isEmpty())
                removeMethod.invoke(null, (Object) toRemove.toArray(new String[0]));
        }
        catch (Throwable ignored)
        {
            // a contract mismatch / sdu error must never abort the race extraction pass
        }
    }

    private static boolean resolve()
    {
        if (resolved)
            return allMethod != null && removeMethod != null;
        resolved = true;
        try
        {
            Class<?> cls = Class.forName(GENERATED_LANG_STORE);
            allMethod = cls.getMethod("all");
            removeMethod = cls.getMethod("remove", String[].class);
        }
        catch (Throwable ignored)
        {
            allMethod = null;
            removeMethod = null;
        }
        return allMethod != null && removeMethod != null;
    }
}
