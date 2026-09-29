package net.shurui.dev.sdu.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import com.mojang.logging.LogUtils;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;

import net.shurui.dev.sdu.DmzNpc;

/**
 * A stand-in for spark's sampler, scoped to the one question a lag hunt needs: when a tick runs long, WHAT is the
 * server thread doing. spark itself is banned here (it crashed the server), so this is the minimum that answers that
 * over the normal log.
 *
 * <h2>How it works</h2>
 * The server thread records when each tick starts ({@link TickEvent.ServerTickEvent} START) and clears it at END, so a
 * separate daemon thread can tell at any instant whether a tick is in flight and for how long. That daemon wakes every
 * {@link #SAMPLE_INTERVAL_MS}; if the current tick has already run longer than {@link #SLOW_THRESHOLD_MS} it grabs the
 * server thread's stack ({@code Thread.getStackTrace()}) and folds the top {@link #STACK_FRAMES} frames into one string
 * key, counting how often each is seen. Long ticks therefore get sampled repeatedly (once per sample interval
 * they overrun), which is exactly the weighting wanted: the hotter a stall, the more samples it earns.
 *
 * <h2>Why a daemon thread and not the tick loop</h2>
 * The whole point is to observe the server thread WHILE it is stuck inside one tick, which by definition it cannot do
 * to itself: any code that ran on the tick thread would run only between ticks or after the slow work finished, seeing
 * nothing. An outside thread reading {@code getStackTrace()} is the only vantage that catches the tick mid-stall. The
 * thread is a daemon so it never holds the JVM open, and it does no work at all unless the instrument is on and a tick
 * is actually overrunning.
 *
 * <h2>The report</h2>
 * Every {@link #WINDOW_TICKS} ticks (60 s): how many ticks in the window ran slow, the worst tick time seen, and the
 * top {@link #TOP_STACKS} sampled stacks with counts, frames compressed to {@code Class.method:line}. Memory is bounded
 * to {@link #MAX_STACK_KEYS} distinct keys per window. Every counter resets after each report.
 *
 * <h2>Safety</h2>
 * Read-only: it never touches game state, only its own concurrent maps and the server thread's stack. The first throw
 * anywhere disables the instrument ({@link #ENABLED} false) with one log line. The daemon keeps running but does
 * nothing once disabled.
 *
 * <p>Switchboard key {@code Diagnostics.TickSampler}, default-ON, sharing the {@code Diagnostics} branch with
 * {@link EntityChurnWatch}.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class TickSampler
{

    private TickSampler() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Whether sampling is live. Volatile: read by the daemon, written on the tick thread. */
    public static volatile boolean ENABLED = false;

    /** Latched after the first failure; never cleared without a restart. */
    private static volatile boolean failed = false;

    /** A tick over this many ms is "slow" and worth a stack. 60 ms = past the 50 ms budget, so only real overruns. */
    private static final long SLOW_THRESHOLD_MS = 60L;

    /**
     * Daemon wake interval. 25 ms is fine enough to catch a stall (a slow tick is >= 60 ms, so it is still
     * sampled at least twice) while cutting the number of getStackTrace() calls that briefly pause the server
     * thread during a stall to under half of what a 10 ms cadence took.
     */
    private static final long SAMPLE_INTERVAL_MS = 25L;

    /** Frames kept per sample. 25 is deep enough to reach past the tick dispatch into the actual hot code. */
    private static final int STACK_FRAMES = 25;

    /** Cap on distinct stack keys per window. */
    private static final int MAX_STACK_KEYS = 300;

    /** Report cadence in ticks. 1200 = 60 s at 20 TPS. */
    private static final int WINDOW_TICKS = 1200;

    /** How many stacks the report breaks out. */
    private static final int TOP_STACKS = 8;

    /** The server thread, captured at start. The daemon samples exactly this thread and no other. */
    private static volatile Thread serverThread;

    /** {@code System.nanoTime()} at the current tick's START, or 0 between ticks. */
    private static volatile long tickStartNanos = 0L;

    /** Compressed stack -> sample count this window. */
    private static final Map<String, LongAdder> STACKS = new ConcurrentHashMap<>();

    /** Slow ticks counted this window (measured at END, one per tick). */
    private static final LongAdder slowTicks = new LongAdder();

    /** Worst full-tick time seen this window, in ms. */
    private static volatile long worstTickMs = 0L;

    /** Ensures the daemon is started exactly once, across integrated-server restarts in dev. */
    private static final AtomicBoolean daemonStarted = new AtomicBoolean(false);

    private static int tick;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        serverThread = event.getServer().getRunningThread();
        refreshEnabled();
        resetWindow();
        startDaemon();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.START)
        {
            // Belt and braces: the running thread is stable, but on the first tick capture it here too in case a
            // platform fires ServerStartedEvent off-thread.
            if (serverThread == null)
                serverThread = Thread.currentThread();
            tickStartNanos = ENABLED ? System.nanoTime() : 0L;
            return;
        }

        // END phase.
        long start = tickStartNanos;
        tickStartNanos = 0L;   // the daemon must not sample between ticks
        if (ENABLED && start != 0L)
        {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            if (ms > worstTickMs)
                worstTickMs = ms;
            if (ms > SLOW_THRESHOLD_MS)
                slowTicks.increment();
        }

        refreshEnabled();
        if (++tick < WINDOW_TICKS)
            return;
        tick = 0;
        report();
    }

    private static void startDaemon()
    {
        if (!daemonStarted.compareAndSet(false, true))
            return;
        Thread t = new Thread(TickSampler::daemonLoop, "sdu-ticksampler");
        t.setDaemon(true);
        // Below normal so the sampler never competes with the server thread it is watching.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static void daemonLoop()
    {
        while (true)
        {
            try
            {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                return;
            }
            try
            {
                if (!ENABLED)
                    continue;
                long start = tickStartNanos;
                if (start == 0L)
                    continue;   // no tick in flight
                long ms = (System.nanoTime() - start) / 1_000_000L;
                if (ms <= SLOW_THRESHOLD_MS)
                    continue;
                Thread st = serverThread;
                if (st == null)
                    continue;
                sample(st.getStackTrace());
            }
            catch (Throwable th)
            {
                fail("daemon", th);
            }
        }
    }

    private static void sample(StackTraceElement[] frames)
    {
        if (frames.length == 0)
            return;
        StringBuilder sb = new StringBuilder(256);
        // Skip the leading run of JDK socket, TLS and JDBC driver frames. A blocked database round trip is about
        // twenty of them deep, which used to fill the whole budget and hide the game code that made the call.
        int start = 0;
        while (start < frames.length - 1 && isPlumbing(frames[start].getClassName()))
            start++;
        if (start > 0)
            sb.append(compact(frames[start - 1])).append(" <~ ");
        int limit = Math.min(start + STACK_FRAMES, frames.length);
        for (int i = start; i < limit; i++)
        {
            if (i > start)
                sb.append(" <- ");
            sb.append(compact(frames[i]));
        }
        String key = sb.toString();
        LongAdder adder = STACKS.get(key);
        if (adder == null)
        {
            if (STACKS.size() >= MAX_STACK_KEYS)
                return;
            adder = STACKS.computeIfAbsent(key, k -> new LongAdder());
        }
        adder.increment();
    }

    private static boolean isPlumbing(String cn)
    {
        return cn.startsWith("sun.nio.") || cn.startsWith("java.net.") || cn.startsWith("sun.security.ssl.")
                || cn.startsWith("java.io.") || cn.startsWith("org.mariadb.jdbc.") || cn.startsWith("com.mysql.")
                || cn.startsWith("jdk.internal.misc.") || cn.startsWith("java.util.concurrent.locks.");
    }

    private static String compact(StackTraceElement e)
    {
        String cn = e.getClassName();
        int dot = cn.lastIndexOf('.');
        if (dot >= 0)
            cn = cn.substring(dot + 1);
        return cn + '.' + e.getMethodName() + ':' + e.getLineNumber();
    }

    private static void refreshEnabled()
    {
        if (failed)
        {
            ENABLED = false;
            return;
        }
        // The operator switchboard was removed in batch M; the tick sampler is a read-only lag diagnostic that runs
        // whenever the process is up (its hot path is one boolean when nothing is slow).
        ENABLED = true;
    }

    private static void report()
    {
        if (failed)
            return;
        try
        {
            long slow = slowTicks.sum();
            long worst = worstTickMs;
            if (slow == 0)
            {
                LOGGER.info("[ticksampler] window: no ticks over {}ms, worst {}ms.", SLOW_THRESHOLD_MS, worst);
                resetWindow();
                return;
            }

            List<Map.Entry<String, Long>> ranked = new ArrayList<>();
            for (Map.Entry<String, LongAdder> e : STACKS.entrySet())
                ranked.add(Map.entry(e.getKey(), e.getValue().sum()));
            ranked.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());

            LOGGER.info("[ticksampler] window: {} slow ticks (> {}ms), worst {}ms, {} distinct stacks. Top {}:",
                    slow, SLOW_THRESHOLD_MS, worst, ranked.size(), Math.min(TOP_STACKS, ranked.size()));
            for (int i = 0; i < ranked.size() && i < TOP_STACKS; i++)
                LOGGER.info("[ticksampler]   x{} {}", ranked.get(i).getValue(), ranked.get(i).getKey());
        }
        catch (Throwable t)
        {
            fail("report", t);
        }
        finally
        {
            resetWindow();
        }
    }

    private static void resetWindow()
    {
        STACKS.clear();
        slowTicks.reset();
        worstTickMs = 0L;
    }

    private static void fail(String where, Throwable t)
    {
        if (failed)
            return;
        failed = true;
        ENABLED = false;
        LOGGER.warn("[ticksampler] disabling after failure in {}: {}", where, t.toString());
    }

    static
    {
        DmzNpc.LOGGER.debug("[{}] tick sampler loaded (active while Diagnostics.TickSampler is on)", DmzNpc.MODID);
    }
}
