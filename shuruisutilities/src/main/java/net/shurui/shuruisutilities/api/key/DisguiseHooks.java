package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE staff DISGUISE feature (logic in the Ragnarok Key, {@code dmz_ragnarok_key}).
 *
 * <p>Core keeps everything that has to load in both outputs: the synced identity DTO ({@code DisguiseView}), the
 * server registry ({@code DisguiseState}), the packet codec ({@code PacketDisguiseSync}, id 148 on the SU channel)
 * and every render / name / rank / tab / chat / skin override. This hook is the one place the PRIVATE part lives:
 * choosing the target (a named player, online or offline, or a random recently-seen one who is offline everywhere),
 * capturing that target's identity, skin profile and DragonMineZ appearance, permission gating, audit logging, and
 * turning that into a {@code DisguiseView} handed to {@code DisguiseState}. It runs ONLY when the key is installed.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and every action tells the
 * caller the feature is unavailable, so a keyless server (or a stray command) can never disguise anyone. The key
 * swaps the whole impl in via {@link #install(Impl)}, which also marks {@link KeyFeatures} so the client login sync
 * reports the feature and gates any private UI on it.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class DisguiseHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "disguise";

    private DisguiseHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the disguise feature is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /**
         * Disguise {@code staff} as {@code targetName}, or, when {@code targetName} is null or blank, as a random
         * eligible player (seen recently, offline everywhere). The key resolves and captures the target, gates on permission, writes the
         * {@code DisguiseView} into {@code DisguiseState} and audit-logs the action. Keyless: no-op.
         */
        default void disguise(ServerPlayer staff, String targetName)
        {
        }

        /** Remove {@code staff}'s disguise entirely (a stamped tombstone) and audit-log it. Keyless: no-op. */
        default void undisguise(ServerPlayer staff)
        {
        }

        /**
         * Toggle {@code staff}'s configured disguise on or off WITHOUT losing it, and audit-log it. Disabling keeps the
         * stored, synced view and renders the real identity; enabling restores it. Keyless: no-op.
         */
        default void setEnabled(ServerPlayer staff, boolean enabled)
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

    /** Whether the disguise feature is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
