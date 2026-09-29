package net.shurui.shuruisutilities.tasks;

import java.util.UUID;

import net.shurui.shuruisutilities.api.key.TaskHooks;

/**
 * Facade for the one task-progress entry point that is called from outside the task system: a zeni credit. The
 * task boards and every progress listener live in the Ragnarok Key; this keeps the FQN and the static that the
 * economy and the bounty payout call, and forwards to {@link TaskHooks} (keyless: ignored). No logic here.
 */
public final class TaskProgressEvents
{
    private TaskProgressEvents()
    {
    }

    /** EARN_ZENI: count a credit toward the player's active {@code earn_zeni} task, if any. */
    public static void onZeniEarned(UUID id, long amount)
    {
        TaskHooks.get().onZeniEarned(id, amount);
    }
}
