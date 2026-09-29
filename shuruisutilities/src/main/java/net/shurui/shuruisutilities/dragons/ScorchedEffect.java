package net.shurui.shuruisutilities.dragons;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Marks a victim as standing inside Nuova Shenron's heat, driving the orange screen shimmer.
 *
 * <p>Same trick as {@link PollutedEffect}: a mob effect is already synced to the client by vanilla, so the overlay
 * can ask "does the local player have it" with no packet, no client cache, and no way for the tint to disagree with
 * the server. It also self-clears - step out of the heat and the effect lapses a moment later, taking the tint with
 * it - which is exactly the behaviour an area you can walk out of needs.
 *
 * <p>Carries no attribute and does nothing on tick. The burning and the damage are the aura's job; this is the
 * marker and the colour.
 */
public final class ScorchedEffect extends MobEffect
{
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ShuruisUtilities.MODID);

    /** Registered under this name, so the texture resolves at textures/mob_effect/su_scorched.png. */
    public static final String NAME = "su_scorched";

    public static final RegistryObject<MobEffect> SCORCHED = EFFECTS.register(NAME, ScorchedEffect::new);

    private ScorchedEffect()
    {
        super(MobEffectCategory.HARMFUL, DragonMoveNuova.HEAT_RGB);
    }

    /** Mark a victim as standing in the heat for the next {@code ticks}. */
    public static void apply(LivingEntity victim, int ticks)
    {
        if (victim == null || ticks <= 0)
            return;
        try
        {
            victim.addEffect(new MobEffectInstance(SCORCHED.get(), ticks, 0, false, false, true));
        }
        catch (Throwable ignored)
        {
        }
    }

    /** True when this entity is currently standing in the heat. */
    public static boolean has(LivingEntity entity)
    {
        try
        {
            return entity != null && entity.hasEffect(SCORCHED.get());
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }
}
