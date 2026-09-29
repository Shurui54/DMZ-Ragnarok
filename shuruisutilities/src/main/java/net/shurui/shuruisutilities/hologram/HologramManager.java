package net.shurui.shuruisutilities.hologram;

import java.util.Set;

import net.shurui.shuruisutilities.api.key.HologramHooks;

/**
 * The hologram store, as core sees it (S14: facade by FQN). The Hologram module and the store's body (loading and
 * saving {@code holograms.json}, spawning the TextDisplay stacks, the animation tick, the stale-display janitor, the
 * gif billboards and their sync, {@code /hologram} and the editor row) moved into the Ragnarok Key
 * ({@code net.shurui.ragnarokkey.hologram}). This class keeps its name, the display tag and frame constants, and the
 * lookups the Deletion Wand makes, which go through {@link HologramHooks}: keyless there are no holograms (nothing is
 * loaded, spawned or written).
 */
public class HologramManager
{
    /** Tag stamped on every hologram display entity (saved in chunks; unchanged). */
    public static final String TAG = "su_hologram";

    /** Game ticks each frame of an animated line is shown for (read by {@link HologramText}). */
    public static final int FRAME_TICKS = 40;

    private static final HologramManager INSTANCE = new HologramManager();

    /** The one store view (its calls go through {@link HologramHooks}). */
    public static HologramManager instance()
    {
        return INSTANCE;
    }

    protected HologramManager() {}

    /** The hologram of this name, or null. Keyless: null. */
    public Hologram get(String name)
    {
        return name == null ? null : HologramHooks.get().get(name);
    }

    /** Every hologram name. Keyless: empty. */
    public Set<String> getNames()
    {
        return HologramHooks.get().names();
    }

    /** Delete a hologram (definition and live displays); returns the displays removed. Keyless: 0, nothing. */
    public int delete(String name)
    {
        return HologramHooks.get().delete(name);
    }
}
