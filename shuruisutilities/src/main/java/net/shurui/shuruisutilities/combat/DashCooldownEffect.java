package net.shurui.shuruisutilities.combat;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Shows the dash cooldown as a status effect, so it is readable where a player already looks for cooldowns.
 *
 * <p>DragonMineZ puts its own dash, ki blast and teleport cooldowns in the effect bar, so a dash of ours that had no
 * pip was the odd one out: the only way to know whether it was ready was to press the key and find out. The icon is
 * DMZ's own {@code dash_cd} recoloured, deliberately, so it reads as the same kind of thing at a glance while still
 * being distinguishable from theirs.
 *
 * <p>Purely a readout. It applies no attribute, does nothing on tick, and is not what enforces the cooldown; that
 * stays in {@link DashService}, which is the single source of truth. If the effect were somehow removed the dash
 * would still be on cooldown, which is the right way round: a cosmetic that cannot be cleansed into an exploit.
 */
public final class DashCooldownEffect extends MobEffect
{
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ShuruisUtilities.MODID);

    /** Registered under this name, so the texture resolves at textures/mob_effect/su_dash_cd.png. */
    public static final String NAME = "su_dash_cd";

    public static final RegistryObject<MobEffect> DASH_COOLDOWN =
            EFFECTS.register(NAME, DashCooldownEffect::new);

    private DashCooldownEffect()
    {
        // HARMFUL so it sits with the other cooldown pips rather than among buffs. The colour is the aqua the
        // recoloured icon is painted in, which is what tints the swirl particles vanilla would otherwise draw.
        super(MobEffectCategory.HARMFUL, 0x3FC8D8);
    }

    /** Put the pip on a player for the length of their dash cooldown. */
    public static void apply(net.minecraft.server.level.ServerPlayer player, int ticks)
    {
        if (player == null || ticks <= 0)
            return;
        try
        {
            // No particles and no HUD icon suppression: ambient false keeps it a solid pip, and showParticles false
            // stops a cooldown readout from covering the player in swirls every time they dash.
            player.addEffect(new MobEffectInstance(DASH_COOLDOWN.get(), ticks, 0, false, false, true));
        }
        catch (Throwable ignored)
        {
            // A readout must never be able to break the dash that produced it.
        }
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier)
    {
        return false; // nothing to do per tick; this is a readout, not a behaviour
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier)
    {
        // deliberately empty
    }

    /** Never obtainable from a potion; it is applied only by the dash. */
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getCurativeItems()
    {
        return java.util.List.of();
    }

}
