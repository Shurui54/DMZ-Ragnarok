package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/** Guard for reading which technique a player is currently charging. */
public final class ChargeState
{
    private ChargeState() {}

    public static String chargingIdOf(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez")
                ? ChargeStateImpl.chargingIdOf(player) : null;
    }

    /** How far along this player's charge is, on DMZ's 0..175 scale (over 100 is an overcharge). */
    public static float chargePercentOf(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez")
                ? ChargeStateImpl.chargePercentOf(player) : 0.0f;
    }

    /** Clear DMZ's charge state for this player. */
    public static void clearCharge(ServerPlayer player)
    {
        if (player != null && ModList.get().isLoaded("dragonminez"))
            ChargeStateImpl.clearCharge(player);
    }
}
