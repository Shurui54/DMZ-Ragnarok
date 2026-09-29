package net.shurui.dev.sdu.buff;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.dev.sdu.DmzNpc;

/**
 * The visible pip for an active TP-boost gem. A gem writes its boost into {@link TokenBuffStore} under
 * {@link TokenBuffStore.Category#TP}; this effect is only the readout of that, so a player can see at a glance that a
 * boost is running and how long is left, in the same effect bar DMZ already uses for its own timers.
 *
 * <p>The stored expiry in {@link TokenBuffStore} is the single source of truth, NOT the effect's own remaining
 * duration. The effect is re-derived from that expiry on login, respawn and shard hop, and re-applied by the sweep in
 * the Ragnarok Key (TokenBuffFeature) if it is ever cleared (milk, {@code /effect clear}) while the boost is still
 * live. That is why it is not curable and why a missing pip never shortens or ends the boost: the multiply in the
 * key's {@code TokenBuffFeature.onTpGain} reads the same store, so the number and the pip can never disagree.
 */
public final class TpBoostEffect extends MobEffect
{
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, DmzNpc.MODID);

    /** Registered under this name, so the texture resolves at textures/mob_effect/tp_boost.png. */
    public static final String NAME = "tp_boost";

    public static final RegistryObject<MobEffect> TP_BOOST =
            EFFECTS.register(NAME, TpBoostEffect::new);

    private TpBoostEffect()
    {
        // BENEFICIAL so it sits with the buffs, aqua to match the TP-boost gem art (it tints the swirl particles).
        super(MobEffectCategory.BENEFICIAL, 0x26E9FF);
    }

    /** Put the pip on a player for a length in milliseconds, converted to ticks. No-op for zero or less. */
    public static void applyFor(ServerPlayer player, long remainingMillis)
    {
        if (player == null || remainingMillis <= 0L)
            return;
        int ticks = (int) Math.min(Integer.MAX_VALUE, remainingMillis / 50L);
        if (ticks <= 0)
            return;
        try
        {
            // ambient false keeps it a solid pip; showParticles false stops a 30 minute swirl; showIcon true so it
            // reads in the inventory and HUD.
            player.addEffect(new MobEffectInstance(TP_BOOST.get(), ticks, 0, false, false, true));
        }
        catch (Throwable ignored)
        {
            // A readout must never be able to break the boost that produced it.
        }
    }

    /**
     * Reconcile the pip against the store, the ONE place that decides whether it should be on and for how long.
     *
     * <p>If a TP boost is active it (re)applies the pip with the ticks left derived from the stored expiry, so a
     * cleared pip comes back at the right length and a stale one (wrong duration after a relog) is corrected. If no
     * boost is active it removes the pip. Called on login/respawn and on the periodic tick sweep.
     */
    public static void reconcile(ServerPlayer player)
    {
        if (player == null)
            return;
        long left = TokenBuffStore.remainingMillis(player, TokenBuffStore.Category.TP);
        if (left > 0L)
        {
            // Re-apply only when it is missing or has drifted from the stored time, so we do not spam addEffect.
            MobEffectInstance current = player.getEffect(TP_BOOST.get());
            int wantTicks = (int) Math.min(Integer.MAX_VALUE, left / 50L);
            if (current == null || Math.abs(current.getDuration() - wantTicks) > 40)
                applyFor(player, left);
        }
        else if (player.getEffect(TP_BOOST.get()) != null)
        {
            player.removeEffect(TP_BOOST.get());
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

    /** Never cleansable: milk must not end the boost. The store is the authority; see the class docs. */
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getCurativeItems()
    {
        return java.util.List.of();
    }
}
