package net.shurui.shuruisutilities.tasks;

import java.util.Locale;

/**
 * What a task asks a player to DO, and how progress toward it is counted.
 *
 * <p>Deliberately a small, closed set of kinds rather than a scripting hook. Every kind here is something the
 * server already sees happen through a Forge event, so a task can be counted without the task system knowing
 * anything about the feature it is counting: a kill is a kill whether the mob came from a spawner, a raid or the
 * open world.
 *
 * <p>The three fields mean the same thing for every kind, which is what keeps the editor to one row per task and
 * the counter to one switch: {@code target} names the thing (an entity id, an item id, a block id, a dimension
 * id), and {@code amount} is how many times it has to happen. A kind that needs no target ignores it.
 *
 * <p>ADDING A KIND is meant to be cheap: add the constant, add its case to the counter, and it appears in the
 * editor dropdown. Nothing else keys off this enum, and the ordinal is never persisted (the {@link #key} is), so
 * the order here carries no meaning and can change.
 */
public enum TaskGoal
{
    /** Kill {@code amount} of entity {@code target} (an entity type id, or blank for anything). */
    KILL("kill"),

    /** Pick up {@code amount} of item {@code target}. Counts the item reaching the inventory, however it got there. */
    COLLECT("collect"),

    /** Break {@code amount} of block {@code target}. */
    MINE("mine"),

    /** Craft {@code amount} of item {@code target}. */
    CRAFT("craft"),

    /** Set foot in dimension {@code target}. {@code amount} is ignored; arriving once is the whole task. */
    VISIT("visit"),

    /**
     * Earn {@code amount} zeni from any source that mints it (shop sales, kill and crate rewards, wishes, task
     * payouts). Counted through {@code EconomyManager.earn}, never an event, so zeni merely moving between players
     * ({@code /pay}, trades, the auction house) or coming back as a refund does not count.
     */
    EARN_ZENI("earn_zeni");

    /** Stable lowercase key. PERSISTED on every task definition, so never rename these. */
    public final String key;

    TaskGoal(String key)
    {
        this.key = key;
    }

    public String langKey()
    {
        return "gui.dmz_ragnarok.tasks.goal." + key;
    }

    private static final TaskGoal[] VALUES = values();

    /** Never throws: an unknown or absent key reads as KILL rather than failing a load. */
    public static TaskGoal byKey(String key)
    {
        if (key != null)
        {
            String lower = key.toLowerCase(Locale.ROOT);
            for (TaskGoal g : VALUES)
                if (g.key.equals(lower))
                    return g;
        }
        return KILL;
    }

    /** Whether {@code target} means anything for this kind, so the editor can grey the field out when it does not. */
    public boolean usesTarget()
    {
        return this != EARN_ZENI;
    }

    /** Whether a count is meaningful, so the editor can grey it out for the one kind that is a single event. */
    public boolean usesAmount()
    {
        return this != VISIT;
    }
}
