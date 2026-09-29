package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.minecraft.world.entity.Entity;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.sparring.SparConfig;

/**
 * Core-side hook for the PRIVATE player sparring (logic in the Ragnarok Key, {@code dmz_ragnarok_key}): the
 * {@code Sparring} module, the live spars, invites and cooldowns, the TP payout, {@code /spar}, the sparring hub rows
 * and the {@code su:cfg_sparring} shard state. Core keeps {@code sparring.SparManager} as a facade (the spar-pair
 * question the PvP layers ask and the tuning the guild chamber reuses, routed here), {@code SparConfig} (the file
 * format of {@code sparring/config.json}), {@code SparData} (SavedData {@code shuruisutilities_sparring}),
 * {@code SparCarry} (the persisted NBT mirror) and {@code SparDmz} (the DMZ helpers the chamber shares).
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: there are no spars. Nobody is in a spar, no pair is a spar
 * pair, and the tuning is the code default ({@code sparring/config.json} is neither read nor written keyless).
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class SparringHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "sparring";

    /** The keyless tuning: the code defaults, never loaded from or saved to disk. */
    private static final SparConfig DEFAULT_CONFIG = new SparConfig();

    private SparringHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether sparring is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The live sparring tuning. Keyless: the code defaults. */
        default SparConfig config()
        {
            return DEFAULT_CONFIG;
        }

        /** Whether this player is in a live spar. Keyless: false. */
        default boolean isInSpar(UUID id)
        {
            return false;
        }

        /** Whether the two entities are the two fighters of one live spar. Keyless: false. */
        default boolean isSparPair(Entity a, Entity b)
        {
            return false;
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

    /** Whether sparring is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
