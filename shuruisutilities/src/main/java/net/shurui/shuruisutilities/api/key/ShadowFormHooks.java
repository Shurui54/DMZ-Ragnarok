package net.shurui.shuruisutilities.api.key;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.compat.dmz.ShadowDragonRepair;
import net.shurui.shuruisutilities.corrupted.ShadowDragonStorage;

/**
 * Core-side hook for the PRIVATE shadow dragon transformation, Omega Shenron (logic in the Ragnarok Key,
 * {@code dmz_ragnarok_key}). The shadow dragon race and its sub-race unlocks stay PUBLIC in core, as do the kill
 * tallies, {@code ShadowDragonStorage}, {@code RaceUnlocks} (the {@code omega_shenron} entitlement node included) and
 * {@code /raceunlock}; this hook is how the key grants, applies and re-applies the Omega form, and only when the key is
 * installed.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, the form skill is never
 * raised, the seventh killing blow grants nothing, login re-applies nothing and the repair reports nothing. A stored
 * entitlement is left untouched, so a keyed server applies it again.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class ShadowFormHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "shadowform";

    private ShadowFormHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the Omega form is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /**
         * Raise a shadow dragon player's {@code superforms} skill to the given form's unlock level (only ever raises,
         * and a no-op for any other race). Returns whether the skill is now met. Keyless: false, nothing applied.
         */
        default boolean applyFormSkill(ServerPlayer player, String formName)
        {
            return false;
        }

        /** A shadow dragon killing blow was credited (tallies already recorded by core). Keyless: no-op. */
        default void afterKillCredited(MinecraftServer server, ServerPlayer killer, ShadowDragonStorage storage,
                                       int slot)
        {
        }

        /** Login pass for the Omega form (re-apply, pending notice). Keyless: no-op. */
        default void onLogin(ServerPlayer player)
        {
        }

        /** Re-apply the form to every entitled online player, one line per player. Keyless: nothing. */
        default List<ShadowDragonRepair.RepairLine> repairOnline(MinecraftServer server)
        {
            return List.of();
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether the Omega form is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
