package net.shurui.shuruisutilities.staff;

/**
 * The weekly staff goal's shared wording and candidate row. Plain formatting and a record, no logic: the goal itself
 * (evaluation, notices, the {@code staff_goal.json} store) lives in the Ragnarok Key. Kept in core because the shard
 * layer's network-wide week evaluation ({@code ShardStaff.evaluateWeek}) builds the same notice text.
 */
public final class StaffGoalNotice
{
    private StaffGoalNotice() {}

    /** One staffer to judge: their id, name, and this server's own banked time for the week in question. */
    public record Candidate(String uuid, String name, long localMillis) {}

    /** "Name clocked 6h 12m of their 10h weekly goal (week of 2026-09-07)." Plain text; colour is added at delivery. */
    public static String formatNotice(String name, long clockedMillis, long goalMillis, long weekId)
    {
        return (name == null || name.isBlank() ? "A staff member" : name)
                + " clocked " + hoursMinutes(clockedMillis) + " of their " + hoursMinutes(goalMillis)
                + " weekly goal (week of " + StaffWeek.label(weekId) + ").";
    }

    /** "6h 12m", "10h", "42m", or "0h" for nothing at all. */
    public static String hoursMinutes(long millis)
    {
        long minutes = Math.max(0L, millis) / 60000L;
        long hours = minutes / 60L;
        minutes %= 60L;
        if (hours > 0 && minutes > 0)
            return hours + "h " + minutes + "m";
        if (hours > 0)
            return hours + "h";
        if (minutes > 0)
            return minutes + "m";
        return "0h";
    }
}
