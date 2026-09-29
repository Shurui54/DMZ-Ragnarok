package net.shurui.shuruisutilities.client.space;

import net.shurui.shuruisutilities.space.PacketPlanetInfoGui;

/**
 * Client-only sink for a received {@link PacketPlanetInfoGui}: it hands the server-built view to the floating
 * {@link PlanetInfoOverlay} to cache and draw, rather than opening a screen (the old on-demand screen is gone). Still
 * referenced only through {@code DistExecutor} so it never classloads on a dedicated server.
 */
public final class PlanetInfoClient
{
    private PlanetInfoClient()
    {
    }

    public static void accept(PacketPlanetInfoGui packet)
    {
        PlanetInfoOverlay.acceptView(packet.view);
    }
}
