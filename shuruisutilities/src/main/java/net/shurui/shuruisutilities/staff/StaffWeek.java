package net.shurui.shuruisutilities.staff;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;

/**
 * Week arithmetic for the staff clocked-hours goal.
 *
 * <p>A week runs Monday 00:00 to the next Monday 00:00, read in the configured zone (UTC by default, for the same
 * reason the scheduled restart uses UTC: on a shard network all servers must agree on one instant, and a
 * daylight-saving zone would make them disagree by an hour twice a year). A week is identified by a single stable
 * long, the epoch day of its Monday, which is identical on every shard because they share the zone. This mirrors
 * {@code TaskPeriod.WEEKLY}.
 */
public final class StaffWeek
{
    private StaffWeek() {}

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** The id (the epoch day of the Monday) of the week the given instant falls in. */
    public static long weekIdOf(long epochMillis, ZoneId zone)
    {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    /** The instant (epoch millis) of the Monday 00:00 that begins the given week. */
    public static long weekStartMillis(long weekId, ZoneId zone)
    {
        return LocalDate.ofEpochDay(weekId).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The previous week's id. Weeks are seven epoch days apart. */
    public static long previousWeek(long weekId)
    {
        return weekId - 7L;
    }

    /** "2026-09-07", the date of the week's Monday, for the notice text. */
    public static String label(long weekId)
    {
        return LocalDate.ofEpochDay(weekId).format(YMD);
    }

    /**
     * Split the span {@code [from, to]} across the weeks it touches, returning weekId to millis. A span that stays
     * within one week yields a single entry; a span that crosses a Monday 00:00 boundary is divided exactly at the
     * boundary, so the time on each side lands in the correct week.
     */
    public static Map<Long, Long> distribute(long from, long to, ZoneId zone)
    {
        Map<Long, Long> out = new HashMap<>();
        if (to <= from)
            return out;
        long cursor = from;
        // Bounded by the number of week boundaries between from and to, so a stray huge span cannot spin forever.
        int guard = 0;
        while (cursor < to && guard++ < 10000)
        {
            long week = weekIdOf(cursor, zone);
            long nextBoundary = weekStartMillis(week + 7L, zone);
            long segmentEnd = Math.min(to, nextBoundary);
            if (segmentEnd <= cursor)
                break;
            out.merge(week, segmentEnd - cursor, Long::sum);
            cursor = segmentEnd;
        }
        return out;
    }
}
