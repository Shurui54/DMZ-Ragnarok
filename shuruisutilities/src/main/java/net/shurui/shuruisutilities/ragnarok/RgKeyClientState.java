package net.shurui.shuruisutilities.ragnarok;

import net.shurui.dev.sdu.api.ClientGate;

/**
 * The client's copy of the server's key state, delivered by {@link PacketRgKeySync} at login.
 *
 * <p>As of the "private UI is not rendered without the key" pass this no longer holds its own flag: there is one
 * client-side key flag for the whole suite, {@link ClientGate}, and this delegates to it. Both the SU key packet
 * (id 76) and the sdu {@code KeySyncPacket} set the same flag, so the ragnarok model picker and every other
 * client gate agree. Kept as a thin facade because callers ({@code RgNpcCnpcCatalog}) reference it by name.
 */
public final class RgKeyClientState
{
    private RgKeyClientState() {}

    public static void set(boolean value)
    {
        ClientGate.set(value);
    }

    /** Called on disconnect: the next server states its own key status, and until it does the gate is shut. */
    public static void clear()
    {
        ClientGate.reset();
    }

    public static boolean unlocked()
    {
        return ClientGate.key();
    }
}
