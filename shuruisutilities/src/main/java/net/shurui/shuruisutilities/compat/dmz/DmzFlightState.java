package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for what a player's DMZ FLIGHT is doing right now, as opposed to whether they own the skill at
 * all ({@link DmzFlight}).
 *
 * <p>Holds no DMZ imports; {@link DmzFlightStateImpl} is the sole class naming them (the optional-dependency pattern). Both
 * questions answer "no" without DMZ, so an ability gated on flight is simply uncastable rather than free.
 */
public final class DmzFlightState
{
    private DmzFlightState() {}

    /** Is this player flying at this moment? */
    public static boolean isFlying(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez") && DmzFlightStateImpl.isFlying(player);
    }

    /** Have they taken the flight skill all the way to its maximum level? */
    public static boolean isFlightMaxed(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez") && DmzFlightStateImpl.isFlightMaxed(player);
    }
}
