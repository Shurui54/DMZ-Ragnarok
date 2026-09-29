package net.shurui.shuruisutilities.saibaman;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.player.Player;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.dragonminez.common.events.DMZEvent;

/**
 * Suppresses DragonMineZ's ki (energy) regeneration for a player while they are charging ki beside a growing saibaman
 * crop, so the crop's ki drain (see {@link SaibamanCropBlockEntity}) is a real, reliable net drain instead of being
 * silently offset by DragonMineZ's own charging regen.
 *
 * <p>How it works: DragonMineZ fires a cancelable {@code DMZEvent.EnergyRegenEvent} on the FORGE bus each time it is
 * about to add regen energy (once per second while charging). {@code TickHandler.regenerateEnergy} reads the event's
 * amount only if the post was NOT canceled, and treats a canceled post as an amount of zero, so canceling the event
 * fully skips that tick's regen. The saibaman crop's per-second scan marks every charging player within range via
 * {@link #mark}; this handler cancels the regen event for any player whose mark is still live. Nothing here ever
 * touches a player who is not charging beside a growing crop, so ordinary DragonMineZ charging is unaffected.
 *
 * <p>The mark carries a short expiry (a couple of seconds of game time) so the crop tick and the regen tick do not
 * have to line up within a single tick: whichever runs first, the mark set by the crop's most recent scan still covers
 * the regen that fires shortly after. Marks are keyed by player UUID and cleared on expiry, so the map never grows
 * without bound.
 *
 * <p>This references DragonMineZ's event class directly, which is safe because DragonMineZ is a mandatory dependency of
 * this addon (the crop block entity already calls DragonMineZ stat APIs directly for the same reason). If a future
 * DragonMineZ build stopped firing the event, regen would simply no longer be suppressed (the crop's drain still runs);
 * nothing here crashes.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SaibamanKiRegenSuppression
{
    private SaibamanKiRegenSuppression() {}

    // how many ticks a single mark stays live after the crop's scan set it. The crop scans once per second (20 ticks)
    // and regen also fires once per second, so a two-second window guarantees a mark set by the latest scan still
    // covers the next regen tick regardless of intra-tick ordering.
    private static final long MARK_WINDOW_TICKS = 40L;

    // player UUID -> game time (server tick) at or before which regen is suppressed for that player. Concurrent because
    // the crop's block-entity ticker and the regen event can, in principle, touch it from different level threads.
    private static final Map<UUID, Long> SUPPRESS_UNTIL = new ConcurrentHashMap<>();

    /**
     * Flag {@code player} so their ki regen is suppressed for the next {@link #MARK_WINDOW_TICKS} ticks. Called by the
     * saibaman crop's scan for every player found charging ki within range of a growing crop.
     *
     * @param playerId  the charging player's UUID
     * @param gameTime  the current server game time (level game time)
     */
    public static void mark(UUID playerId, long gameTime)
    {
        if (playerId != null)
        {
            SUPPRESS_UNTIL.put(playerId, gameTime + MARK_WINDOW_TICKS);
        }
    }

    // cancel DragonMineZ's energy regen for any player whose suppression mark is still live, and opportunistically drop
    // an expired mark so the map stays small.
    @SubscribeEvent
    public static void onEnergyRegen(DMZEvent.EnergyRegenEvent event)
    {
        Player player = event.getPlayer();
        if (player == null || player.level() == null)
        {
            return;
        }
        UUID id = player.getUUID();
        Long until = SUPPRESS_UNTIL.get(id);
        if (until == null)
        {
            return;
        }
        long now = player.level().getGameTime();
        if (until >= now)
        {
            event.setCanceled(true);
        }
        else
        {
            SUPPRESS_UNTIL.remove(id, until);
        }
    }
}
