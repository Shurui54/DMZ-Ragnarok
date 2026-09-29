package net.shurui.shuruisutilities.api.key;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.npcregion.NpcRegion;

/**
 * Core-side hook for the PRIVATE NPC regions (logic in the Ragnarok Key, {@code dmz_ragnarok_key}): the
 * {@code NpcRegions} module, the region store body ({@code npcregions.json}), the spawner tick and tether, the census,
 * the kill rewards, the NPC behaviour enforcement, the client outline sync, {@code /npcregion} and the editor row.
 * Core keeps {@code npcregion.NpcRegionManager} as a facade (its tag and node constants, its public statics and the
 * store calls routed here), the region model ({@code NpcRegion}, {@code NpcSpawnConfig}, {@code CustomDrop}), the
 * packets, the editor screens, the event bundle import, {@code RegionSurfacePicker}, {@code GroundBlocks} and the
 * kill-TP modifier.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: there are no NPC regions. Every lookup answers null or empty,
 * and put, delete and rename do nothing, so {@code npcregions.json} is never read or written keyless.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class NpcRegionHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "npcregions";

    private NpcRegionHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether NPC regions are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The region of this name (case-insensitive). Keyless: null. */
        default NpcRegion get(String name)
        {
            return null;
        }

        /** A fresh copy of every region. Keyless: empty. */
        default Collection<NpcRegion> all()
        {
            return new ArrayList<>();
        }

        /** The regions that should run right now. Keyless: empty. */
        default Collection<NpcRegion> live()
        {
            return new ArrayList<>();
        }

        /** Every region name, sorted. Keyless: empty. */
        default List<String> names()
        {
            return new ArrayList<>();
        }

        /** Store a region. Keyless: nothing. */
        default void put(NpcRegion region)
        {
        }

        /** Delete a region. Keyless: false. */
        default boolean delete(String name)
        {
            return false;
        }

        /** Rename a region. Keyless: false. */
        default boolean rename(String oldName, String newName)
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

    /** Whether NPC regions are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
