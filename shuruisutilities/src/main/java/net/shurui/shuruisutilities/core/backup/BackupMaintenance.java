package net.shurui.shuruisutilities.core.backup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.io.FileUtils;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Clearing up the backups that are already on disk.
 *
 * <h2>Why a sweep is needed at all, given both rings prune themselves</h2>
 * Because each ring only prunes when it is WRITTEN. That was fine while a snapshot was taken on every boot, and
 * stopped being fine the moment snapshots became conditional on the data having changed: a server whose permissions
 * are stable now takes no snapshot, so the prune that used to run alongside it never fires either, and whatever pile
 * built up under the old every-boot behaviour would sit there forever. The race-bundle backups were always in that
 * position, since they are only pruned when THAT race is upgraded again, which for most races never happens.
 *
 * <p>So the sweep is separate from the writing, runs on every start, and answers only one question: is there more
 * here than the retention allows? It never creates a backup and never restores one.
 *
 * <h2>What it will not touch</h2>
 * The last-good copies ({@code SUData_backup} and {@code GroupB_backup}) are never candidates: they are what a
 * restore reaches for first, and there is exactly one of each. Only the timestamped rings are trimmed, and only down
 * to the retention count, newest kept. Every delete goes through {@link ShuruisUtilities#isSafeToDeleteBackup} so a
 * misresolved path can never turn this into a delete of the live data.
 */
public final class BackupMaintenance
{
    private BackupMaintenance() {}

    /** How many timestamped snapshots of each kind survive a sweep. Matches the rings' own retention. */
    public static final int KEEP = 5;

    /** What one sweep did, for the log line and for the command's report. */
    public record Result(int suDataRemoved, int groupBRemoved, int raceRemoved, int tempRemoved, long bytesFreed)
    {
        public int total()
        {
            return suDataRemoved + groupBRemoved + raceRemoved + tempRemoved;
        }
    }

    /**
     * Trim every backup location to its retention, and remove any half-written {@code .tmp} left by an interrupted
     * swap. Never throws: a cleanup problem must not stop a server from starting.
     */
    public static Result sweep(int keep)
    {
        int keepCount = Math.max(1, keep);
        long before = 0L;
        int suData = 0;
        int groupB = 0;
        int race = 0;
        int temp = 0;
        try
        {
            File suDir = ShuruisUtilities.getSUDirectory();
            File dataParent = ShuruisUtilities.getSUDataRoot().getParentFile();

            List<File> suDataSnaps = snapshots(dataParent, ShuruisUtilities.SUDATA_BACKUP_PREFIX);
            List<File> groupBSnaps = snapshots(suDir, ShuruisUtilities.GROUPB_BACKUP_PREFIX);

            before = sizeOf(excess(suDataSnaps, keepCount)) + sizeOf(excess(groupBSnaps, keepCount));

            suData = deleteAll(excess(suDataSnaps, keepCount));
            groupB = deleteAll(excess(groupBSnaps, keepCount));
            temp = deleteAll(staleTemp(dataParent, suDir));

            race = net.shurui.shuruisutilities.compat.dmz.RaceBundleCompat.pruneRaceBackups(keepCount);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[SUData] Backup sweep failed part way through: {}", t.toString());
        }
        return new Result(suData, groupB, race, temp, before);
    }

    /** How many timestamped snapshots of each kind are on disk right now, for the report. */
    public static int[] counts()
    {
        File suDir = ShuruisUtilities.getSUDirectory();
        File dataParent = ShuruisUtilities.getSUDataRoot().getParentFile();
        return new int[] {
                snapshots(dataParent, ShuruisUtilities.SUDATA_BACKUP_PREFIX).size(),
                snapshots(suDir, ShuruisUtilities.GROUPB_BACKUP_PREFIX).size(),
                net.shurui.shuruisutilities.compat.dmz.RaceBundleCompat.countRaceBackups(),
        };
    }

    /** Total bytes held by every timestamped snapshot of both kinds, for the report. */
    public static long totalSnapshotBytes()
    {
        File suDir = ShuruisUtilities.getSUDirectory();
        File dataParent = ShuruisUtilities.getSUDataRoot().getParentFile();
        return sizeOf(snapshots(dataParent, ShuruisUtilities.SUDATA_BACKUP_PREFIX))
                + sizeOf(snapshots(suDir, ShuruisUtilities.GROUPB_BACKUP_PREFIX));
    }

    // Every timestamped snapshot under parent, oldest first. The last-good copy does not match: its name has no
    // trailing underscore, and both prefixes end with one.
    private static List<File> snapshots(File parent, String prefix)
    {
        List<File> out = new ArrayList<>();
        if (parent == null)
            return out;
        File[] found = parent.listFiles((dir, name) -> name.startsWith(prefix));
        if (found == null)
            return out;
        // Epoch millis are fixed width for the next two centuries, so name order is age order.
        Arrays.sort(found, Comparator.comparing(File::getName));
        out.addAll(Arrays.asList(found));
        return out;
    }

    private static List<File> excess(List<File> snapshots, int keep)
    {
        if (snapshots.size() <= keep)
            return List.of();
        return new ArrayList<>(snapshots.subList(0, snapshots.size() - keep));
    }

    // Leftovers from an interrupted last-good swap. Safe to remove unconditionally: a .tmp is only ever a partial
    // copy that was about to be renamed, never something a restore would read.
    private static List<File> staleTemp(File dataParent, File suDir)
    {
        List<File> out = new ArrayList<>();
        for (File parent : new File[] { dataParent, suDir })
        {
            if (parent == null)
                continue;
            File[] found = parent.listFiles((dir, name) -> name.endsWith(".tmp")
                    && (name.startsWith(ShuruisUtilities.SUDATA_BACKUP_PREFIX)
                            || name.startsWith(ShuruisUtilities.GROUPB_BACKUP_PREFIX)
                            || name.startsWith("SUData_backup")
                            || name.startsWith("GroupB_backup")));
            if (found != null)
                out.addAll(Arrays.asList(found));
        }
        return out;
    }

    private static int deleteAll(List<File> targets)
    {
        int removed = 0;
        for (File target : targets)
        {
            if (!ShuruisUtilities.isSafeToDeleteBackup(target))
            {
                LoggingHandler.sulog.warn("[SUData] Refusing to sweep \"{}\": the path guard flagged it.", target);
                continue;
            }
            try
            {
                if (target.isDirectory())
                    FileUtils.deleteDirectory(target);
                else if (!target.delete())
                    continue;
                removed++;
            }
            catch (IOException ex)
            {
                LoggingHandler.sulog.warn("[SUData] Could not sweep \"{}\": {}", target, ex.getMessage());
            }
        }
        return removed;
    }

    private static long sizeOf(List<File> files)
    {
        long total = 0L;
        for (File f : files)
        {
            try
            {
                total += f.isDirectory() ? FileUtils.sizeOfDirectory(f) : f.length();
            }
            catch (Throwable ignored)
            {
                // an unreadable or vanishing directory contributes nothing rather than failing the whole report
            }
        }
        return total;
    }

    /** Bytes as a short human string, for chat and log lines. */
    public static String humanBytes(long bytes)
    {
        if (bytes < 1024L)
            return bytes + " B";
        if (bytes < 1024L * 1024L)
            return (bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L)
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
