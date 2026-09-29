package net.shurui.dev.sdu.api;

/**
 * Core client hook for the Space module's two keybind actions, so the shared keybind handler (which stays in core so
 * options.txt keeps its bindings) can fire them without naming the Space client classes directly.
 *
 * <p>This lives in core (sdu). The Space module registers its client-side implementation at client setup; the core
 * keybind handler reads it. When Space is absent no provider is registered, so both calls are no-ops: pressing the
 * planet-select or planet-info key simply does nothing. This keeps the keybind to space edge a soft, core-mediated
 * hook rather than a direct dependency, and lets Space move to a separate jar later without touching the keybinds.
 *
 * <p>Plain by design: it imports no client-only class, because the core keybind handler that touches it could be
 * classloaded on a dedicated server, and a client-only import would crash there. The provider itself lives in the
 * Space client package and is only registered on {@link net.minecraftforge.api.distmarker.Dist#CLIENT}.
 */
public final class SpaceClientHook
{
    /** Implemented client-side by the Space module. */
    public interface Provider
    {
        /** Set (clear = false) or clear (clear = true) the local player's space-pod planet course. */
        void courseKey(boolean clear);

        /** Flip the persistent floating planet-info overlay preference. */
        void togglePlanetInfo();
    }

    private static volatile Provider impl;

    private SpaceClientHook()
    {
    }

    /** Called once client-side by the Space module at client setup. */
    public static void register(Provider p)
    {
        impl = p;
    }

    /** True when the Space module is present and has registered its client provider. */
    public static boolean available()
    {
        return impl != null;
    }

    public static void courseKey(boolean clear)
    {
        Provider p = impl;
        if (p != null)
        {
            p.courseKey(clear);
        }
    }

    public static void togglePlanetInfo()
    {
        Provider p = impl;
        if (p != null)
        {
            p.togglePlanetInfo();
        }
    }
}
