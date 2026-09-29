package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/** Guard for clearing a player's charging ki ball. */
public final class ChargingProjectile
{
    private ChargingProjectile() {}

    /** Discard the ball this player is charging, if any. @return how many were removed. */
    public static int discardChargingFor(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez")
                ? ChargingProjectileImpl.discardChargingFor(player) : 0;
    }

    /**
     * True when this entity is a ki projectile still being CHARGED rather than one already fired.
     *
     * <p>False when DragonMineZ is absent, so a caller can use this as "is this a held orb" without guarding the
     * mod check itself.</p>
     */
    public static boolean isStillCharging(net.minecraft.world.entity.Entity entity)
    {
        return entity != null && ModList.get().isLoaded("dragonminez")
                && ChargingProjectileImpl.isStillCharging(entity);
    }

    /**
     * Wrap an area move's charging orb around its caster and grow it with the charge.
     *
     * @param baseDiameter the orb's diameter at a full 100% charge, in blocks
     * @param chargePercent DMZ's 0..175 charge, so anything past 100 reads as an overcharge and swells the orb
     */
    public static void engulfCaster(ServerPlayer player, float baseDiameter, float chargePercent)
    {
        if (player != null && ModList.get().isLoaded("dragonminez"))
            ChargingProjectileImpl.engulfCaster(player, baseDiameter, chargePercent);
    }
}
