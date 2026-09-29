package net.shurui.dev.shuruis_raid_bosses.raid;

import net.minecraft.server.MinecraftServer;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Per-raid automatic scheduling. Sign-ups open once when a def's schedule reaches its moment. The per-def
 * last-run timestamp de-duplicates so a slot fires once even though this is polled every tick. Cadence
 * comes from the day fields (0 = ignore).
 */
public final class Scheduler {
    private Scheduler() {}

    private static final long FIRE_WINDOW_MS = 12 * 60 * 60 * 1000L;

    /**
     * Parsed {@code scheduleTimes} per def, so the per-second schedule poll does not re-parse the HH:MM
     * strings every time. Keyed weakly on the def instance: an edit, reload or cross-server sync replaces the
     * def object (see {@code RaidBossDef.load} / {@code RaidData.putDef}), so the stale entry is simply never
     * hit again and is collected. Server-thread only ({@link #mostRecentSlot} runs from the manager tick).
     */
    private static final java.util.Map<RaidBossDef, int[][]> PARSED_TIMES = new java.util.WeakHashMap<>();

    /** Parsed, cache-backed HH:MM list for a def ({hour, minute} pairs), malformed entries dropped. */
    private static int[][] parsedTimes(RaidBossDef def) {
        int[][] cached = PARSED_TIMES.get(def);
        if (cached != null) return cached;
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (def.scheduleTimes != null) {
            for (String t : def.scheduleTimes) {
                int[] hm = parseHhMm(t);
                if (hm != null) out.add(hm);
            }
        }
        int[][] arr = out.toArray(new int[0][]);
        PARSED_TIMES.put(def, arr);
        return arr;
    }

    public static void tickSchedule(RaidManager manager, MinecraftServer server, String defId) {
        RaidBossDef def = RaidData.get(server).getDef(defId);
        if (def == null || !def.scheduleEnabled) return;
        // An eventOnly raid never fires on its own timer; it runs only while its timed event lists it. Keyless
        // the hook's default is false, so an eventOnly raid never schedules without the key.
        if (def.eventOnly && !net.shurui.dev.sdu.api.key.EventHooks.get().raidRunnable(def.id)) return;

        LocalDateTime slot = mostRecentSlot(LocalDateTime.now(), def);
        if (slot == null) return;

        long slotMillis = slot.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long nowMillis = System.currentTimeMillis();
        RaidData data = RaidData.get(server);

        // cap the window to one interval so a stale sub-day slot doesn't fire on server start hours later
        long window = def.scheduleIntervalMinutes > 0
                ? Math.min(FIRE_WINDOW_MS, def.scheduleIntervalMinutes * 60_000L)
                : FIRE_WINDOW_MS;

        if (slotMillis > data.getLastScheduledRun(defId) && (nowMillis - slotMillis) <= window) {
            // on a shard network the slot goes to an election (one open world wins); on a single server it
            // opens locally. Returns true when spent, so stamp the per-server clock then.
            if (RaidNetwork.handleScheduledOpen(manager, server, defId, def.signupMinutes, slotMillis)) {
                data.setLastScheduledRun(defId, slotMillis);
            }
        }
    }

