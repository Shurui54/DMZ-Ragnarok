package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import net.minecraft.server.MinecraftServer;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

// per-tournament auto-scheduling. at the configured DAILY/WEEKLY/MONTHLY moment, sign-ups open once.
// per-def last-run timestamp de-dupes so a slot only fires once even though we poll every tick.
public final class Scheduler {
    private Scheduler() {}

    private static final long FIRE_WINDOW_MS = 12 * 60 * 60 * 1000L;

    public static void tickSchedule(TournamentManager manager, MinecraftServer server, String defId) {
        TournamentDef def = TournamentData.get(server).getDef(defId);
        if (def == null || !def.scheduleEnabled) return;

        LocalDateTime slot = mostRecentSlot(LocalDateTime.now(), def);
        if (slot == null) return;

        long slotMillis = slot.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long nowMillis = System.currentTimeMillis();
        TournamentData data = TournamentData.get(server);

        if (slotMillis > data.getLastScheduledRun(defId) && (nowMillis - slotMillis) <= FIRE_WINDOW_MS) {
            // On a shard network, a network-wide election picks exactly one open world to open it; on a single
            // server it just opens locally. True means the slot is spent.
            if (TournamentNetwork.handleScheduledOpen(manager, server, defId, def.signupMinutes, slotMillis)) {
                data.setLastScheduledRun(defId, slotMillis);
            }
        }
    }

    // epoch millis of the next scheduled opening, -1 if auto-schedule is off
    public static long nextRunMillis(TournamentDef def) {
        if (def == null || !def.scheduleEnabled) return -1;
        LocalDateTime next = nextSlot(LocalDateTime.now(), def);
        return next == null ? -1 : next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // soonest slot strictly after now, same 0=ignore cadence rules
    private static LocalDateTime nextSlot(LocalDateTime now, TournamentDef def) {
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

    // cadence comes from the day fields (0 = ignore): nonzero day-of-month -> monthly, else nonzero
    // day-of-week -> weekly, else daily
    private static LocalDateTime mostRecentSlot(LocalDateTime now, TournamentDef def) {
        int hour = Math.max(0, Math.min(23, def.scheduleHour));
        int minute = Math.max(0, Math.min(59, def.scheduleMinute));

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
}
