package net.shurui.shuruisutilities.corrupted;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Forge-bus handler: right-clicking a placed swap block checks for all seven distinct stars within {@link
 * #SUMMON_RADIUS} of the clicked block. When the full set is present it is consumed and {@link
 * CorruptedEventManager#trigger} runs. Nothing here grants a wish or touches DMZ.
 */
public final class CorruptedBallHandler
{
    // matches DMZ's summon_radius idea: how close the seven blocks must be to count as one set
    private static final int SUMMON_RADIUS = 5;

    // retry cadence for the leftover-cleanup pass, in server ticks (100 = once every 5 seconds)
    private static final int RETRY_INTERVAL_TICKS = 100;

    private int tickCounter = 0;

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        // Held back for this release (see ReleaseToggles). This guard has to sit at the TOP: the block below
        // CONSUMES all seven balls before it calls the event, so gating the trigger instead would eat a player's
        // set and give them nothing back.
        if (!net.shurui.shuruisutilities.core.ReleaseToggles.CORRUPTED_DRAGON_BALL_EVENT)
            return;
        if (event.getHand() != InteractionHand.MAIN_HAND)
            return;
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide())
            return;
        if (!(event.getLevel() instanceof ServerLevel level))
            return;

        BlockPos clicked = event.getPos();
        BlockState clickedState = level.getBlockState(clicked);
        if (!(clickedState.getBlock() instanceof CorruptedBallBlock))
            return;

        // gather one distinct position per star within the radius; require all seven
        Map<Integer, BlockPos> found = new HashMap<>();
        int r = SUMMON_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(clicked.offset(-r, -r, -r), clicked.offset(r, r, r)))
        {
            if (!(level.getBlockState(pos).getBlock() instanceof CorruptedBallBlock ball))
                continue;
            found.putIfAbsent(ball.getStar(), pos.immutable());
        }

        if (found.size() < CorruptedBalls.COUNT)
            return;

        // Shard design: the shadow dragon raid is an OPENWORLD event. ShardConfig.Role is explicit that raid arenas
        // and events belong on the disposable open worlds, never on the SMP that holds people's builds, and
        // ShardEvents.eligibleHost() already encodes that (OPENWORLD role only). So on a shard network, refuse the
        // summon on any server that is not an eligible open-world host, BEFORE the seven balls are consumed and
        // BEFORE the cinematic touches the weather. Without this, defiling on the SMP left that world in an endless
        // storm with no dragons, because the arenas (and the dimension they live in) are not on the SMP at all. A
        // single server has the shard system off, so active() is false and the summon runs exactly as before.
        if (net.shurui.shuruisutilities.shard.ShardEvents.active()
                && !net.shurui.shuruisutilities.shard.ShardEvents.eligibleHost())
        {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    net.shurui.shuruisutilities.util.output.ChatOutputHandler.formatColors(
                            "&5The defiled dragon balls lie dormant here. Their malice can only take form in the "
                            + "open world.")));
            return;
        }

        event.setCanceled(true);

        MinecraftServer server = level.getServer();
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        for (BlockPos pos : found.values())
        {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        storage.clearPlaced();

        // The seven corrupted balls have now been consumed to summon the shadow dragons, so reset the wish cycle:
        // zero the server-wide use count and disarm the swap. Disarming restores DMZ's normal dragon ball world gen
        // (players are never left with no balls) and clears any corrupted balls still lying around. Runs only here,
        // after the full set was confirmed present and consumed above, so a partial set never resets; and because the
        // balls are already gone, a second click finds no corrupted block and returns early, so it cannot double-fire.
        // This is persisted immediately, so even a restart mid-cinematic leaves world gen restored rather than a world
        // with no dragon balls at all.
        net.shurui.shuruisutilities.compat.dmz.WishTrackingCompat.resetCycle(server);

        CorruptedEventManager.trigger(server, player, clicked);
    }

    /**
     * Throttled server-tick pass with two mutually exclusive jobs, keyed on the armed state:
     * <ul>
     *   <li><b>While armed</b>, it drains pending placements (see {@link CorruptedScatter#drain}). Stars whose
     *       target chunk was not loaded at arm time are recorded as pending, not dropped, so this pass finishes
     *       them opportunistically as those chunks load. Without it, on a real server almost no distant chunk is
     *       loaded at arm time and the seven-ball set would be permanently uncompletable.</li>
     *   <li><b>While NOT armed</b>, it retries leftover cleanup. A disarm can leave placed positions recorded when
     *       their chunks were not loaded at the time (see {@link CorruptedScatter#clear}), so a later pass has to
     *       finish the job or those blocks linger in the world forever.</li>
     * </ul>
     * Runs at most once every {@link #RETRY_INTERVAL_TICKS} ticks, and exits immediately when the relevant list is
     * empty, so it costs essentially nothing on a normal server where there is no pending or leftover work.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END)
            return;
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        if (++tickCounter < RETRY_INTERVAL_TICKS)
            return;
        tickCounter = 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null)
            return;

        // Shadow dragon encounter upkeep (phase 3c): refresh compass pips as the dragons move, prune any that no
        // longer exist, and enforce the lifetime timeout. Driven off THIS throttled tick rather than a second
        // ticker; it returns immediately when no encounter is running, so the no-work case stays free. Independent
        // of the armed/disarmed state below, so it runs before those early returns.
        ShadowDragonBossManager.tick(server);

        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        if (storage.isArmed())
        {
            // armed: finish any deferred placements whose chunks have since loaded
            if (storage.hasPending())
                CorruptedScatter.drain(overworld, storage);
            return;
        }
        // disarmed: chase leftover placed blocks that could not be cleared earlier
        if (storage.hasPlaced())
            CorruptedScatter.clear(overworld, storage);
    }
}
