package net.shurui.shuruisutilities.senzu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Throwing a senzu to somebody instead of eating it: sneak and right click a bean while locked on to a player,
 * and the bean leaves your hand and flies to them.
 *
 * <h2>Why this is split across a packet and an event</h2>
 *
 * The lock-on lives only on the client ({@code LockOnEvent.getLockedTarget()}), so the server cannot know who is
 * being aimed at without being told, and the eat can only be suppressed from inside {@code RightClickItem},
 * which the client cannot cancel on the server's behalf. Both halves of 1.20.1's use path were read rather than
 * assumed, and they say:
 *
 * <ul>
 *   <li>{@code MultiPlayerGameMode#useItem} sends {@code ServerboundUseItemPacket} <b>even when the Forge event is
 *       cancelled</b> (the cancel result is returned from inside the prediction lambda, after the packet has
 *       already been built and handed back to be sent). So cancelling on the client suppresses only the client's
 *       own local prediction. The server still gets the click and would still eat the bean.</li>
 *   <li>{@code ServerPlayerGameMode#useItem} fires {@code RightClickItem} and returns early if it is cancelled,
 *       so the SERVER side of that event is the only place the eat actually stops.</li>
 * </ul>
 *
 * Hence: the client sends {@link PacketSenzuThrow} first, which parks a short-lived intent here, and the throw
 * itself happens in {@link #onRightClickItem} a moment later when the click arrives. Both travel the same
 * connection, and the packet is queued before the use packet is sent, so the intent is always parked before the
 * click is handled. The intent still expires on a tick window so a dropped or reordered click can never fire a
 * throw the player did not ask for.
 *
 * <p>One consequence worth knowing: {@code ServerPlayerGameMode#useItem} checks the item cooldown BEFORE firing
 * the event, so while the shared bean cooldown is running no throw happens either, because the event never
 * fires. That is the intended behaviour (a throw spends the same cooldown an eat does), it is just enforced a
 * layer higher than it looks.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class SenzuThrow
{
    private SenzuThrow() {}

    /** DragonMineZ's own senzu, thrown on the same terms as ours. */
    private static final ResourceLocation DMZ_SENZU_ITEM = new ResourceLocation("dragonminez", "senzu_bean");

    /** DragonMineZ's senzu sound, resolved from the registry so a pack without it falls back rather than throwing. */
    private static final ResourceLocation DMZ_SENZU_SOUND = new ResourceLocation("dragonminez", "senzu");

    /** How far the target may be. Beyond this the throw is refused outright rather than sent on a long journey. */
    private static final double MAX_THROW_DISTANCE = 48.0D;

    /** How long a parked intent stays good. Five ticks is far more than the same-connection gap it has to cover. */
    private static final int INTENT_WINDOW_TICKS = 5;

    /** Blocks per tick the bean travels. About 0.9 is a brisk lob that still reads as thrown rather than teleported. */
    private static final double FLIGHT_SPEED = 0.9D;

    /** Within this of the target's chest the bean is considered caught. */
    private static final double CATCH_DISTANCE = 1.1D;

    /** A bean that has not arrived by now stops homing and drops as an ordinary item, so it is never lost. */
    private static final int MAX_FLIGHT_TICKS = 200;

    /** A parked throw intent: who is being aimed at, and when it was parked. */
    private record Intent(int targetEntityId, long tick) {}

    /** A bean in the air: the item, who it is going to, and how long it has been travelling. */
    private static final class Flight
    {
        final ItemEntity item;
        final UUID targetId;
        int ticks;

        Flight(ItemEntity item, UUID targetId)
        {
            this.item = item;
            this.targetId = targetId;
        }
    }

    private static final Map<UUID, Intent> INTENTS = new HashMap<>();
    private static final List<Flight> IN_FLIGHT = new ArrayList<>();

    /** Whether this stack is a bean that can be thrown: any of ours, plus DragonMineZ's own senzu. */
    public static boolean isThrowable(ItemStack stack)
    {
        if (stack.isEmpty())
            return false;
        if (stack.getItem() instanceof BeanItem)
            return true;
        return DMZ_SENZU_ITEM.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    /** Park the client's aim. Called from {@link PacketSenzuThrow}; validated when the click arrives, not here. */
    public static void requestThrow(ServerPlayer player, int targetEntityId)
    {
        INTENTS.put(player.getUUID(), new Intent(targetEntityId, player.level().getGameTime()));
    }

    // HIGH, so we run BEFORE DragonMineZ's own StatsEvents#onItemRightClick, which is at the default NORMAL priority.
    // That DMZ handler calls player.startUsingItem(hand) unconditionally on every non-blacklisted right click (it is
    // how DMZ makes its senzu eat in one click), and it does NOT check whether the event is already cancelled. Its
    // @SubscribeEvent takes the default receiveCanceled = false, so a cancel BEFORE it runs skips it entirely. If we
    // ran after it, the eat had already been started: DMZ's senzu_bean is a food, so finishUsingItem then shrank a
    // second bean AND StatsEvents#onItemUseFinish healed the thrower, on top of the one we threw. That is the "throwing
    // also eats the bean" double consume. Cancelling first, from a higher priority, is what stops the eat being started
    // at all. The parked throw intent is unaffected by priority: it is set from PacketSenzuThrow, which is processed
    // before the use packet regardless of event ordering here.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        if (event.getLevel().isClientSide)
            return;
        if (!(event.getEntity() instanceof ServerPlayer thrower))
            return;

        Intent intent = INTENTS.remove(thrower.getUUID());
        if (intent == null)
            return;
        // Stale intent: the click it was meant for never arrived. Dropping it here rather than acting on it is what
        // stops a much later, unrelated right click from launching somebody's bean across the map.
        if (thrower.level().getGameTime() - intent.tick() > INTENT_WINDOW_TICKS)
            return;

        ItemStack stack = event.getItemStack();
        // Deliberately NO server-side sneak check. The client only sends PacketSenzuThrow while sneaking, but the
        // server learns about sneak from the movement update (ServerboundPlayerCommandPacket PRESS_SHIFT_KEY), which is
        // sent on the client's next tick. Pressing sneak and right clicking in the same tick delivers the click first,
        // so the server saw "not sneaking", ignored the intent, and the bean was EATEN instead of thrown. The explicit
        // intent packet is the real signal; the target, range, item and freshness are all still checked here.
        if (!isThrowable(stack))
            return;

        ServerPlayer target = resolveTarget(thrower, intent.targetEntityId());
        if (target == null)
            return;

        // From here the click belongs to us: stop the eat, then throw. Cancelling is what makes
        // ServerPlayerGameMode#useItem return before it calls the item's own use().
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        throwBean(thrower, target, stack, event.getHand());
    }

    /**
     * Turn the client's claimed entity id into a player it is actually allowed to throw to, or null. Everything a
     * lying client could gain by naming an arbitrary id is refused here: a non-player, itself, another dimension,
     * something across the map, or something it cannot see.
     */
    private static ServerPlayer resolveTarget(ServerPlayer thrower, int entityId)
    {
        Entity entity = thrower.level().getEntity(entityId);
        if (!(entity instanceof ServerPlayer target))
            return null;
        if (target == thrower || !target.isAlive())
            return null;
        if (target.level() != thrower.level())
            return null;
        if (thrower.distanceToSqr(target) > MAX_THROW_DISTANCE * MAX_THROW_DISTANCE)
            return null;
        return target;
    }

    private static void throwBean(ServerPlayer thrower, ServerPlayer target, ItemStack held, InteractionHand hand)
    {
        // One bean leaves the hand. Creative keeps the stack, matching how eating one behaves.
        ItemStack thrown = held.copyWithCount(1);
        if (!thrower.getAbilities().instabuild)
        {
            held.shrink(1);
            if (held.isEmpty())
                thrower.setItemInHand(hand, ItemStack.EMPTY);
        }

        // Spend the same shared cooldown an eaten bean spends, so throwing is not a way around it.
        SenzuRegistry.applyBeanCooldown(thrower, SenzuModule.cooldownTicks());

        Vec3 from = thrower.getEyePosition().subtract(0.0D, 0.2D, 0.0D);
        ItemEntity item = new ItemEntity(thrower.level(), from.x, from.y, from.z, thrown);
        // Only the person it was thrown to may pick it up. ItemEntity#setTarget is exactly this gate in vanilla
        // (ItemEntity#playerTouch refuses anyone else), so nobody can step in front of a bean meant for a team mate.
        item.setTarget(target.getUUID());
        item.setDeltaMovement(aim(item, target));
        item.setNoGravity(true);
        thrower.level().addFreshEntity(item);
        IN_FLIGHT.add(new Flight(item, target.getUUID()));

        thrower.level().playSound(null, thrower.getX(), thrower.getY(), thrower.getZ(), senzuSound(),
                SoundSource.PLAYERS, 0.7f, 1.4f);
    }

    /**
     * Steered here rather than by giving the item a velocity and hoping: a player who is moving (and the target of
     * a thrown senzu is usually the one running away or being hit) is not where they were when it was thrown, so a
     * ballistic toss lands behind them. Re-aiming every tick means the bean follows them.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || IN_FLIGHT.isEmpty())
            return;

        for (Iterator<Flight> it = IN_FLIGHT.iterator(); it.hasNext();)
        {
            Flight flight = it.next();
            ItemEntity item = flight.item;

            if (!item.isAlive() || ++flight.ticks > MAX_FLIGHT_TICKS)
            {
                land(item);
                it.remove();
                continue;
            }

            Player target = item.level().getPlayerByUUID(flight.targetId);
            if (target == null || !target.isAlive() || target.level() != item.level())
            {
                // They logged out, died or changed dimension mid-flight. The bean becomes an ordinary dropped item
                // where it is, rather than hanging in the air forever chasing nobody.
                land(item);
                it.remove();
                continue;
            }

            Vec3 chest = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
            Vec3 toTarget = chest.subtract(item.position());
            if (toTarget.length() <= CATCH_DISTANCE)
            {
                deliver(item, target);
                it.remove();
                continue;
            }

            item.setDeltaMovement(toTarget.normalize().scale(FLIGHT_SPEED));
            item.hasImpulse = true;
        }
    }

    /** Put the bean in their hands. Falls back to dropping at their feet when there is no room for it. */
    private static void deliver(ItemEntity item, Player target)
    {
        ItemStack stack = item.getItem().copy();
        item.discard();
        target.getInventory().add(stack); // mutates stack down to whatever did not fit
        if (!stack.isEmpty())
        {
            target.drop(stack, false);
        }
        target.level().playSound(null, target.getX(), target.getY(), target.getZ(), senzuSound(),
                SoundSource.PLAYERS, 0.7f, 1.0f);
    }

    /**
     * Both maps hold entities and player ids belonging to one running server. On singleplayer the same JVM opens a
     * second world later, so anything left here would be steering entities from a level that no longer exists.
     */
    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event)
    {
        INTENTS.clear();
        IN_FLIGHT.clear();
    }

    /** Stop homing and let the bean behave like any other dropped item, pickable by anyone. */
    private static void land(ItemEntity item)
    {
        if (!item.isAlive())
            return;
        item.setNoGravity(false);
        // The pickup restriction goes with the homing: an undelivered bean nobody but an absent player may touch is
        // a bean thrown away.
        item.setTarget(null);
    }

    private static SoundEvent senzuSound()
    {
        SoundEvent dmz = ForgeRegistries.SOUND_EVENTS.getValue(DMZ_SENZU_SOUND);
        return dmz != null ? dmz : SoundEvents.GENERIC_EAT;
    }

    private static Vec3 aim(ItemEntity item, Player target)
    {
        Vec3 chest = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 to = chest.subtract(item.position());
        return to.lengthSqr() < 1.0E-4D ? Vec3.ZERO : to.normalize().scale(FLIGHT_SPEED);
    }
}
