package net.shurui.dev.sdu.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.server.MinecraftServer;

/**
 * Core hook that lets the PRIVATE timed-event engine (the Ragnarok Key) import a piece of module content from a
 * text payload without naming the module that owns it. The key ships a bundle (for example the built-in Halloween
 * pack) that carries an eventOnly raid, an eventOnly rift and event NPC regions as text; when an operator runs
 * {@code /event import builtin:halloween} the key hands each payload to this hook, and whichever module can parse
 * it stores it in its own {@code SavedData}.
 *
 * <p>Lives in {@code sdu} so the key and every module can reach it without a cross-module import. Each module
 * REGISTERS a {@link Provider} at load (Raids provides {@link Provider#importRaid} / {@link Provider#importRift}
 * from SNBT; core SU provides {@link Provider#importRegion} from region JSON). An import call tries every registered
 * provider until one HANDLES it (returns true), so a module that is absent simply contributes nothing and the key
 * never classloads it. With no provider registered every import is a no-op that returns false, which the command
 * reports rather than crashing.
 */
public final class ContentImportHook
{
    private ContentImportHook() {}

    /** What a module offers the importer. Every method defaults to "not mine" so a module implements only its own. */
    public interface Provider
    {
        /** Parse and store a raid def from SNBT; true if this provider handled it. */
        default boolean importRaid(MinecraftServer server, String snbt)
        {
            return false;
        }

        /** Parse and store a rift def from SNBT; true if this provider handled it. */
        default boolean importRift(MinecraftServer server, String snbt)
        {
            return false;
        }

        /** Parse and store an NPC region from JSON; true if this provider handled it. */
        default boolean importRegion(MinecraftServer server, String json)
        {
            return false;
        }

        /** Ids of this module's raid defs flagged eventOnly (the event editor's raid picker). */
        default List<String> eventOnlyRaids(MinecraftServer server)
        {
            return List.of();
        }

        /** Ids of this module's rift defs flagged eventOnly (the event editor's rift picker). */
        default List<String> eventOnlyRifts(MinecraftServer server)
        {
            return List.of();
        }
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Register a module's provider at load. Order of registration is the order they are tried. */
    public static void register(Provider provider)
    {
        if (provider != null)
        {
            PROVIDERS.add(provider);
        }
    }

    /** True when at least one module registered a provider (a module with importable content is installed). */
    public static boolean available()
    {
        return !PROVIDERS.isEmpty();
    }

    /** Offer SNBT to every provider as a raid; true once one stores it. A thrown provider is skipped, not fatal. */
    public static boolean importRaid(MinecraftServer server, String snbt)
    {
        for (Provider p : PROVIDERS)
        {
            try
            {
                if (p.importRaid(server, snbt))
                {
                    return true;
                }
            }
            catch (Throwable ignored)
            {
            }
        }
        return false;
    }

    /** Offer SNBT to every provider as a rift; true once one stores it. */
    public static boolean importRift(MinecraftServer server, String snbt)
    {
        for (Provider p : PROVIDERS)
        {
            try
            {
                if (p.importRift(server, snbt))
                {
                    return true;
                }
            }
            catch (Throwable ignored)
            {
            }
        }
        return false;
    }

    /** Every provider's eventOnly raid ids, de-duplicated and sorted. Empty when Raids is absent. */
    public static List<String> eventOnlyRaids(MinecraftServer server)
    {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (Provider p : PROVIDERS)
        {
            try
            {
                out.addAll(p.eventOnlyRaids(server));
            }
            catch (Throwable ignored)
            {
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /** Every provider's eventOnly rift ids, de-duplicated and sorted. Empty when Raids is absent. */
    public static List<String> eventOnlyRifts(MinecraftServer server)
    {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (Provider p : PROVIDERS)
        {
            try
            {
                out.addAll(p.eventOnlyRifts(server));
            }
            catch (Throwable ignored)
            {
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /** Offer JSON to every provider as an NPC region; true once one stores it. */
    public static boolean importRegion(MinecraftServer server, String json)
    {
        for (Provider p : PROVIDERS)
        {
            try
            {
                if (p.importRegion(server, json))
                {
                    return true;
                }
            }
            catch (Throwable ignored)
            {
            }
        }
        return false;
    }
}
