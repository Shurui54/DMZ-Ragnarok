package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerLevel;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.zorb.ZOrbEntity;

/**
 * Core-side hook for the PRIVATE Z orb feature (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps
 * the entity type, the config DTOs, the packets, the renderer and the editor; this hook is how the key's spawning,
 * chain and reward logic runs, and only when the key is installed.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, and {@link Impl#entityTick}
 * discards the orb on its first server tick, so a keyless {@code /summon dmz_ragnarok:z_orb} (or a stray orb from a
 * mixed-version shard) never survives. Everything else is inert. The key swaps the whole impl in via
 * {@link #install(Impl)}, which also marks {@link KeyFeatures} so the client login sync reports the feature.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class ZOrbHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "zorbs";

    private ZOrbHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the Z orb feature is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Called each SERVER tick for a live orb. Keyless: discard it, so no orb ever survives a tick. */
        default void entityTick(ZOrbEntity e)
        {
            e.discard();
        }

        /** Notified when a region's config changed (put / delete / rename / editor save). Keyless: no-op. */
        default void onRegionChanged(String regionKey)
        {
        }

        /** Spawn a chain now in the named region; returns how many orbs were placed. Keyless: 0. */
        default int spawnNow(ServerLevel level, String regionKey, int length)
        {
            return 0;
        }

        /** Event-engine hook: scale the spawn rate (an "orb rush"). Keyless: no-op. */
        default void setEventRateMultiplier(double multiplier)
        {
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

    /** Whether the Z orb feature is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
