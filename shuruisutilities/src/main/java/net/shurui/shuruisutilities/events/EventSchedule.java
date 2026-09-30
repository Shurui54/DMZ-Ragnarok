package net.shurui.shuruisutilities.events;

import java.time.Instant;
import java.time.LocalDateTime;
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

    /** Format epoch millis as the UTC DATETIME(3) string the bot reads (naive, treated as UTC by the reader). */
    public static String toDbUtc(long epochMillis)
    {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).format(DB_UTC);
    }
}