    /** epoch millis of the NEXT sign-up opening, or -1 if auto-schedule is off */
    public static long nextRunMillis(RaidBossDef def) {
        if (def == null || !def.scheduleEnabled) return -1;
        LocalDateTime next = nextSlot(LocalDateTime.now(), def);
        return next == null ? -1 : next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static LocalDateTime nextSlot(LocalDateTime now, RaidBossDef def) {
        int hour = Math.max(0, Math.min(23, def.scheduleHour));
        int minute = Math.max(0, Math.min(59, def.scheduleMinute));

        if (def.scheduleDayOfMonth > 0) {
            int dom = Math.min(def.scheduleDayOfMonth, now.toLocalDate().lengthOfMonth());
            LocalDateTime slot = now.toLocalDate().withDayOfMonth(dom).atTime(hour, minute);
            if (!slot.isAfter(now)) {
                LocalDate nm = now.toLocalDate().plusMonths(1);
                slot = nm.withDayOfMonth(Math.min(def.scheduleDayOfMonth, nm.lengthOfMonth())).atTime(hour, minute);
            }
            return slot;
        }
        if (def.scheduleDayOfWeek > 0) {
            DayOfWeek target = DayOfWeek.of(Math.min(7, def.scheduleDayOfWeek));
            LocalDate date = now.toLocalDate();
            while (date.getDayOfWeek() != target) date = date.plusDays(1);
            LocalDateTime slot = date.atTime(hour, minute);
            if (!slot.isAfter(now)) slot = slot.plusWeeks(1);
            return slot;
        }
        LocalDateTime today = now.toLocalDate().atTime(hour, minute);
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    /**
     * The most-recent past slot across all sources: the {@code scheduleTimes} list (or the legacy single
     * {@code scheduleHour}/{@code scheduleMinute} when empty), plus repeat-interval boundaries. Each
     * candidate is the latest occurrence &le; {@code now} on a cadence-matching day (weekly/monthly gates
     * preserved). Returns the MAXIMUM, or null.
     */
    private static LocalDateTime mostRecentSlot(LocalDateTime now, RaidBossDef def) {
        LocalDateTime best = null;

        // explicit HH:MM list, or the legacy single time when empty. Branch on the raw list (not the parsed
        // one) so a non-empty list of only-malformed times still suppresses the legacy fallback, as before.
        if (def.scheduleTimes != null && !def.scheduleTimes.isEmpty()) {
            for (int[] hm : parsedTimes(def)) {
                best = later(best, mostRecentTimeOfDaySlot(now, def, hm[0], hm[1]));
            }
        } else {
            int hour = Math.max(0, Math.min(23, def.scheduleHour));
            int minute = Math.max(0, Math.min(59, def.scheduleMinute));
            best = later(best, mostRecentTimeOfDaySlot(now, def, hour, minute));
        }

        // repeat-every-N-minutes boundaries anchored at local midnight, on cadence-matching days
        if (def.scheduleIntervalMinutes > 0) {
            best = later(best, mostRecentIntervalSlot(now, def, def.scheduleIntervalMinutes));
        }

        return best;
    }

    /** latest {@code hour:minute} occurrence &le; {@code now} on a cadence-matching day */
    private static LocalDateTime mostRecentTimeOfDaySlot(LocalDateTime now, RaidBossDef def, int hour, int minute) {
        hour = Math.max(0, Math.min(23, hour));
        minute = Math.max(0, Math.min(59, minute));

        if (def.scheduleDayOfMonth > 0) {
            int dom = Math.min(def.scheduleDayOfMonth, now.toLocalDate().lengthOfMonth());
            LocalDateTime slot = now.toLocalDate().withDayOfMonth(dom).atTime(hour, minute);
            if (slot.isAfter(now)) {
                LocalDate prev = now.toLocalDate().minusMonths(1);
                slot = prev.withDayOfMonth(Math.min(def.scheduleDayOfMonth, prev.lengthOfMonth())).atTime(hour, minute);
            }
            return slot;
        }
        if (def.scheduleDayOfWeek > 0) {
            DayOfWeek target = DayOfWeek.of(Math.min(7, def.scheduleDayOfWeek));
            LocalDate date = now.toLocalDate();
            while (date.getDayOfWeek() != target) date = date.minusDays(1);
            LocalDateTime slot = date.atTime(hour, minute);
            if (slot.isAfter(now)) slot = slot.minusWeeks(1);
            return slot;
        }
        LocalDateTime today = now.toLocalDate().atTime(hour, minute);
        return now.isBefore(today) ? today.minusDays(1) : today;
    }

    /**
     * Latest interval boundary &le; {@code now} (00:00, +interval, ... within a day) on the most recent
     * cadence-matching day. Weekly/monthly gates limit which days interval firing is allowed on.
     */
    private static LocalDateTime mostRecentIntervalSlot(LocalDateTime now, RaidBossDef def, int intervalMinutes) {
        int interval = Math.max(1, intervalMinutes);
        LocalDate day = mostRecentCadenceDay(now.toLocalDate(), def);
        if (day == null) return null;

        // today: only boundaries already reached; an earlier cadence day: the last of that day
        int limitMinutes = day.isEqual(now.toLocalDate())
                ? now.getHour() * 60 + now.getMinute()
                : 23 * 60 + 59;
        if (limitMinutes < 0) return null;
        int boundaryMinutes = (limitMinutes / interval) * interval;
        return day.atStartOfDay().plusMinutes(boundaryMinutes);
    }

    /** The most recent date &le; {@code from} that satisfies the monthly/weekly cadence (or {@code from} when daily). */
    private static LocalDate mostRecentCadenceDay(LocalDate from, RaidBossDef def) {
        if (def.scheduleDayOfMonth > 0) {
            LocalDate d = from;
            for (int i = 0; i < 62; i++) { // bounded lookback (~2 months) for safety
                if (Math.min(def.scheduleDayOfMonth, d.lengthOfMonth()) == d.getDayOfMonth()) return d;
                d = d.minusDays(1);
            }
            return null;
        }
        if (def.scheduleDayOfWeek > 0) {
            DayOfWeek target = DayOfWeek.of(Math.min(7, def.scheduleDayOfWeek));
            LocalDate d = from;
            while (d.getDayOfWeek() != target) d = d.minusDays(1);
            return d;
        }
        return from; // daily
    }

    /** Parse "H:MM"/"HH:MM" in 00:00-23:59 into {hour, minute}, or null if malformed/out of range. */
    private static int[] parseHhMm(String s) {
        if (s == null) return null;
        String v = s.trim();
        int colon = v.indexOf(':');
        if (colon <= 0 || colon >= v.length() - 1) return null;
        try {
            int h = Integer.parseInt(v.substring(0, colon).trim());
            int m = Integer.parseInt(v.substring(colon + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The later of two nullable slots (nulls ignored). */
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }
}
