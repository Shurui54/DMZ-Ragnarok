package net.shurui.shuruisutilities.space;

/**
 * The single deterministic weather model for every generated planet's surface, shared by the SERVER (light gameplay
 * effects) and the CLIENT (precipitation, sky darkening, fog and sound). All planets share the one shared surface
 * dimension ({@link SurfaceDimension}) and vanilla weather is per dimension, so vanilla weather cannot vary per planet.
 * This builds our own: a weather STATE per planet that changes over time, chosen from the planet's surface
 * {@link SurfaceStamp.Theme}.
 *
 * <h3>Determinism (the whole point)</h3>
 * The state at any instant is a PURE FUNCTION of (planet key, theme, epoch millis). The epoch is the shard-corrected,
 * client-synced wall clock from {@link OrbitClock#epochMillis()}, exactly the clock the orbit work uses, so every shard
 * and every client computes the SAME weather for the same planet at the same real instant with nothing stored and
 * nothing streamed per tick. The only thing a client must be told is which planet it stands on and its theme, which the
 * existing {@link PacketSurfaceSky} already carries (no new packet, no id change).
 *
 * <h3>The timeline</h3>
 * Time is cut into fixed one-hour BLOCKS. Within a block a deterministic sequence of weather EPISODES tiles the hour:
 * each episode has a random duration (3..10 minutes) and a state weighted-picked from the theme's table, seeded from
 * (planet key, block index). A block re-seeds afresh, so the walk to find the current episode is bounded (about 20
 * episodes per hour) and O(1)-ish. Episode intensity ramps up over the first {@link #FADE_MS} and back down over the
 * last {@link #FADE_MS}, so precipitation eases in and out rather than snapping.
 *
 * <p>Nothing here touches world data: the surface dimension stays {@code fixed_time} with no rain flag, and this is a
 * client visual plus a couple of cheap, optional, configurable server effects (see {@link PlanetWeatherEffects}).
 */
public final class PlanetWeather
{
    private PlanetWeather()
    {
    }

    /**
     * A weather state. The set is theme-driven (see {@link #pick}); the client maps each to its own precipitation,
     * particles, fog, sky darkening and sound ({@code SurfaceWeatherClient}). ACID rain was left out on purpose (it is
     * the owner's optional item and adds a gameplay hazard the public tier does not want by default).
     */
    public enum State
    {
        CLEAR,
        RAIN,
        STORM,      // rain plus lightning flashes and thunder
        SNOW,
        BLIZZARD,   // heavy snow plus wind and a movement drag
        DUST,        // sandstorm / dust haze on barren worlds
        ASH,         // ash fall / embers on nether-like worlds
        METEOR       // a meteor shower streaking across the sky
    }

    /** An immutable weather reading for one instant: the state, its 0..1 intensity, and how long until it changes. */
    public static final class Snapshot
    {
        public final State state;
        public final float intensity;      // 0..1, ramped at the episode edges
        public final long msUntilChange;   // milliseconds until the next episode begins

        Snapshot(State state, float intensity, long msUntilChange)
        {
            this.state = state;
            this.intensity = intensity;
            this.msUntilChange = msUntilChange;
        }
    }

    // one weather block: a fixed hour, re-seeded per (key, block). A shorter block would reseed (and so risk a visible
    // state change) more often; an hour keeps the walk short while letting several episodes play out inside it.
    private static final long BLOCK_MS = 3_600_000L;
    private static final long EPISODE_MIN_MS = 180_000L;   // 3 minutes
    private static final long EPISODE_MAX_MS = 600_000L;   // 10 minutes
    // intensity ramp at each end of an episode, so precipitation fades in and out instead of snapping on.
    static final long FADE_MS = 20_000L;
    // a safety cap on the episode walk (an hour of >=3-minute episodes is at most 20, so 64 can never be reached).
    private static final int MAX_EPISODES = 64;

    /** The weather on {@code planetKey} of {@code theme} at {@code epochMillis}. Never null; a null key/theme is CLEAR. */
    public static Snapshot at(String planetKey, SurfaceStamp.Theme theme, long epochMillis)
    {
        if (planetKey == null || planetKey.isEmpty() || theme == null)
        {
            return new Snapshot(State.CLEAR, 0.0F, BLOCK_MS);
        }
        long block = Math.floorDiv(epochMillis, BLOCK_MS);
        long offset = epochMillis - block * BLOCK_MS;
        boolean cold = isCold(planetKey);

        long state = mix(seed(planetKey), block);   // walk RNG state, threaded by hand for JVM-stable determinism
        long cursor = 0L;
        for (int i = 0; i < MAX_EPISODES; ++i)
        {
            state = step(state);
            long dur = EPISODE_MIN_MS + (long) (unit(state) * (EPISODE_MAX_MS - EPISODE_MIN_MS));
            state = step(state);
            State s = pick(theme, cold, unit(state));
            long end = cursor + dur;
            if (offset < end || i == MAX_EPISODES - 1)
            {
                long into = offset - cursor;
                long remain = end - offset;
                float intensity = s == State.CLEAR ? 0.0F : ramp(into, remain, dur);
                return new Snapshot(s, intensity, Math.max(0L, remain));
            }
            cursor = end;
        }
        return new Snapshot(State.CLEAR, 0.0F, BLOCK_MS);
    }

