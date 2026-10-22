package net.shurui.shuruisutilities.ocarina;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every ocarina number, held in core so the effects (core registry content) and the skill prices injected into
 * DragonMineZ's skills config can read them. The Ragnarok Key's {@code Ocarina.toml} bakes into this
 * ({@code OcarinaConfig}); keyless nothing reads it, so the defaults below are only ever a fallback.
 *
 * <p>Each song scales linearly from its level 1 value to its max level value (max level = the number of prices).
 * What "power" and "secondary" mean depends on the song, see {@link Song}.
 */
public final class OcarinaTuning
{
    private OcarinaTuning() {}

    /**
     * One song's numbers.
     *
     * <ul>
     *   <li>MENDING: power = % of max health healed, secondary = % of max ki and stamina restored.</li>
     *   <li>VALOUR: power = % added to STR/SKP/PWR/DEF; allies join from {@code allyLevel}.</li>
     *   <li>DISCORD: power = % taken off STR/SKP/PWR/DEF, secondary = % movement slow.</li>
     *   <li>SEAL: power unused, duration = how long targets are bound.</li>
     *   <li>LULLABY: duration = how long creatures stay calm.</li>
     *   <li>RESONANCE: power = % of max ki restored per second, secondary = % of max stamina per second.</li>
     * </ul>
     */
    public record Song(List<Integer> costs, double cooldownSeconds,
                       double radiusMin, double radiusMax,
                       double powerMin, double powerMax,
                       double secondaryMin, double secondaryMax,
                       double durationMin, double durationMax,
                       int allyLevel)
    {
        public int maxLevel()
        {
            return Math.max(1, costs.size());
        }

        /** 0 at level 1, 1 at max level. */
        public double t(int level)
        {
            int max = maxLevel();
            if (max <= 1)
                return 1.0D;
            return Math.max(0.0D, Math.min(1.0D, (level - 1) / (double) (max - 1)));
        }

        public double radius(int level)
        {
            return radiusMin + (radiusMax - radiusMin) * t(level);
        }

        public double power(int level)
        {
            return powerMin + (powerMax - powerMin) * t(level);
        }

        public double secondary(int level)
        {
            return secondaryMin + (secondaryMax - secondaryMin) * t(level);
        }

        public double duration(int level)
        {
            return durationMin + (durationMax - durationMin) * t(level);
        }
    }

    private static final List<Integer> COSTS = List.of(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000);
    private static final List<Integer> COSTS_RARE =
            List.of(1500, 2500, 3500, 4500, 5500, 6500, 7500, 8500, 9500, 10500);

    /** The shipped defaults, the same numbers {@code Ocarina.toml} starts from. */
    public static Map<OcarinaSong, Song> defaults()
    {
        Map<OcarinaSong, Song> m = new EnumMap<>(OcarinaSong.class);
        m.put(OcarinaSong.MENDING, new Song(COSTS, 30, 6, 16, 10, 35, 5, 20, 0, 0, 1));
        m.put(OcarinaSong.VALOUR, new Song(COSTS, 60, 8, 16, 5, 20, 0, 0, 30, 75, 5));
        m.put(OcarinaSong.DISCORD, new Song(COSTS, 45, 6, 12, 2.5, 10, 10, 25, 10, 20, 1));
        m.put(OcarinaSong.SEAL, new Song(COSTS_RARE, 60, 4, 8, 0, 0, 0, 0, 1.5, 4, 1));
        m.put(OcarinaSong.LULLABY, new Song(COSTS, 60, 8, 20, 0, 0, 0, 0, 5, 15, 1));
        m.put(OcarinaSong.RESONANCE, new Song(COSTS_RARE, 60, 6, 14, 1, 3, 1, 3, 8, 16, 1));
        return m;
    }

    private static volatile Map<OcarinaSong, Song> songs = defaults();

    /** Below this score (0..100) a song falls apart and does nothing but start the cooldown. */
    private static volatile double minScorePercent = 40.0D;

    /** Creatures with more max health than this shrug off the seal and the lullaby (bosses). 0 = nobody is immune. */
    private static volatile double bossHealthImmunity = 2000.0D;

    /** What Tapion teaches besides the songs: plain DragonMineZ skill ids, his entry in skillOfferings. */
    private static volatile List<String> tapionExtraSkills = List.of("kicontrol", "kisense", "meditation",
            "kiprotection", "sprint", "ki_barrage", "beam_sword_slash", "dimensional_sword_attack");

    public static Song song(OcarinaSong s)
    {
        Song v = songs.get(s);
        return v != null ? v : defaults().get(s);
    }

    public static double minScorePercent()
    {
        return minScorePercent;
    }

    public static double bossHealthImmunity()
    {
        return bossHealthImmunity;
    }

    public static List<String> tapionExtraSkills()
    {
        return tapionExtraSkills;
    }

    /** Replace everything at once (the key's config bake). */
    public static void set(Map<OcarinaSong, Song> newSongs, double minScore, double bossHealth, List<String> extras)
    {
        Map<OcarinaSong, Song> m = new EnumMap<>(OcarinaSong.class);
        m.putAll(defaults());
        if (newSongs != null)
            m.putAll(newSongs);
        songs = m;
        minScorePercent = minScore;
        bossHealthImmunity = bossHealth;
        tapionExtraSkills = extras == null ? List.of() : List.copyOf(extras);
    }
}
