package net.shurui.shuruisutilities.client.space;

import net.shurui.dev.sdu.api.SpaceClientHook;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.space.PacketSpaceCourse;

/**
 * The Space module's client-side implementation of {@link SpaceClientHook}, backing the planet-select / planet-info
 * keybinds. Registered from {@link SpaceClientBusEvents} on client setup (batch B moves the registration into the
 * Space {@code @Mod} client bus). Each method does exactly what the core keybind handler used to do inline, so the
 * key behaviour is unchanged.
 */
public final class SpaceClientHookImpl implements SpaceClientHook.Provider
{
    private SpaceClientHookImpl()
    {
    }

    /** Register this implementation with the core client hook. Called once, client-side, at client setup. */
    public static void register()
    {
        SpaceClientHook.register(new SpaceClientHookImpl());
    }

    @Override
    public void courseKey(boolean clear)
    {
        // ask the server to name (clear = false) or stop tracking (clear = true) the current planet course. Turned
        // into a compass course by MixinDmzTravelToPlanet; no teleport or pod can result.
        NetworkUtils.INSTANCE.sendToServer(new PacketSpaceCourse(clear));
    }

    @Override
    public void togglePlanetInfo()
    {
        // flip the persistent overlay preference; the overlay itself does the look-at detection and requesting.
        PlanetInfoOverlay.toggle();
    }
}
