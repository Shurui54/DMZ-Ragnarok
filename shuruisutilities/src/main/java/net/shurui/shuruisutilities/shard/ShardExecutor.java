package net.shurui.shuruisutilities.shard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The threads every vault call runs on.
 *
 * <p>Two kinds of work live here and they must not be confused:
 *
 * <ul>
 *   <li><b>Global work</b> (boot imports, chat and announce publishing, region and event polls) has no single
 *       player it belongs to and no per-player ordering to protect. It runs on the one {@link #get()} thread, the
 *       same single thread this class has always had.</li>
 *   <li><b>Per-player work</b> (a claim, a save, a release, an abandon, a presence depart) MUST stay ordered for
 *       that one player: a save queued before a release can never be allowed to overtake it, or the release writes
 *       first and the save lands on a row somebody else already took, duplicating or losing a character. It runs
 *       on {@link #forPlayer(UUID)}.</li>
 * </ul>
 *
 * <h2>Why the per-player side is STRIPED and not a pool</h2>
 * The old design put everything on one thread precisely to get that ordering for free. That serialised every
 * player behind every other, and on a login burst (a proxy failing a group of players over at once) the claim at
 * the back of the queue expired before it ever ran. The fix is NOT a flat thread pool: a pool could run two tasks
 * for the SAME player on two threads at once, and a save could then land after the release meant to follow it,
 * which is the exact duplication this layer exists to stop. Instead we hash each player's UUID to a fixed stripe,
 * so every operation for that player runs on ONE thread (ordering preserved exactly) while DIFFERENT players run
 * in parallel on different stripes. If anyone ever "simplifies" this into a shared pool, per-player ordering is
 * gone and the character-loss bugs come back silently. Stripe by identity, never pool.
 *
 * <p>Daemon threads throughout, so a stuck query can never hold the JVM open after the server has stopped.
 */
public final class ShardExecutor
{
    private ShardExecutor() {}

    /**
     * How many player stripes run in parallel.
     *
     * <p>The work is IO bound on a remote MariaDB: each of these threads spends almost all of its time blocked on a
     * socket, not on a core, so the right number is small and the real ceiling is the database, not the CPU. Six
     * sits in the middle of the sensible 4..8 band. It is enough that a realistic login burst does not queue behind
     * one slow claim, while staying well under MariaDB's connection ceiling once the single global thread and the
     * heartbeat writes are counted too. Note that {@code ShardDatabase} keeps only {@code MAX_IDLE} connections
     * warm, so a burst that lights up every stripe at once will open a few short-lived extra connections and close
     * them again; that is cheap and bounded, and not a reason to shrink the parallelism this exists to provide.
     * Deliberately NOT an operator tunable: no value they could set is worth a figure that either starves the
     * database or collapses the parallelism.
     */
    private static final int STRIPES = 6;

    private static volatile ExecutorService exec;
    private static volatile ExecutorService[] stripes;
    private static volatile ExecutorService heartbeat;

    /**
     * How long one task may hold a vault thread before the stall watch reports it.
     *
     * <p>A healthy claim or save is a round trip or two, tens of milliseconds. On 2026-09-13 {@code su-shard-vault-3}
     * on OW1 ran nothing at all for 8, then 4, then 5 minutes, logging nothing, while every player hashed to it was
     * refused at login with "Player data timed out". Nothing in the logs could say what the thread was waiting on,
     * so this exists to catch the next one in the act: fifteen seconds is far past any legitimate task and still
     * inside the window where the stack shows the cause rather than its aftermath.
     */
    private static final long STALL_REPORT_NANOS = TimeUnit.SECONDS.toNanos(15);

    private static volatile ScheduledExecutorService stallWatch;

    public static ExecutorService get()
    {
        ExecutorService e = exec;
        if (e == null || e.isShutdown())
        {
            synchronized (ShardExecutor.class)
            {
                e = exec;
                if (e == null || e.isShutdown())
                {
                    e = new WatchedThread("su-shard-vault");
                    exec = e;
                    startStallWatch();
                }
            }
        }
        return e;
    }

    /**
     * The one thread a given player's vault work always runs on.
     *
     * <p>Returns an {@link Executor} so a caller can hand it straight to {@code CompletableFuture.runAsync}. See the
     * class note for why this is striped by UUID and not pooled, and what breaks if that is ever changed.
     * {@link Math#floorMod} is used, not {@code %}, so a negative {@code hashCode()} still maps to a valid stripe.
     */
    public static Executor forPlayer(UUID id)
    {
        return stripe(id);
    }

    private static ExecutorService stripe(UUID id)
    {
        ExecutorService[] s = stripes;
        if (s == null)
        {
            synchronized (ShardExecutor.class)
            {
                s = stripes;
                if (s == null)
                {
                    s = new ExecutorService[STRIPES];
                    for (int i = 0; i < STRIPES; i++)
                    {
                        s[i] = new WatchedThread("su-shard-vault-" + i);
                    }
                    stripes = s;
                    startStallWatch();
                }
            }
        }
        return s[Math.floorMod(id.hashCode(), STRIPES)];
    }

    // Public so the tab-list sync (net.shurui.shuruisutilities.tablist) can push its presence read onto the same
    // single vault thread instead of opening a second one. It is still the only sanctioned way to do GLOBAL shard
    // I/O: anything keyed to one player must use submit(UUID, Runnable) so it stays ordered with that player's
    // other work.
    public static void submit(Runnable task)
    {
        try
        {
            get().execute(task);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[shard] Could not queue vault work: {}", t.toString());
        }
    }

    /**
     * Queue the lock refresh on its own thread, never on a stripe or the global thread.
     *
     * <h2>Why the lock refresh may not share a thread with anything</h2>
     * The refresh is what tells every other server "this server is alive and still holds these players". On
     * 2026-09-13 one task on {@code su-shard-vault-3} on OW1 hung for minutes, and the refresh for every player on
     * that stripe was queued behind it. After {@code lockTimeoutSeconds} OW2 and smp read OW1 as dead, took those
     * players with the vault copy from before the hang, and OW1's newer saves were then refused: a rollback for
     * everyone on the stripe, caused by one stuck query on a server that was fine. Ordering does not require the
     * stripe here: {@code PlayerVault#heartbeat} only touches rows WHERE {@code owner_server} is still this server,
     * so it cannot resurrect a lock a release or a handover has already moved, whatever order it runs in. The
     * presence heartbeat is different (it upserts) and stays on the stripes.
     */
    public static void submitHeartbeat(Runnable task)
    {
        try
        {
            ExecutorService e = heartbeat;
            if (e == null || e.isShutdown())
            {
                synchronized (ShardExecutor.class)
                {
                    e = heartbeat;
                    if (e == null || e.isShutdown())
                    {
                        e = new WatchedThread("su-shard-heartbeat");
                        heartbeat = e;
                        startStallWatch();
                    }
                }
            }
            e.execute(task);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[shard] Could not queue the lock heartbeat: {}", t.toString());
        }
    }

    /**
     * Queue a vault operation for one specific player, onto that player's stripe.
     *
     * <p>Use this, never {@link #submit(Runnable)}, for anything belonging to a single player (a save, a release, an
     * abandon, a presence depart), so it stays ordered with the rest of that player's vault work. See
     * {@link #forPlayer(UUID)}.
     */
    public static void submit(UUID id, Runnable task)
    {
        try
        {
            stripe(id).execute(task);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[shard] Could not queue vault work for {}: {}", id, t.toString());
        }
    }

    /**
     * Queue a player's vault work after a delay, still on THAT PLAYER'S stripe so it stays ordered with their saves.
     *
     * <p>For work that has to observe something another task is about to write. Re-queuing such a task immediately
     * just spends a database read to look at the same row again: the backpack hop confirm burned all eight of its
     * attempts inside 135 ms that way, always ahead of the save it was waiting for. The delay runs on the stall
     * watch's scheduler (a timer thread, no vault work on it) and the task itself still runs on the stripe.
     */
    public static void submitLater(UUID id, long delayMs, Runnable task)
    {
        ScheduledExecutorService watch = stallWatch;
        if (watch == null || delayMs <= 0L)
        {
            submit(id, task); // no scheduler yet (or no delay asked for): behave exactly like submit
            return;
        }
        try
        {
            watch.schedule(() -> submit(id, task), delayMs, TimeUnit.MILLISECONDS);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[shard] Could not schedule delayed vault work for {}: {}", id, t.toString());
            submit(id, task);
        }
    }

    /**
     * Stop accepting work and wait for what is queued, across the global thread AND every stripe.
     *
     * <p>Called on shutdown AFTER the last player has been captured, so the wait is what actually gets everyone's
     * final state written. Every executor is told to stop FIRST and only then awaited, so the stripes drain in
     * parallel on their own threads rather than one after another: the whole flush shares the one {@code seconds}
     * budget (see {@link ShardSync#onServerStopping}), and a serial wait would multiply it by the stripe count.
     * Bounded, because a server that will not stop is worse than one that lost a few seconds of somebody's session,
     * and the compare and set in {@code PlayerVault#save} means an unwritten player simply keeps their older blob.
     */
    public static void drainAndStop(long seconds)
    {
        ScheduledExecutorService watch = stallWatch;
        stallWatch = null;
        if (watch != null)
            watch.shutdownNow();
        ExecutorService global = exec;
        exec = null;
        ExecutorService[] s = stripes;
        stripes = null;
        ExecutorService beat = heartbeat;
        heartbeat = null;

        List<ExecutorService> all = new ArrayList<>();
        if (global != null)
        {
            global.shutdown();
            all.add(global);
        }
        if (beat != null)
        {
            beat.shutdown();
            all.add(beat);
        }
        if (s != null)
        {
            for (ExecutorService e : s)
            {
                if (e != null)
                {
                    e.shutdown();
                    all.add(e);
                }
            }
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        for (ExecutorService e : all)
        {
            long leftNanos = deadlineNanos - System.nanoTime();
            try
            {
                if (leftNanos <= 0L || !e.awaitTermination(leftNanos, TimeUnit.NANOSECONDS))
                {
                    LoggingHandler.sulog.error(
                            "[shard] Vault work did not finish within {}s; some players may keep their previous "
                                    + "saved state. Their locks will free themselves once the stale timer elapses.",
                            seconds);
                    e.shutdownNow();
                }
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                e.shutdownNow();
            }
        }
    }

    /**
     * A single vault thread that remembers when its current task started, so {@link #checkStalls} can see a stuck
     * one. Behaves exactly like {@link Executors#newSingleThreadExecutor}: one daemon thread, an unbounded queue, tasks
     * strictly in submission order, which is the per-player ordering the class note depends on.
     */
    private static final class WatchedThread extends ThreadPoolExecutor
    {
        final String name;
        volatile Thread worker;
        /** {@link System#nanoTime} when the running task began, or 0 while idle. */
        volatile long runningSince;
        /** The {@link #runningSince} already reported, so one stuck task is logged once, not every check. */
        volatile long reportedFor;

        WatchedThread(String name)
        {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r ->
            {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
            this.name = name;
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r)
        {
            worker = t;
            runningSince = System.nanoTime();
        }

        @Override
        protected void afterExecute(Runnable r, Throwable thrown)
        {
            long began = runningSince;
            runningSince = 0L;
            if (began != 0L && reportedFor == began)
                LoggingHandler.sulog.warn("[shard] {} is moving again after {}s on one task; {} task(s) were waiting.",
                        name, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began), getQueue().size());
        }
    }

    private static void startStallWatch()
    {
        if (stallWatch != null)
            return;
        ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "su-shard-stall-watch");
            t.setDaemon(true);
            return t;
        });
        stallWatch = watch;
        watch.scheduleWithFixedDelay(ShardExecutor::checkStalls, 5L, 5L, TimeUnit.SECONDS);
    }

    /** Report, once per task, any vault thread that has been on the same task past {@link #STALL_REPORT_NANOS}. */
    private static void checkStalls()
    {
        try
        {
            List<ExecutorService> all = new ArrayList<>();
            if (exec != null)
                all.add(exec);
            if (heartbeat != null)
                all.add(heartbeat);
            ExecutorService[] s = stripes;
            if (s != null)
                all.addAll(List.of(s));
            long now = System.nanoTime();
            for (ExecutorService e : all)
            {
                if (!(e instanceof WatchedThread w))
                    continue;
                long began = w.runningSince;
                Thread t = w.worker;
                if (began == 0L || t == null || now - began < STALL_REPORT_NANOS || w.reportedFor == began)
                    continue;
                w.reportedFor = began;
                StringBuilder stack = new StringBuilder();
                for (StackTraceElement frame : t.getStackTrace())
                    stack.append("\n        at ").append(frame);
                LoggingHandler.sulog.warn("[shard] {} has been on one task for {}s with {} task(s) queued behind it. "
                                + "Every player on this thread waits until it finishes. Thread state {}, stack:{}",
                        w.name, TimeUnit.NANOSECONDS.toSeconds(now - began), w.getQueue().size(), t.getState(), stack);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[shard] Stall watch check failed: {}", t.toString());
        }
    }
}
