package net.shurui.shuruisutilities.teleport.portal;

/**
 * Where the dungeons module registers its {@link PortalTargetResolver} (floor and next-floor portal targets). Kept in
 * core because the dungeons module registers at mod construction, which can run before the Ragnarok Key installs the
 * portal manager; the key's portal manager reads the resolver from here at walk-through time. A plain holder: null
 * when the dungeons module is absent, and then floor portals refuse clearly.
 */
public final class PortalTargets
{
    private static volatile PortalTargetResolver symbolicResolver;

    private PortalTargets()
    {
    }

    /** Registered by the dungeons module's compat layer. Passing null clears it. */
    public static void setSymbolicResolver(PortalTargetResolver resolver)
    {
        symbolicResolver = resolver;
    }

    public static PortalTargetResolver symbolicResolver()
    {
        return symbolicResolver;
    }
}
