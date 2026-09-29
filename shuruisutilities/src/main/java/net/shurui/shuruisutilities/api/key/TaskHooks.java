package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE task boards (daily, weekly and monthly tasks; logic in the Ragnarok Key,
 * {@code dmz_ragnarok_key}). Core keeps the task DTOs ({@code TaskDef}, {@code TaskSlot}, {@code TaskGoal},
 * {@code TaskPeriod}, {@code TaskTargets}), the client screens, and {@code tasks.TaskProgressEvents.onZeniEarned} as
 * a facade over this hook, because zeni credits are counted from outside the task system.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and progress is dropped.
 * The pool file {@code tasks.json} is never read or written keyless, and a player's boards (the {@code su_tasks}
 * entry of their persisted tag) are left exactly as they are.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class TaskHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "tasks";

    private TaskHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the task boards are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Count zeni a player just earned toward an {@code earn_zeni} task. Keyless: ignored. */
        default void onZeniEarned(UUID player, long amount)
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

    /** Whether the task boards are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