    // 0..1 intensity: rises over the first FADE_MS, holds at 1, falls over the last FADE_MS. A very short episode (never
    // below EPISODE_MIN_MS, well above 2*FADE_MS) still peaks cleanly.
    private static float ramp(long into, long remain, long dur)
    {
        float up = Math.min(1.0F, into / (float) FADE_MS);
        float down = Math.min(1.0F, remain / (float) FADE_MS);
        return Math.max(0.0F, Math.min(up, down));
    }

    /**
     * Deterministic lightning flash brightness (0..1) at {@code epochMillis} on {@code planetKey}, meaningful only while
     * the state is {@link State#STORM}. Flashes fall on fixed 5-second slots; a slot flashes with a per-key/per-slot
     * chance and, when it does, the flash punches to full and decays over ~700 ms. Pure function, so a flash lights every
     * client's sky at the same instant and the server can fire thunder on the same schedule.
     */
    public static float lightning(String planetKey, long epochMillis)
    {
        if (planetKey == null || planetKey.isEmpty())
        {
            return 0.0F;
        }
        long slotMs = 5_000L;
        long slot = Math.floorDiv(epochMillis, slotMs);
        long h = step(mix(seed(planetKey) ^ 0x5F3759DFL, slot));
        if (unit(h) >= 0.55D)
        {
            return 0.0F;   // this slot has no strike
        }
        long strikeAt = slot * slotMs + (long) (unit(step(h)) * (slotMs - 800L));
        long dt = epochMillis - strikeAt;
        if (dt < 0L || dt > 700L)
        {
            return 0.0F;
        }
        return 1.0F - dt / 700.0F;
    }

    // WEATHER TABLES. Weighted state pick per theme. A "cold" world (per-key, roughly a third of planets) swaps its
    // rain/storm for snow/blizzard, which is how snow and blizzard reach a planet at all: the surface Theme set has no
    // dedicated frozen theme, so a cold classification of the temperate/barren themes is what gives frozen worlds.
    // KAIO is mostly clear on purpose (King Kai's calm sky). NETHER falls ash, barren STONY/END get dust and meteors.
    private static State pick(SurfaceStamp.Theme theme, boolean cold, double u)
    {
        switch (theme)
        {
            case OVERWORLD:
                return cold ? weighted(u, State.CLEAR, 55, State.SNOW, 30, State.BLIZZARD, 15)
                            : weighted(u, State.CLEAR, 55, State.RAIN, 30, State.STORM, 15);
            case NAMEK:
                return weighted(u, State.CLEAR, 50, State.RAIN, 33, State.STORM, 17);
            case KAIO:
                return weighted(u, State.CLEAR, 85, State.RAIN, 15);
            case STONY:
                return cold ? weighted(u, State.CLEAR, 55, State.SNOW, 30, State.BLIZZARD, 15)
                            : weighted(u, State.CLEAR, 55, State.DUST, 30, State.METEOR, 15);
            case NETHER:
                return weighted(u, State.CLEAR, 55, State.ASH, 45);
            case END:
                return weighted(u, State.CLEAR, 65, State.METEOR, 35);
            case OTHERWORLD:
                return weighted(u, State.CLEAR, 70, State.RAIN, 30);
            default:
                return State.CLEAR;
        }
    }

    // pick a state from a flat (state, weight, state, weight, ...) bag by a uniform 0..1 draw.
    private static State weighted(double u, Object... bag)
    {
        int total = 0;
        for (int i = 1; i < bag.length; i += 2)
        {
            total += (Integer) bag[i];
        }
        int target = (int) (u * total);
        int acc = 0;
        for (int i = 0; i < bag.length; i += 2)
        {
            acc += (Integer) bag[i + 1];
            if (target < acc)
            {
                return (State) bag[i];
            }
        }
        return (State) bag[0];
    }

    // a planet is "cold" (frozen climate) if its own salted hash lands in the bottom third. Pure function of the key.
    private static boolean isCold(String planetKey)
    {
        return Math.floorMod(hashSalted(planetKey, "climate"), 3) == 0;
    }

    // ==== JVM-stable deterministic PRNG (splitmix64), threaded by hand so client and server never disagree ====

    private static long seed(String planetKey)
    {
        return hashSalted(planetKey, "weather") * 0x9E3779B97F4A7C15L;
    }

    private static long mix(long a, long b)
    {
        long z = a ^ (b * 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long step(long state)
    {
        long z = state + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // a state's top 53 bits as a uniform double in [0,1).
    private static double unit(long state)
    {
        return (state >>> 11) * 0x1.0p-53;
    }

    private static long hashSalted(String key, String salt)
    {
        long h = 1125899906842597L;   // a large prime
        String s = key + '#' + salt;
        for (int i = 0; i < s.length(); ++i)
        {
            h = 31L * h + s.charAt(i);
        }
        return h;
    }
}
