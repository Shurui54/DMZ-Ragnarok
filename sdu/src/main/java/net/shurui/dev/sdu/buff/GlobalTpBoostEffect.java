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
 * The visible pip for the server-wide /tpboost window. Unlike {@link TpBoostEffect} (a per-player gem readout backed by
 * {@link TokenBuffStore}), the authority for this one lives in shuruisutilities' TpBoostState, which sdu must not import
 * (the tree invariant is one-way: SU reads sdu, never the reverse). So this class holds ONLY the registry entry and the
 * apply/remove/reconcile primitives; the SU side reads its own state and passes in the remaining time.
 *
 * <p>That remaining time (the shared-clock global window) is the single source of truth, NOT the effect's own duration.
 * The SU driver re-derives the pip from it on login, respawn and shard arrival, and in a once-per-second sweep that
 * re-applies it if milk or {@code /effect clear} removed it and drops it when the window ends. That is why it is not
 * curative and why a missing pip never shortens the boost: the TP multiply reads the same window, so the number and the
 * pip can never disagree.
 *
 * <p>This is a DIFFERENT effect id from {@link TpBoostEffect}, and it writes nothing into {@link TokenBuffStore}, so it
 * is never seen by the one-gem-at-a-time rule and can sit alongside a personal TP gem pip without either clearing the
 * other.
 */
public final class GlobalTpBoostEffect extends MobEffect
{
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, DmzNpc.MODID);

    /**
     * Registered under this name, so the texture resolves at textures/mob_effect/global_tp_boost.png. Pinned: renaming
     * a registered effect orphans it in every saved player that carries the pip, so this string must not change.
     */
    public static final String NAME = "global_tp_boost";

    public static final RegistryObject<MobEffect> GLOBAL_TP_BOOST =
            EFFECTS.register(NAME, GlobalTpBoostEffect::new);

    private GlobalTpBoostEffect()
    {
        // BENEFICIAL so it sits with the buffs; a warm gold to read apart from the aqua per-player TP gem pip.
        super(MobEffectCategory.BENEFICIAL, 0xFFD54A);
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
            // ambient false keeps it a solid pip; showParticles false stops a long swirl; showIcon true so it reads in
            // the inventory and HUD.
            player.addEffect(new MobEffectInstance(GLOBAL_TP_BOOST.get(), ticks, 0, false, false, true));
        }
        catch (Throwable ignored)
        {
            // A readout must never be able to break the boost that produced it.
        }
    }

    /**
     * Reconcile the pip against the remaining window time the SU side hands in, the ONE input that decides whether it
     * should be on and for how long.
     *
     * <p>If time is left it (re)applies the pip with those ticks, so a cleared pip comes back at the right length and a
     * stale one (wrong duration after a relog or a drift) is corrected. If nothing is left it removes the pip. Called on
     * login/respawn/shard arrival and on the periodic sweep, always with a freshly read remaining time.
     */
    public static void reconcile(ServerPlayer player, long remainingMillis)
    {
        if (player == null)
            return;
        if (remainingMillis > 0L)
        {
            // Re-apply only when it is missing or has drifted from the wanted time, so we do not spam addEffect.
            MobEffectInstance current = player.getEffect(GLOBAL_TP_BOOST.get());
            int wantTicks = (int) Math.min(Integer.MAX_VALUE, remainingMillis / 50L);
            if (current == null || Math.abs(current.getDuration() - wantTicks) > 40)
                applyFor(player, remainingMillis);
        }
        else if (player.getEffect(GLOBAL_TP_BOOST.get()) != null)
        {
            player.removeEffect(GLOBAL_TP_BOOST.get());
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

    /** Never cleansable: milk must not end the window. The global window is the authority; see the class docs. */
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getCurativeItems()
    {
        return java.util.List.of();
    }
}
