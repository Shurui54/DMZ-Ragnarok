package net.shurui.shuruisutilities.compat.dmz;

import java.lang.reflect.Field;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.GeneralServerConfig;
import com.dragonminez.common.events.DMZEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.corrupted.CorruptedScatter;
import net.shurui.shuruisutilities.corrupted.ShadowDragonStorage;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * DMZ-facing half of the wish-tracking subsystem, reached only through {@link WishTrackingCompat}'s guard. Counts
 * successful dragon ball summons off DMZ's DragonSummonedEvent, arms the swap at the configured threshold, and
 * while armed suppresses DMZ's own balls by setting its generateDragonBalls world gen flag to false. The prior
 * value is stored so {@link #disarm(MinecraftServer)} can put the admin's original setting back. DMZ reloads its
 * config from disk every startup, so the suppression is re-applied on ServerStartedEvent whenever the saved data
 * still says armed. Every DMZ access is wrapped so an internals change degrades to a no-op instead of crashing.
 */
public final class WishTrackingBridge
{
    @SubscribeEvent
    public void onDragonSummoned(DMZEvent.DragonSummonedEvent event)
    {
        if (!SUConfig.wishTrackingEnabled)
            return;
        // Held back for this release (see ReleaseToggles). Returning before the count is incremented, not just
        // before arming, so the wish tally does not quietly climb past the threshold while the event is off and
        // then arm the instant it is switched back on.
        if (!net.shurui.shuruisutilities.core.ReleaseToggles.CORRUPTED_DRAGON_BALL_EVENT)
            return;

        ServerLevel level = event.getLevel();
        if (level == null)
            return;
        MinecraftServer server = level.getServer();
        if (server == null)
            return;

        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        int uses = storage.incrementUses();
        if (!storage.isArmed() && uses >= SUConfig.wishTrackingThreshold)
        {
            arm(server, storage);
        }
    }

    /**
     * Admin escape hatch: force the armed state and the ball swap now, without waiting for the use count to reach
     * the threshold. Reuses the same {@link #arm(MinecraftServer, ShadowDragonStorage)} path the threshold uses, so
     * an already armed run is a harmless no-op. Reachable from {@link WishTrackingCompat} so non-DMZ code stays
     * behind the guard.
     */
    void forceArm(MinecraftServer server)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        if (storage.isArmed())
            return;
        arm(server, storage);
    }

    private void arm(MinecraftServer server, ShadowDragonStorage storage)
    {
        storage.setArmed(true);

        // remember DMZ's current setting, then turn its ball generation off while armed
        int previous = readGenerateDragonBalls();
        if (previous >= 0)
            storage.setSavedGenerateDragonBalls(previous);
        writeGenerateDragonBalls(false);

        ServerLevel overworld = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld != null)
            CorruptedScatter.scatter(overworld, storage);

        // The defiled flag just flipped on: tell every client so the Earth radar swaps to the shadow-dragon dial now,
        // no relog needed. Broadcasting only here (not per tick) keeps the client in step with the server state.
        net.shurui.shuruisutilities.corrupted.DefiledBallsSync.broadcast(server);

        LoggingHandler.sulog.info("[wishtracking] armed at {} uses", storage.getUses());
    }

    /**
     * Undo the swap: put DMZ's generateDragonBalls flag back to the admin's stored value, clear the armed state
     * and the stored value, and remove any corrupted balls still placed in the world. Reachable from
     * {@link WishTrackingCompat} so non-DMZ code can trigger it through the guard.
     */
    void disarm(MinecraftServer server)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);

        // restore DMZ's flag only when a real prior value was captured (0 or 1); -1 means it was never read
        int saved = storage.getSavedGenerateDragonBalls();
        if (saved == 0 || saved == 1)
            writeGenerateDragonBalls(saved == 1);

        storage.setArmed(false);
        storage.setSavedGenerateDragonBalls(-1);

        ServerLevel overworld = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld != null)
            CorruptedScatter.clear(overworld, storage);

        // The defiled flag just flipped off: tell every client so the Earth radar reverts to DMZ's stock dial now.
        net.shurui.shuruisutilities.corrupted.DefiledBallsSync.broadcast(server);

        LoggingHandler.sulog.info("[wishtracking] disarmed");
    }

    /**
     * Re-apply the suppression after a restart. DMZ reloads generateDragonBalls from disk at startup, so a still
     * armed run would otherwise let normal balls generate again. Records the on-disk value as the saved value only
     * when nothing valid is stored yet (never clobbers a real admin value with the false SU forced), then writes
     * false again.
     */
    void reapplyOnStart(MinecraftServer server)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        if (!storage.isArmed())
            return;

        int saved = storage.getSavedGenerateDragonBalls();
        if (saved != 0 && saved != 1)
        {
            int current = readGenerateDragonBalls();
            if (current >= 0)
                storage.setSavedGenerateDragonBalls(current);
        }
        writeGenerateDragonBalls(false);

        LoggingHandler.sulog.info("[wishtracking] re-applied suppression on start (still armed)");
    }

    // reads DMZ's live generateDragonBalls flag: 1 = on, 0 = off, -1 = unavailable
    private static int readGenerateDragonBalls()
    {
        try
        {
            GeneralServerConfig cfg = ConfigManager.getServerConfig();
            if (cfg == null || cfg.getWorldGen() == null)
                return -1;
            Boolean value = cfg.getWorldGen().getGenerateDragonBalls();
            if (value == null)
                return -1;
            return value ? 1 : 0;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[wishtracking] could not read DMZ dragon ball flag: {}", t.toString());
            return -1;
        }
    }

    // sets DMZ's live generateDragonBalls flag. no public setter exists, so the private field on the live
    // WorldGenConfig instance is written directly; failure is logged and ignored.
    private static void writeGenerateDragonBalls(boolean value)
    {
        try
        {
            GeneralServerConfig cfg = ConfigManager.getServerConfig();
            if (cfg == null || cfg.getWorldGen() == null)
                return;
            Object worldGen = cfg.getWorldGen();
            Field field = worldGen.getClass().getDeclaredField("generateDragonBalls");
            field.setAccessible(true);
            field.set(worldGen, Boolean.valueOf(value));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[wishtracking] could not set DMZ dragon ball flag: {}", t.toString());
        }
    }
}
