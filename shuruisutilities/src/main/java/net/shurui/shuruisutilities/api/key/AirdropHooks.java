package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.MinecraftServer;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE airdrops (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). The {@code Airdrop}
 * module, the drop manager (still {@code <SUdir>/airdrop.json}), its compass marker and {@code /airdrop} live in the
 * key. They are reached from the shard airdrop poller ({@code ShardAirdrop}, in the key since Sh2: a spawn or clear
 * fired on a sibling server) and the NPC region rename ({@code CommandNpcRegion}, which repoints a
 * live drop's owning region).
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: no drop is active, nothing spawns or clears, a rename has
 * nothing to repoint. Keyless {@code airdrop.json} is never read or written.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class AirdropHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "airdrop";

    private AirdropHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether airdrops are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether a drop is currently live on this server. Keyless: false. */
        default boolean hasActiveDrop()
        {
            return false;
        }

        /** Land a drop now (a network-wide airdrop fired elsewhere). Keyless: nothing. */
        default void spawnAirdrop(MinecraftServer server)
        {
        }

        /** Remove the live drop's chest (a network-wide clear). Keyless: nothing. */
        default void removeActiveChest(MinecraftServer server)
        {
        }

        /** An NPC region was renamed: repoint a live drop that belongs to it. Keyless: nothing. */
        default void onNpcRegionRenamed(String oldName, String newName)
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

    /** Whether airdrops are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
