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
 * The two custom hazard {@link DamageSource}s, from the datapack {@code damage_type} JSONs (star_burn.json,
 * black_hole.json). The {@code message_id} ("su_star_burn" / "su_black_hole") drives the death message key, translated
 * in both lang files.
 *
 * <p>Why DMZ stats do NOT trivially negate these: DMZ subtracts resistance only when its {@code onLivingHurt} tagged the
 * hit as combat (player attacker, msgId "player"); these have no attacker, so DMZ never writes {@code dmz_raw_damage}
 * and mitigation finds nothing. Both types are in bypasses_armor/effects/enchantments, and black hole also in
 * bypasses_invulnerability/resistance (unconditional kill). AMOUNTS are a fraction of the victim's MAX HEALTH in
 * SpaceHazardModule, so an inflated DMZ health pool scales the hit up instead of a flat number vanishing.
 */
public final class SpaceHazardDamage
{
    private SpaceHazardDamage()
    {
    }

    public static final ResourceKey<DamageType> STAR_BURN =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(ShuruisUtilities.MODID, "star_burn"));

    public static final ResourceKey<DamageType> BLACK_HOLE =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(ShuruisUtilities.MODID, "black_hole"));

    // No attacker, so the death reads as the environmental "death.attack.<id>" form. Falls back to the given generic
    // source if the datapack type is absent.
    public static DamageSource star(ServerLevel level)
    {
        return byKey(level, STAR_BURN, level.damageSources().onFire());
    }

    public static DamageSource blackHole(ServerLevel level)
    {
        return byKey(level, BLACK_HOLE, level.damageSources().fellOutOfWorld());
    }

    private static DamageSource byKey(ServerLevel level, ResourceKey<DamageType> key, DamageSource fallback)
    {
        return level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(key)
                .map((Holder.Reference<DamageType> holder) -> new DamageSource(holder))
                .orElse(fallback);
    }
}
