package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE ocarina (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the song
 * list, the tuning holder, the effects, both packets (86 and 87), the item, the client screen and the pose, and puts
 * the songs into DragonMineZ's skill shop ({@code OcarinaSkills}) only while this hook is available.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and every request is
 * ignored, so no menu opens, no song is sold and Tapion is not let into the world. A player's bought song levels are
 * DMZ skill levels and stay on the character untouched, so a keyed server reads them again.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class OcarinaHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "ocarina";

    private OcarinaHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the ocarina is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** A {@code PacketOcarina} arrived from this player (server thread). Keyless: ignored. */
        default void onPacket(ServerPlayer player, int action, int song, int score)
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

    /** Whether the ocarina is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
