package net.shurui.shuruisutilities.api.key;

import java.util.HashSet;
import java.util.Set;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.hologram.Hologram;

/**
 * Core-side hook for the PRIVATE holograms (logic in the Ragnarok Key, {@code dmz_ragnarok_key}): the
 * {@code Hologram} module, the store body ({@code holograms.json}), spawning and animating the TextDisplay stacks, the
 * stale-display janitor, the gif billboards and their sync to players, {@code /hologram} and the editor row. Core
 * keeps {@code hologram.HologramManager} as a facade (the tag and frame constants, and the lookups the Deletion Wand
 * makes, routed here), the {@code Hologram} model, {@code HologramText}, {@code HologramGifs} (the client decodes with
 * it), the packets and the client renderers.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: there are no holograms. Lookups answer null or empty and
 * delete removes nothing, so {@code holograms.json} is never read or written keyless.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class HologramHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "holograms";

    private HologramHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether holograms are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The hologram of this name. Keyless: null. */
        default Hologram get(String name)
        {
            return null;
        }

        /** Every hologram name. Keyless: empty. */
        default Set<String> names()
        {
            return new HashSet<>();
        }

        /** Delete a hologram; the number of displays removed. Keyless: 0. */
        default int delete(String name)
        {
            return 0;
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

    /** Whether holograms are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
