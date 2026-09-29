package net.shurui.shuruisutilities.api.key;

import java.util.Collection;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.crate.Crate;

/**
 * Core-side hook for the PRIVATE crates (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the crate
 * blocks, items and block entity (registry, {@code crate.block}), their client renderers, and the {@link Crate} /
 * {@code CrateReward} records (the {@code crates.json} shape, which the cosmetics crates also read). The module,
 * {@code CrateManager} (still {@code crates.json}, the right-click on a bound block, which it handles on the Forge bus
 * itself, key stamping with the unchanged {@code SUCrateKey} tag, rolls and reveals), {@code /crate} and the hub rows
 * live in the key. The cosmetics shop and cosmetic crate editor in core reach the crate list through here.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, no crate list is loaded,
 * every lookup is empty and nothing is created, saved or minted. Keyless {@code crates.json} is never read or written
 * (the default crates are not seeded either), so a keyed server reads it again unchanged.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class CrateHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "crates";

    private CrateHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether crates are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether the crate list is loaded on this server (the Crate module is running). Keyless: false. */
        default boolean loaded()
        {
            return false;
        }

        /** Every crate name. Keyless: empty. */
        default Collection<String> crateNames()
        {
            return List.of();
        }

        /** The crate with this name, or null. Keyless: null. */
        default Crate crate(String name)
        {
            return null;
        }

        /** Create an empty crate with this name (no-op if it exists). Keyless: ignored. */
        default void createCrate(String name)
        {
        }

        /** Persist the crate list. Keyless: ignored. */
        default void save()
        {
        }

        /** A genuine key for this crate (stamped so it opens it). Keyless: {@link ItemStack#EMPTY}. */
        default ItemStack makeKey(Crate crate, int count)
        {
            return ItemStack.EMPTY;
        }

        /** What this crate's key is called, e.g. "Minor Namek Key". Keyless: empty. */
        default String keyLabel(Crate crate)
        {
            return "";
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

    /** Whether crates are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
