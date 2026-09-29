package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for extracting SU's bundled races into DMZ's config tree. This class holds no DMZ imports: it only
 * checks that DMZ is present before touching {@link RaceBundleExtractor}, which does. Follows the optional-dependency pattern.
 *
 * <p>SU ships several race folders (base shadow_dragon, its six star sub-races, half_saiyan) inside the jar and copies
 * them into {@code config/dragonminez/races/} at startup. Extraction is version-aware: an absent folder is written, a
 * stale-but-unedited folder is upgraded (with a backup), and an edited or pre-versioning folder is left untouched and
 * surfaced through a log line plus the {@code /rgrace refresh} command.
 */
public final class RaceBundleCompat
{
    private RaceBundleCompat() {}

    /**
     * DMZ-free result of one race in a forced refresh, so the command can render translatable chat without importing
     * any DMZ type. {@code outcome} is one of {@link #WROTE_FRESH}, {@link #ALREADY_CURRENT}, {@link #UPGRADED},
     * {@link #FAILED}. {@code fromVersion} is the version that was on disk before (or -1 if unknown).
     */
    public static final class RaceRefreshLine
    {
        public static final String WROTE_FRESH = "wrote_fresh";
        public static final String ALREADY_CURRENT = "already_current";
        public static final String UPGRADED = "upgraded";
        public static final String FAILED = "failed";

        public final String raceId;
        public final String outcome;
        public final int fromVersion;
        public final int toVersion;

        RaceRefreshLine(String raceId, String outcome, int fromVersion, int toVersion)
        {
            this.raceId = raceId;
            this.outcome = outcome;
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
        }
    }

    /**
     * Extract any bundled race folders from SU's jar into {@code config/dragonminez/races/} and version-check the ones
     * already there, then ask DMZ to reload so the races become selectable. Gated ONLY on DMZ being present: the race
     * FILES are always installed, independent of the wish-tracking master switch. The switch governs whether the
     * cinematic unlock PATH is live (whether players can EARN the race), not whether the race data exists, so the races
     * are laid down on every DMZ server and stay fail-closed locked until earned (the server commit gates enforce that
     * regardless of the switch, see MixinDmzCreateCharacterRaceGate and MixinDmzRacePrestigeGate). Extraction is
     * idempotent (writes only if absent, upgrades only unedited folders, backs up first), so a server that never
     * touches the feature just carries the padlocked races and nothing more. Server-side only, no-op when DMZ is
     * absent. Never throws.
     */
    public static void extractOnServerStart()
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return;
        RaceBundleExtractor.extractAndReload();
    }

    /**
     * Trim every race's upgrade backups down to {@code keep}, newest kept per race.
     *
     * <p>Separate from the extraction because the extractor only prunes a race it is CURRENTLY backing up, which
     * happens when that race is upgraded. A race that is never upgraded again keeps its backups forever, so the
     * boot-time maintenance sweep calls this instead. Returns how many folders went; 0 without DMZ, where no race
     * backup was ever written.
     */
    public static int pruneRaceBackups(int keep)
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return 0;
        return RaceBundleExtractor.pruneAllBackups(keep);
    }

    /** How many race backup folders are on disk, for the maintenance report. */
    public static int countRaceBackups()
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return 0;
        return RaceBundleExtractor.countBackups();
    }

    /**
     * True when the forced-refresh command should be available. Gated ONLY on DMZ being present, matching
     * {@link #extractOnServerStart()}: the race files are always installed, so an admin must always be able to
     * force-repair them with {@code /rgrace refresh}, independent of the wish-tracking master switch. The refresh only
     * rewrites the same bundled files (with a backup first), so it carries no unlock leak.
     */
    public static boolean isRefreshAvailable()
    {
        return ModList.get().isLoaded("dragonminez");
    }

    /** True when {@code raceId} is one of the bundled race ids, without classloading DMZ. */
    public static boolean isBundledRace(String raceId)
    {
        return RaceBundleExtractor.isBundledRace(raceId);
    }

    /** The bundled race ids, for command suggestions. Always available (the race files are always installed). */
    public static List<String> bundledRaceIds()
    {
        return new ArrayList<>(RaceBundleExtractor.bundledRaceIds());
    }

    /**
     * Force-apply the bundle to one race id, or to every bundled race when {@code raceId} is null or "all". Backs up
     * before overwriting (even over local edits), reloads DMZ once if anything changed, and returns one DMZ-free line
     * per race. Never throws; returns an empty list when the feature is off or DMZ is absent.
     */
    public static List<RaceRefreshLine> forceRefresh(String raceId)
    {
        List<RaceRefreshLine> out = new ArrayList<>();
        if (!isRefreshAvailable())
            return out;
        // A forced refresh is the deliberate "put it back" action, so it drops every deletion tombstone first.
        // Otherwise an admin who deleted a race would run the refresh command, see nothing happen, and have no way
        // to find out why short of being told about a state file.
        BundleTombstones.clearTombstones();
        for (RaceBundleExtractor.RefreshResult r : RaceBundleExtractor.forceRefresh(raceId))
        {
            String outcome;
            switch (r.outcome)
            {
                case WROTE_FRESH:     outcome = RaceRefreshLine.WROTE_FRESH; break;
                case ALREADY_CURRENT: outcome = RaceRefreshLine.ALREADY_CURRENT; break;
                case UPGRADED:        outcome = RaceRefreshLine.UPGRADED; break;
                default:              outcome = RaceRefreshLine.FAILED; break;
            }
            out.add(new RaceRefreshLine(r.raceId, outcome, r.fromVersion, RaceBundleExtractor.BUNDLE_VERSION));
        }
        return out;
    }
}
