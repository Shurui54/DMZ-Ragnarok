package net.shurui.shuruisutilities.dragons;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Drives every running shadow dragon effect, one server tick at a time.
 *
 * <p>One ticker for all of them rather than a scheduled task per effect, so an effect can always be ended and its
 * cleanup run: a freeze that has placed ice MUST get its {@code release()} even if the world unloads or the server
 * stops, and a per-effect timer gives no single place to guarantee that.
 *
 * <p>Runtime state only, deliberately not persisted. These effects last seconds; carrying one across a restart would
 * mean restoring block edits made in a previous session, which is far more likely to go wrong than simply ending the
 * effect. {@link #endAll} therefore runs cleanup for everything still active when the server stops.
 *
 * <p>Registered by hand on the Forge bus from the mod's main class, like every other handler in the merged jar.
 */
public final class DragonEffectTicker
{
    /** One running effect. {@link #tick()} returns false when it is finished and should be dropped. */
    public interface ActiveEffect
    {
        /** @return true to keep running, false when finished. Implementations run their own cleanup before false. */
        boolean tick();

        /** Force the effect to end now and undo anything it changed (server stopping, or an admin reset). */
        default void cancel() {}
    }

    private static final List<ActiveEffect> active = new ArrayList<>();

    /** Start tracking an effect. Safe to call from inside a tick: additions land on the next pass. */
    public static void add(ActiveEffect effect)
    {
        if (effect != null)
            pending.add(effect);
    }

    // Staged separately so an effect started while iterating cannot mutate the list mid-sweep.
    private static final List<ActiveEffect> pending = new ArrayList<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (!pending.isEmpty())
        {
            active.addAll(pending);
            pending.clear();
        }
        for (Iterator<ActiveEffect> it = active.iterator(); it.hasNext(); )
        {
            ActiveEffect effect = it.next();
            boolean keep;
            try
            {
                keep = effect.tick();
            }
            catch (Throwable t)
            {
                // A broken effect must not stop every other one ticking, and must not stay stuck in the list.
                net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                        "[dragons] effect errored and was dropped: {}", t.toString());
                safeCancel(effect);
                keep = false;
            }
            if (!keep)
                it.remove();
        }
    }

    /** End everything and run its cleanup. Called when the server stops so no block edit is left behind. */
    @SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event)
    {
        endAll();
    }

    public static void endAll()
    {
        pending.clear();
        for (ActiveEffect effect : active)
            safeCancel(effect);
        active.clear();
    }

    private static void safeCancel(ActiveEffect effect)
    {
        try
        {
            effect.cancel();
        }
        catch (Throwable ignored)
        {
        }
    }
}
