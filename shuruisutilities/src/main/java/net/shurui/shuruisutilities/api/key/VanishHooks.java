package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.commands.player.VanishState;

/**
 * Core-side hook for staff vanish (S18a). The vanish mixins (ping sample, tab list packets, join / leave / death
 * messages), the re-hide handler and the shard tables ask this hook; the toggle ({@code /vanish}) and the cross-shard
 * {@code su:vanish} registration live in the Ragnarok Key ({@code dmz_ragnarok_key}).
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour, and they are exactly what a keyless server did before the
 * move: they read core's {@link VanishState}, which is seeded from {@code VanishStorage} at every server start. A
 * player vanished on a keyed server therefore stays hidden on a keyless one, and nobody can toggle it there (the
 * command is the key's). {@code VanishStorage} and its {@code shuruisutilities_vanish} data are untouched keyless.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class VanishHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "vanish";

    private VanishHooks() {}

    /** The behaviour the key installs. Every method has a keyless default (the persisted state, read only). */
    public interface Impl
    {
        /** Whether /vanish is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether this player is vanished. Keyless: the persisted state loaded at server start. */
        default boolean isVanished(UUID uuid)
        {
            return VanishState.isVanished(uuid);
        }

        /** Whether this viewer may see this target. Keyless: the same relation over the persisted state. */
        default boolean canSee(UUID viewerId, UUID targetId)
        {
            return VanishState.canSee(viewerId, targetId);
        }

        /** Send a real join / leave / death line only to those who can see the subject. Keyless: the same. */
        default void announcePresenceToSeers(ServerPlayer subject, Component message)
        {
            VanishState.announcePresenceToSeers(subject, message);
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

    /** Whether /vanish is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }

    public static boolean isVanished(UUID uuid)
    {
        return impl.isVanished(uuid);
    }

    public static boolean canSee(UUID viewerId, UUID targetId)
    {
        return impl.canSee(viewerId, targetId);
    }

    public static boolean canSee(ServerPlayer viewer, UUID targetId)
    {
        return impl.canSee(viewer == null ? null : viewer.getUUID(), targetId);
    }

    public static void announcePresenceToSeers(ServerPlayer subject, Component message)
    {
        impl.announcePresenceToSeers(subject, message);
    }
}
