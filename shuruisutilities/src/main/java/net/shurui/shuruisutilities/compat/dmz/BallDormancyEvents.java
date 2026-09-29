package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.events.DMZEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Forge bus wiring for the dragon ball dormancy mechanic. Registered by class annotation (DMZ is a mandatory
 * dependency, so this always loads), on the FORGE bus, like {@link CeruleanCleanupCommand}. Every handler is PUBLIC
 * static: a private {@code @SubscribeEvent} compiles green and then fails Forge at runtime.
 *
 * <ul>
 *   <li>{@link #onDragonSummoned}: a DMZ dragon was summoned, so its ball set was spent. Mark that set dormant.</li>
 *   <li>{@link #onServerTick}: sweep for wakes on a coarse interval (the deadlines are days or a week, so
 *       precision to a few seconds is ample; mirrors the grave sweep's 100 tick cadence).</li>
 *   <li>{@link #onLogin} / {@link #onChangeDimension}: seed and refresh the client's dormant set list so a
 *       dormant set draws grey from the first frame, in the overworld and after a dimension hop alike.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BallDormancyEvents
{
    private BallDormancyEvents() {}

    // Coarse sweep cadence. The shortest dormancy is a real day, so five seconds of slack on the wake is invisible.
    private static final int SWEEP_INTERVAL_TICKS = 100;

    /**
     * A dragon was summoned, which consumed its ball set. Turn that set to stone: mark it dormant so the summon
     * gate, radar strip and grey render all engage. The freshly re-scattered set (DMZ scatters it when the dragon
     * despawns) is the stone the player then sees. Guarded so a DMZ internals change degrades to a no-op.
     */
    @SubscribeEvent
    public static void onDragonSummoned(DMZEvent.DragonSummonedEvent event)
    {
        try
        {
            ServerLevel level = event.getLevel();
            if (level == null)
                return;
            MinecraftServer server = level.getServer();
            String setId = event.getBallSetId();
            if (server == null || setId == null || setId.isEmpty())
                return;
            BallDormancy.markDormant(server, setId);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dormancy] could not record a wish's dormancy: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % SWEEP_INTERVAL_TICKS != 0)
            return;
        BallDormancy.sweep(server);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp)
            BallDormancySync.sendTo(sp);
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp)
            BallDormancySync.sendTo(sp);
    }
}
