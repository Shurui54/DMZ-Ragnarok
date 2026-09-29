package net.shurui.shuruisutilities.cosmetics.wardrobe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.shard.ShardSync;

/**
 * The server-side TRIGGERS that fire triggered cosmetic animations: JOIN, LEAVE, the cross-shard arrival, and a
 * same-server dimension change. All of these are exactly the "a handoff is a disconnect" hazard, so each one is
 * careful to tell a genuine event apart from a shard hop.
 *
 * <ul>
 *   <li><b>JOIN</b> fires on a fresh network login only. A hop arrival carries a {@link CosmeticArrival} marker,
 *       which suppresses JOIN and plays TP_ARRIVE instead. Runs at {@code LOWEST} so the vault and any placement
 *       teleport have already happened.</li>
 *   <li><b>LEAVE</b> fires on a genuine quit only, gated on {@code !ShardSync.isHandingOff}, at {@code HIGH} so it
 *       peeks the flag before {@code ShardSync.onLogout} consumes it, the blessed pattern the flag documents.</li>
 *   <li><b>TP_ARRIVE (cross-shard)</b> is consumed here from the marker the origin wrote in
 *       {@code ShardTransfer.connect}. The marker is cleared on every path in a {@code finally}.</li>
 *   <li><b>TP_ARRIVE (same-server dimension change)</b> fires on {@code PlayerChangedDimensionEvent} when the
 *       player is NOT being handed off, which is the common portal / space / dimension travel case that stays on
 *       one shard. The depart half of a same-server teleport is emitted by explicit callers of
 *       {@link CosmeticAnimations#teleport}.</li>
 * </ul>
 *
 * <p>The annotation is deliberately BARE: the five original addons are one jar now, so naming a modid is a chance
 * to name the wrong one and fail silently.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class CosmeticAnimationEvents
{
    private CosmeticAnimationEvents()
    {
    }

    /**
     * How long a JOIN (or a hop's TP_ARRIVE) waits before it plays, so the client has the catalogue first. The
     * animation server itself lives in the Ragnarok Key (S17a); this class stays in core for the paired-slot
     * migration and the arrival marker, and reaches the animations through {@code CosmeticHooks} (inert keyless).
     */
    public static final int JOIN_PLAY_DELAY_TICKS = 40;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        net.shurui.shuruisutilities.api.key.CosmeticHooks.get().onServerStarted();
        // Fold the four retired single-direction triggered slots into the two paired ones, once per file. Stamped
        // per changed player so the fold carries to sibling shards through the ordinary wardrobe merge.
        try
        {
            CosmeticWardrobeData.get(event.getServer()).migratePairedSlots();
        }
        catch (Throwable t)
        {
            // A migration that cannot run must never stop the server coming up; the read fallback keeps playback
            // correct in the meantime and the next boot retries.
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event)
    {
        net.shurui.shuruisutilities.api.key.CosmeticHooks.get().onServerStopping();
    }

    /** Fire any JOIN or cross-shard arrival plays whose short post-login delay has elapsed. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
            net.shurui.shuruisutilities.api.key.CosmeticHooks.get().onServerTick(event.getServer());
    }

    /**
     * Consume a cross-shard arrival marker (play TP_ARRIVE), or fire JOIN on a genuine fresh login. LOWEST so the
     * vault (HIGHEST) and any placement teleport (LOW) have run and the player is at their final position.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        boolean hop = false;
        try
        {
            hop = CosmeticArrival.isFreshHop(player);
            if (hop)
            {
                // A cross-shard arrival. Play the ARRIVING half on a delay, for the same reason JOIN is delayed: a
                // packet sent before the client's level and catalogue exist is dropped, and a placement teleport may
                // still move the player after login, so the position is re-read at fire time. The carried id is the
                // fallback for when the local equip read is still blank (the equipped set travels in the vault, but
                // a cosmetic edited moments before the hop may not have merged yet); playSoon prefers the equip.
                String carried = CosmeticArrival.read(player);
                net.shurui.shuruisutilities.api.key.CosmeticHooks.get().playSoon(player, CosmeticSlot.TP_ARRIVE, carried,
                        JOIN_PLAY_DELAY_TICKS);
            }
            else
            {
                // Delayed, not inline: a JOIN packet that reaches the client before its level and the wardrobe
                // catalogue have loaded is silently dropped, which was the main reason join animations never showed.
                net.shurui.shuruisutilities.api.key.CosmeticHooks.get().playSoon(player, CosmeticSlot.JOIN, "",
                        JOIN_PLAY_DELAY_TICKS);
            }
        }
        finally
        {
            // On every path, so a leftover cannot re-fire on the next unrelated login.
            CosmeticArrival.clear(player);
        }
    }

    /** LEAVE on a genuine quit only. HIGH so it sees {@link ShardSync#isHandingOff} before onLogout consumes it. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        // Drop any pre-played timed-teleport depart flag: a player who logs out mid-warmup never moved, so the flag
        // must not suppress the depart on their next teleport after reconnecting.
        CosmeticAnimations.cancelTimedDepart(player.getUUID());
        // A shard handoff is a disconnect too. Only a genuine quit plays LEAVE; the sibling shard has no observers
        // who ever saw this player, so nothing double-fires there. See the "a handoff is a disconnect" note.
        if (ShardSync.isHandingOff(player.getUUID()))
            return;
        net.shurui.shuruisutilities.api.key.CosmeticHooks.get().play(CosmeticSlot.LEAVE, player.getUUID(), player.serverLevel(), player.position(),
                player.getYRot());
    }

    /**
     * A same-server dimension change plays TP_ARRIVE at the destination. Skipped when the player is being handed
     * off to another shard, because that path plays its own depart here and its arrive on the destination login,
     * and firing both would double up.
     *
     * <p>This catches the travel that does NOT go through {@code TeleportHelper.doTeleport} (a portal, space or
     * dimension travel). A same-server teleport that DOES go through the helper plays both halves from there; if a
     * cross-dimension helper teleport also trips this, the per-subject debounce swallows the second TP_ARRIVE.
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (ShardSync.isHandingOff(player.getUUID()))
            return;
        net.shurui.shuruisutilities.api.key.CosmeticHooks.get().play(CosmeticSlot.TP_ARRIVE, player.getUUID(), player.serverLevel(), player.position(),
                player.getYRot());
    }

    /**
     * Vanilla {@code /tp} and {@code /teleport} by an operator, hooked cheaply here so a moderator's teleport plays
     * the TELEPORT pair too. The event fires BEFORE the move with the destination in hand, so DEPART goes to the
     * observers at the origin and ARRIVE to those at the destination, both same-dimension (a command teleport does
     * not cross dimensions). SU's own teleport commands go through {@code TeleportHelper.doTeleport} instead and are
     * covered there, so there is no double fire; if there were, the debounce would absorb it. A no-op when nothing
     * is equipped, decided inside {@link CosmeticAnimations#teleport}.
     */
    @SubscribeEvent
    public static void onCommandTeleport(EntityTeleportEvent.TeleportCommand event)
    {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player))
            return;
        if (!(player.level() instanceof ServerLevel level))
            return;
        Vec3 from = player.position();
        Vec3 to = new Vec3(event.getTargetX(), event.getTargetY(), event.getTargetZ());
        CosmeticAnimations.teleport(player, level, from, level, to);
    }
}
