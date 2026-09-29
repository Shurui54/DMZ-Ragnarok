package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.minecraft.world.entity.player.Player;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.ranks.RankManager;

/**
 * Core-side hook for the PRIVATE rank system (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps
 * {@code ranks.RankManager} as the rank index and badge-glyph helpers (the client draws badges from it) and as the
 * facade for resolving a player's rank, plus packets 13 ({@code PacketRankSync}) and 25 ({@code PacketRankAssets}),
 * the client cache, pack finder and the chat / tab / nameplate mixins. The Ranks module, the badge sync, the badge
 * asset streaming and {@code /rank} live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, every player resolves to
 * the index's default rank (the answer the keyless permission helper gives for an unset {@code su.rank}), and no
 * badge map or asset is ever sent.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class RankHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "ranks";

    private RankHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the rank system is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The rank from the player's groups' {@code su.rank}, else the default rank. Keyless: the default rank. */
        default RankManager.Rank rankForPlayer(Player player)
        {
            return RankManager.defaultRank();
        }

        /** As {@link #rankForPlayer}, for a player not connected here. Keyless: the default rank. */
        default RankManager.Rank rankForUuid(UUID uuid, String username)
        {
            return RankManager.defaultRank();
        }

        /** Push every player's badge to every client now. Keyless: no-op (no badges are synced). */
        default void broadcast()
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

    /** Whether the rank system is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
