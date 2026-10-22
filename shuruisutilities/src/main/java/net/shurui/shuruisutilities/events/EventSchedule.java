package net.shurui.shuruisutilities.events;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The pure time math behind the event scheduler, deliberately free of every Minecraft, Forge and NBT type so it
 * can be reasoned about and unit tested on its own (see the standalone harness under tools/events-tests). Nothing
 * here reads a wall clock: {@code now} is always passed in, and the caller is expected to hand it the shared
 * database clock ({@code System.currentTimeMillis() + ShardStateSync.dbClockOffsetMillis()}), never
 * {@code System.currentTimeMillis()} directly, because shard wall clocks skew by hours.
 *
 * <h2>Author time to absolute instant</h2>
 * The operator authors a local wall time in a named zone ("2026-10-24T18:00", "America/New_York"). The absolute
 * instant is resolved with {@link ZonedDateTime}, so the stored epoch is the real moment that local time occurs,
 * with the zone's DST rules applied once at resolution. Storing the absolute instant is what removes per shard
 * DST divergence; the local strings are kept only for editing and display.
 *
 * <h2>Annual recurrence</h2>
 * An {@code ANNUAL} event's authored month, day and time repeat each year in the zone. {@link #resolveWindow}
 * recomputes the current or next occurrence from {@code now} on every call, so a shard that boots months later,
 * or ticks past a year boundary, converges on the right window with no stored state.
 *
 * <h2>Feb 29 authored on an annual event</h2>
 * If an annual event is authored to start (or end) on February 29, the occurrence in a non leap year is pulled
 * back to February 28. This is {@link LocalDateTime#withYear(int)}'s own documented behaviour (the day of month
 * is adjusted to the last valid day of the month), and it is what we want: the event still runs once a year,
 * one day earlier in the three non leap years out of four, rather than throwing or being skipped.
 *
 * <h2>Maximum window</h2>
 * A window longer than {@link #MAX_WINDOW_DAYS} days is rejected at save time by {@link #windowTooLong}. An
 * event is a bounded seasonal thing; a multi year window is almost always a typo (a wrong year on the end date),
 * and for an annual event a window at or over a year would overlap its own next occurrence and make
 * {@link #resolveWindow}'s "an occurrence running now wins" branch ambiguous. Rejecting it at authoring time is
 * simpler than defining that overlap.
 *
 * <h2>Yearly day ranges (the editor's month and day dropdowns)</h2>
 * A def with {@code startMonth}/{@code startDay}/{@code endMonth}/{@code endDay} set is a yearly day range, resolved
 * by {@link #dayRangeWindow}: every year it runs from 00:00 on the start day to 00:00 after the end day, in the
 * def's zone (the saving server's own zone), so both days are included in full. An end day earlier in the year than
 * the start day wraps into the next year (Dec 20 to Jan 5). Start equal to end is that one whole day. February 29
 * becomes February 28 in a non leap year, as above. Midnight is taken with {@link LocalDate#atStartOfDay(ZoneId)},
 * so in a zone whose DST change skips midnight the day starts at the first instant that exists. A range may cover at
 * most {@link #MAX_WINDOW_DAYS} days counted on a non leap year ({@link #rangeDays}), which always leaves at least
 * one day between an occurrence and the next one, so occurrences never overlap. {@link #window} picks the right
 * resolver for a def, so every caller handles both kinds.
 */
public final class EventSchedule
{
    private EventSchedule() {}

    /** The local wall time format the editor writes and stores: "yyyy-MM-ddTHH:mm". */
    public static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** UTC datetime string the MariaDB projection stores, matching the bot's DATETIME(3) UTC contract. */
    public static final DateTimeFormatter DB_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** The longest window an event may span, in days. See the class doc for why a year-plus window is refused. */
    public static final int MAX_WINDOW_DAYS = 364;

    private static final long MAX_WINDOW_MILLIS = MAX_WINDOW_DAYS * 24L * 60L * 60L * 1000L;

    /** True if the zone id parses. A blank or unparseable id is rejected by the editor before it is stored. */
    public static boolean validZone(String zone)
    {
        if (zone == null || zone.isBlank())
            return false;
        try
        {
            ZoneId.of(zone.trim());
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Parse a stored zone id, falling back to UTC (loudly is the caller's job) so downstream never NPEs. */
    public static ZoneId zoneOrUtc(String zone)
    {
        if (zone == null || zone.isBlank())
            return ZoneOffset.UTC;
        try
        {
            return ZoneId.of(zone.trim());
        }
        catch (Exception e)
        {
            return ZoneOffset.UTC;
        }
    }

    /**
     * Resolve a "yyyy-MM-ddTHH:mm" local time in a named zone to absolute epoch millis, or throw if either the
     * local string or the zone will not parse. Used at save time to freeze the authored instant.
     */
    public static long resolveEpoch(String local, String zone)
    {
        LocalDateTime ldt = parseLocal(local);
        return ZonedDateTime.of(ldt, ZoneId.of(zone.trim())).toInstant().toEpochMilli();
    }

    /**
     * Read an authored local time. The canonical form is {@link #LOCAL} ("2026-10-24T18:00"), but the forms an admin
     * naturally types are accepted too: a space instead of the T, trailing seconds, and a bare date (midnight).
     * Throws when none of them fit, so the caller can name the bad value. Before this, a time typed with a space
     * silently failed to parse and the editor kept its default epoch of 0, which the validator then reported as a
     * window "longer than 364 days" (1970 to the end date) instead of as the unreadable time it was.
     */
    public static LocalDateTime parseLocal(String local)
    {
        if (local == null || local.isBlank())
            throw new IllegalArgumentException("empty time");
        String t = local.trim().replace(' ', 'T');
        if (t.length() == 10)
            return java.time.LocalDate.parse(t).atStartOfDay();
        return LocalDateTime.parse(t); // ISO_LOCAL_DATE_TIME: seconds optional
    }

    /** True if {@link #parseLocal} can read this string. */
    public static boolean validLocal(String local)
    {
        try
        {
            parseLocal(local);
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Server-side freeze of the authored window: when both locals and the zone parse, the absolute instants are
     * recomputed from them, so a client that sent stale or zero epochs still stores the window the admin typed.
     * Leaves the epochs alone otherwise (the validator then names what could not be read).
     */
    public static void freeze(EventDef.Schedule s)
    {
        if (s == null || !validZone(s.zone) || !validLocal(s.startLocal) || !validLocal(s.endLocal))
            return;
        s.startEpochMillis = resolveEpoch(s.startLocal, s.zone);
        s.endEpochMillis = resolveEpoch(s.endLocal, s.zone);
    }

    /** True if this instant falls inside the half open window [start, end). */
    public static boolean activeAt(long startEpochMillis, long endEpochMillis, long now)
    {
        return now >= startEpochMillis && now < endEpochMillis;
    }

    /**
     * True if the window [start, end) is longer than {@link #MAX_WINDOW_DAYS} days (or is inverted). The editor
     * calls this at save time and rejects such a def, naming the limit. A zero or negative span is left to the
     * "end after start" check, which is a clearer message; this one is only about the upper bound, so an inverted
     * pair (which has a negative span) is not flagged here.
     */
    public static boolean windowTooLong(long startEpochMillis, long endEpochMillis)
    {
        long span = endEpochMillis - startEpochMillis;
        return span > MAX_WINDOW_MILLIS;
    }

    /**
     * The window that is in force at {@code now}.
     *
     * <p>For a one shot ({@code annual == false}) event this is just the authored window. For an annual event it
     * is the occurrence currently running, or, if none is running, the soonest future occurrence, or, if there is
     * no future occurrence in range, the most recent past one. The authored strings and zone drive the month, day
     * and time; the authored epoch pair is used only as the fallback window when the locals cannot be parsed.
     *
     * @return {@code {startEpochMillis, endEpochMillis}} of the occurrence in force at {@code now}
     */
    public static long[] resolveWindow(long authoredStart, long authoredEnd, String startLocal, String endLocal,
                                       String zone, boolean annual, long now)
    {
        if (!annual)
            return new long[] { authoredStart, authoredEnd };

        LocalDateTime s;
        LocalDateTime e;
        ZoneId z;
        try
        {
            s = parseLocal(startLocal);
            e = parseLocal(endLocal);
            z = ZoneId.of(zone.trim());
        }
        catch (Exception ex)
        {
            return new long[] { authoredStart, authoredEnd };
        }

        // The authored duration, measured on the real timeline so a window spanning a DST change keeps its wall
        // clock length rather than gaining or losing the offset hour.
        long durationMillis = ZonedDateTime.of(e, z).toInstant().toEpochMilli()
                - ZonedDateTime.of(s, z).toInstant().toEpochMilli();
        if (durationMillis < 0)
            durationMillis = 0;

        int baseYear = Instant.ofEpochMilli(now).atZone(z).getYear();

        long bestFutureStart = Long.MAX_VALUE;
        long bestFutureEnd = 0;
        boolean haveFuture = false;
        long bestPastStart = Long.MIN_VALUE;
        long bestPastEnd = 0;
        boolean havePast = false;

        // A window may straddle a year boundary (Dec into Jan), so the occurrence in force at now can be anchored
        // in the previous, current or next year. Trying three years covers every case for a window under a year.
        // withYear pulls a Feb 29 authored start back to Feb 28 in a non leap year (see the class doc), so a leap
        // day event still resolves every year.
        for (int y = baseYear - 1; y <= baseYear + 1; y++)
        {
            long start = ZonedDateTime.of(s.withYear(y), z).toInstant().toEpochMilli();
            long end = start + durationMillis;
            if (now >= start && now < end)
                return new long[] { start, end };   // an occurrence is running now: it wins
            if (start > now && start < bestFutureStart)
            {
                bestFutureStart = start;
                bestFutureEnd = end;
                haveFuture = true;
            }
            if (end <= now && start > bestPastStart)
            {
                bestPastStart = start;
                bestPastEnd = end;
                havePast = true;
            }
        }
        if (haveFuture)
            return new long[] { bestFutureStart, bestFutureEnd };
        if (havePast)
            return new long[] { bestPastStart, bestPastEnd };
        return new long[] { authoredStart, authoredEnd };
    }

    // ---- yearly day ranges --------------------------------------------------------------------------------------

    /** The most days a month can have in any year (February counts as 29), for the day dropdown and validation. */
    public static int maxDaysInMonth(int month)
    {
        return month < 1 || month > 12 ? 0 : java.time.Month.of(month).maxLength();
    }

    /** True when month is 1..12 and day is 1..{@link #maxDaysInMonth(int)}. */
    public static boolean validMonthDay(int month, int day)
    {
        return month >= 1 && month <= 12 && day >= 1 && day <= maxDaysInMonth(month);
    }

    /** The month/day as a date in {@code year}, a February 29 becoming February 28 in a non leap year. */
    public static LocalDate dateIn(int year, int month, int day)
    {
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        return ym.atDay(Math.min(day, ym.lengthOfMonth()));
    }

    /** True when the range runs past December 31 into the next year: the end day comes before the start day. */
    public static boolean wraps(int startMonth, int startDay, int endMonth, int endDay)
    {
        return endMonth < startMonth || (endMonth == startMonth && endDay < startDay);
    }

    /**
     * How many days the range includes, both ends counted, measured on a non leap year (so a February 29 counts as
     * February 28). A single day is 1, Dec 20 to Jan 5 is 17. This is what {@link #MAX_WINDOW_DAYS} limits.
     */
    public static int rangeDays(int startMonth, int startDay, int endMonth, int endDay)
    {
        LocalDate s = dateIn(2025, startMonth, startDay);
        LocalDate e = dateIn(wraps(startMonth, startDay, endMonth, endDay) ? 2026 : 2025, endMonth, endDay);
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(s, e) + 1;
        return Math.max(1, days);
    }

    /** True when the range is too long to be an annual event (see the class doc). */
    public static boolean rangeTooLong(int startMonth, int startDay, int endMonth, int endDay)
    {
        return rangeDays(startMonth, startDay, endMonth, endDay) > MAX_WINDOW_DAYS;
    }

    /**
     * The occurrence of a day range that STARTS in {@code year}: {@code {start, end}} epoch millis, from 00:00 on the
     * start day to 00:00 on the day after the end day (in the next year when the range wraps).
     */
    public static long[] dayRangeOccurrence(int startMonth, int startDay, int endMonth, int endDay, ZoneId zone,
                                            int year)
    {
        LocalDate s = dateIn(year, startMonth, startDay);
        int endYear = wraps(startMonth, startDay, endMonth, endDay) ? year + 1 : year;
        LocalDate e = dateIn(endYear, endMonth, endDay);
        if (e.isBefore(s))
            e = s; // Feb 28 to Feb 29 in a non leap year collapses to the one day, never an inverted window
        long start = s.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = e.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new long[] { start, end };
    }

    /**
     * The yearly day range in force at {@code now}: the occurrence running now, else the soonest future one, else the
     * most recent past one (the same rule as {@link #resolveWindow}). Pure: {@code now} is the shared DB clock.
     *
     * @return {@code {startEpochMillis, endEpochMillis}}, half open
     */
    public static long[] dayRangeWindow(int startMonth, int startDay, int endMonth, int endDay, ZoneId zone, long now)
    {
        int baseYear = Instant.ofEpochMilli(now).atZone(zone).getYear();
        long[] future = null;
        long[] past = null;
        // A wrapping range that started last year can be the one running now, so try the previous year too.
        for (int y = baseYear - 1; y <= baseYear + 1; y++)
        {
            long[] w = dayRangeOccurrence(startMonth, startDay, endMonth, endDay, zone, y);
            if (now >= w[0] && now < w[1])
                return w;
            if (w[0] > now && (future == null || w[0] < future[0]))
                future = w;
            if (w[1] <= now && (past == null || w[0] > past[0]))
                past = w;
        }
        return future != null ? future : past;
    }

    /**
     * The window in force at {@code now} for a def's schedule, whichever way it was authored: a yearly day range
     * through {@link #dayRangeWindow}, else the older free-text window through {@link #resolveWindow}. Every runtime
     * caller (activeness, the next start, the network run key, remaining time) goes through this.
     */
    public static long[] window(EventDef.Schedule s, long now)
    {
        if (s.isDayRange() && validMonthDay(s.startMonth, s.startDay) && validMonthDay(s.endMonth, s.endDay))
            return dayRangeWindow(s.startMonth, s.startDay, s.endMonth, s.endDay, zoneOrUtc(s.zone), now);
        return resolveWindow(s.startEpochMillis, s.endEpochMillis, s.startLocal, s.endLocal, s.zone, s.annual, now);
    }

    /**
     * Server-side freeze for a day range: marks it annual and writes the occurrence in force at {@code now} into the
     * epochs and the local strings ("yyyy-MM-ddT00:00" of the start day and of the day after the end day). Those
     * fields are what an older jar and the DB projection read, and the strings resolve to the same yearly window
     * under the older annual rule. Anything else is handed to {@link #freeze(EventDef.Schedule)}.
     */
    public static void freeze(EventDef.Schedule s, long now)
    {
        if (s == null)
            return;
        if (!s.isDayRange())
        {
            freeze(s);
            return;
        }
        if (!validZone(s.zone) || !validMonthDay(s.startMonth, s.startDay) || !validMonthDay(s.endMonth, s.endDay))
            return;
        ZoneId z = ZoneId.of(s.zone.trim());
        long[] w = dayRangeWindow(s.startMonth, s.startDay, s.endMonth, s.endDay, z, now);
        s.annual = true;
        s.startEpochMillis = w[0];
        s.endEpochMillis = w[1];
        s.startLocal = Instant.ofEpochMilli(w[0]).atZone(z).toLocalDate().atStartOfDay().format(LOCAL);
        s.endLocal = Instant.ofEpochMilli(w[1]).atZone(z).toLocalDate().atStartOfDay().format(LOCAL);
    }

    /**
     * The month/day range an older def's local start and end describe, for migrating it into the dropdowns:
     * {@code {startMonth, startDay, endMonth, endDay}}, or null when the locals cannot be read. The start day is the
     * start's date. The end day is the last day the old window touched: an end at exactly 00:00 belongs to the day
     * before (the old window stopped as that day began), any later time keeps its own day. So "Oct 24 18:00 to
     * Nov 2 23:59" becomes Oct 24 to Nov 2, and "Dec 20 00:00 to Jan 6 00:00" becomes Dec 20 to Jan 5.
     */
    public static int[] legacyDayRange(String startLocal, String endLocal)
    {
        LocalDateTime s;
        LocalDateTime e;
        try
        {
            s = parseLocal(startLocal);
            e = parseLocal(endLocal);
        }
        catch (Exception ex)
        {
            return null;
        }
        LocalDate sd = s.toLocalDate();
        LocalDate ed = e.toLocalTime().equals(LocalTime.MIDNIGHT) ? e.toLocalDate().minusDays(1) : e.toLocalDate();
        if (ed.isBefore(sd))
            ed = sd;
        return new int[] { sd.getMonthValue(), sd.getDayOfMonth(), ed.getMonthValue(), ed.getDayOfMonth() };
    }

    /** Format epoch millis as the UTC DATETIME(3) string the bot reads (naive, treated as UTC by the reader). */
    public static String toDbUtc(long epochMillis)
    {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).format(DB_UTC);
    }
}
