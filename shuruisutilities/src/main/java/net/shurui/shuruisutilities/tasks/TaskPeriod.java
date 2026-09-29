package net.shurui.shuruisutilities.tasks;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * The three task boards: one that turns over every day, one every week, one every month.
 *
 * <p>Each is an independent board with its own pool of task definitions, its own three slots per player, and its
 * own reroll price. Nothing is shared between them except the machinery, so a player's dailies rolling over does
 * not disturb what they are part-way through on the monthly board.
 *
 * <h2>Resets run on the REAL clock, not on playtime</h2>
 * A daily is a daily whether or not anyone logged in, which is the whole reason a player checks back. So the
 * boundary is a wall-clock instant: midnight tonight, midnight on the coming Monday, midnight on the first of next
 * month. That also means a reset can be MISSED - the server can be down over midnight, or a player can be away for
 * a fortnight - so nothing is scheduled in advance. Each board stores the instant it last rolled and is compared
 * against the current period on the way past; a player who returns after six weeks rolls once, not forty-two times.
 *
 * <p>The zone is the SERVER's, deliberately. Players are in many timezones and a board that turned over at a
 * different moment for each of them could not be talked about ("dailies reset at midnight" has to mean one thing),
 * nor could a leaderboard or an event be lined up against it.
 */
public enum TaskPeriod
{
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly");

    /** How many task slots a player has on each board. Three, matching the three rows the panel draws. */
    public static final int SLOTS = 3;

    /** Stable lowercase key. PERSISTED in player data and in the pool file, so never rename these. */
    public final String key;

    TaskPeriod(String key)
    {
        this.key = key;
    }

    /** "Daily" / "Weekly" / "Monthly", for the gold text on the tab plaque. */
    public String langKey()
    {
        return "gui.dmz_ragnarok.tasks.period." + key;
    }

    private static final TaskPeriod[] VALUES = values();

    /** Never throws: an unknown or absent key reads as DAILY rather than failing a load. */
    public static TaskPeriod byKey(String key)
    {
        if (key != null)
        {
            String lower = key.toLowerCase(Locale.ROOT);
            for (TaskPeriod p : VALUES)
                if (p.key.equals(lower))
                    return p;
        }
        return DAILY;
    }

    /**
     * Which period the given instant falls in, as a single comparable number.
     *
     * <p>This is the whole of the reset rule. Two instants are in the same period exactly when this returns the
     * same value for both, so "has the board rolled over" is one integer comparison and needs no scheduler, no
     * timer and no catch-up loop. A gap of any length collapses to a single roll, because only the CURRENT value
     * is ever compared against the stored one.
     *
     * <p>Encoded so the numbers never collide across periods and always increase with time:
     * a day is its epoch day, a week is the epoch day of its Monday, a month is year*12 + month.
     */
    public long stamp(ZoneId zone)
    {
        LocalDateTime now = LocalDateTime.now(zone);
        return switch (this)
        {
            case DAILY -> now.toLocalDate().toEpochDay();
            case WEEKLY -> now.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
            case MONTHLY -> (long) now.getYear() * 12L + now.getMonthValue();
        };
    }
}
