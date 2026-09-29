package net.shurui.shuruisutilities.dragons;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;

import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The status effect a victim carries while standing in Haze Shenron's pollution cloud.
 *
 * <p>WHY AN EFFECT RATHER THAN A PACKET. The purple screen overlay has to be driven by client state, and a mob
 * effect is already synced to the client by vanilla for free. Marking the victim with this effect means the overlay
 * ({@code PollutionOverlay}) simply asks "does the local player have it", with no packet, no client cache and no
 * chance of the overlay and the server disagreeing. It also self-clears: walk out of the cloud, the effect lapses a
 * moment later and the overlay goes with it.
 *
 * <p>Carries no attribute and does nothing on tick. The damage and the vision loss are applied by the cloud itself;
 * this is the marker and the tint, so a cleanse can never turn "I am standing in poison gas" into an exploit.
 */
public final class PollutedEffect extends MobEffect
{
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ShuruisUtilities.MODID);

    /** Registered under this name, so the texture resolves at textures/mob_effect/su_polluted.png. */
    public static final String NAME = "su_polluted";

    public static final RegistryObject<MobEffect> POLLUTED = EFFECTS.register(NAME, PollutedEffect::new);

    private PollutedEffect()
    {
        // HARMFUL, and the colour is the same purple the cloud and its screen overlay are drawn in, so the pip, the
        // particles and the tint all read as one thing.
        super(MobEffectCategory.HARMFUL, DragonMoveHaze.CLOUD_RGB);
    }

    /** Mark a victim as standing in the gas for the next {@code ticks}. */
    public static void apply(net.minecraft.world.entity.LivingEntity victim, int ticks)
    {
        if (victim == null || ticks <= 0)
            return;
        try
        {
            victim.addEffect(new MobEffectInstance(POLLUTED.get(), ticks, 0, false, false, true));
        }
        catch (Throwable ignored)
        {
            // The marker must never be able to break the cloud that applied it.
        }
    }

    /** True when this entity is currently standing in the gas. */
    public static boolean has(net.minecraft.world.entity.LivingEntity entity)
    {
        try
        {
            return entity != null && entity.hasEffect(POLLUTED.get());
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }
}
