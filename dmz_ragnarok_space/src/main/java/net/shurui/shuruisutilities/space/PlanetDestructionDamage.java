package net.shurui.shuruisutilities.space;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * {@link DamageSource} for a planet destroyed under a player. From the datapack {@code damage_type} JSON, whose
 * {@code message_id} "su_planet_destroyed" drives the death message key, translated in both lang files.
 *
 * <p>Tagged bypasses_armor/effects/enchantments/invulnerability/resistance so it is an unconditional kill: armour,
 * Resistance and i-frames cannot soak it. No attacker, so DMZ's combat mitigation (player-attacker only) never touches
 * it. Caller passes {@code Float.MAX_VALUE} to overwhelm an inflated DMZ health pool.
 */
public final class PlanetDestructionDamage
{
    private PlanetDestructionDamage()
    {
    }

    public static final ResourceKey<DamageType> PLANET_DESTROYED =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(ShuruisUtilities.MODID, "planet_destroyed"));

    // Falls back to the out-of-world source (also an unconditional kill) if the datapack type is absent.
    public static DamageSource planetDestroyed(ServerLevel level)
    {
        return level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(PLANET_DESTROYED)
                .map((Holder.Reference<DamageType> holder) -> new DamageSource(holder))
                .orElse(level.damageSources().fellOutOfWorld());
    }
}
