package net.shurui.shuruisutilities.dragonballbag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.shurui.shuruisutilities.compat.curios.DragonBallBagCurios;
import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.shard.ShardSync;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge-bus rules that make dragon balls behave like quest items instead of ordinary loot. Everything here is
 * server-side truth:
 *
 * <ul>
 *   <li><b>Pickup routing + one-type:</b> a picked-up ball goes into the equipped bag first, then the main
 *       inventory; a ball of a DIFFERENT set than the one already being collected is refused entirely.</li>
 *   <li><b>Entomb on death:</b> at death every ball is pulled out of the inventory and bag, even under
 *       keep-inventory, so a dead player never keeps or duplicates a ball. They go into a grave totem rather
 *       than onto the ground; see {@link DragonBallTotem} for which totem and why.</li>
 *   <li><b>Entomb on logout:</b> the same, done while the player is still in the world so the totem is built
 *       and saved before the player is removed.</li>
 *   <li><b>Entomb on server stop (shard network only):</b> the same again, but BEFORE the shard layer writes
 *       everyone to the cross-server vault, so a restart cannot leave one copy in the vault and one in a totem.</li>
 *   <li><b>Item-frame containment:</b> a ball cannot be placed into an item frame.</li>
 * </ul>
 *
 * GUI-container containment (chests, shulkers, ender chests, hopper/dropper GUIs, ...) is handled separately by the
 * {@code Slot.mayPlace} mixin; see the class comment on {@code core.mixin.inventory.MixinSlot} and the report notes
 * for the residual automation gap.
 *
 * <p>No item loss: the bag insert path returns the original stack untouched on any failure, and the death/logout
 * path hands every ball to {@link DragonBallTotem}, which falls back from totem to ground to the player's own
 * inventory, so a ball is always somewhere the player can recover it.
 */
public final class DragonBallInventoryHandler
{
    // per-player cooldown on the "one dragon at a time" refusal message so walking over a wrong-set ball does not
    // spam chat every tick. keyed by player UUID -> last-shown time in millis.
    private static final long REFUSE_MESSAGE_COOLDOWN_MS = 3000L;
    private final Map<UUID, Long> lastRefuseMessage = new HashMap<>();

    // translation key for the refusal (defined in en_us / es_es).
    private static final String ONE_TYPE_KEY = "message.dmz_ragnarok.core.dragonball_bag_one_type";

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPickup(EntityItemPickupEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        ItemEntity itemEntity = event.getItem();
        ItemStack stack = itemEntity.getItem();

        String incomingSet = DragonBallSets.setIdOf(stack);
        if (incomingSet == null)
        {
            return; // not a dragon ball: leave it entirely to vanilla
        }

        // one dragon at a time: refuse a ball of a different set than the one already being collected.
        String carried = DragonBallCarry.carriedSet(player);
        if (carried != null && !carried.equals(incomingSet))
        {
            event.setCanceled(true);
            refuse(player);
            return;
        }

        // route into the equipped bag first; with no bag, fall through so vanilla puts it in the main inventory.
        ItemStack bag = DragonBallBagCurios.findEquipped(player);
        if (bag.isEmpty())
        {
            return;
        }

        int before = stack.getCount();
        ItemStack remainder = DragonBallCarry.insertIntoBag(player, bag, stack);
        int inserted = before - remainder.getCount();
        if (inserted <= 0)
        {
            return; // bag full or insert failed: let vanilla try the main inventory
        }

        if (remainder.isEmpty())
        {
            // all of it went into the bag: consume the ground entity ourselves.
            player.take(itemEntity, inserted);
            itemEntity.discard();
            event.setCanceled(true);
        }
        else
        {
            // partial: what fit is in the bag; leave the rest on the entity and let vanilla route it into the main
            // inventory (an allowed home) on this same touch.
            itemEntity.setItem(remainder);
        }
    }

    private void refuse(ServerPlayer player)
    {
        long now = System.currentTimeMillis();
        Long last = lastRefuseMessage.get(player.getUUID());
        if (last != null && now - last < REFUSE_MESSAGE_COOLDOWN_MS)
        {
            return;
        }
        lastRefuseMessage.put(player.getUUID(), now);
        player.sendSystemMessage(Component.translatable(ONE_TYPE_KEY));
    }

    // a ball may not be placed into an item frame. only an EMPTY frame accepts an item on right-click; a filled
    // frame just rotates, so we cancel only when the frame is empty and the held item is a ball.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getLevel().isClientSide)
        {
            return;
        }
        if (event.getTarget() instanceof ItemFrame frame
                && frame.getItem().isEmpty()
                && DragonBallSets.isDragonBall(event.getItemStack()))
        {
            event.setCanceled(true);
        }
    }

    /* --------------------------------------------------------------- drop on death / logout */

    // LOWEST, deliberately: this is the FALLBACK, not the main path. The grave handler runs earlier (HIGH) and
    // normally takes the balls itself, folding them into the loot grave it is about to place so the player ends up
    // with one totem rather than two. Anything still on the player by the time we get here means no grave handler
    // acted (the keep-inventory gamerule is off, or its own placement failed), so we build a ball-only totem. Still
    // ahead of vanilla, which drops the inventory after this event returns, so the balls are never left to it.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            DragonBallTotem.entomb(player, DragonBallTotem.extract(player));
        }
    }

    // HIGH, deliberately, for two reasons that both defend against duplication. First, this must read the shard
    // transfer mark BEFORE ShardSync's own logout handler (NORMAL) consumes it, or a hop would look like a quit.
    // Second, on a genuine quit the balls must leave the inventory here BEFORE ShardSync captures that inventory
    // into the cross-server vault, or the vault would carry a ball we also entombed.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            // A shard hop is a logout here and an immediate login on the destination, and the player's inventory
            // rides the cross-server vault, so a ball in it ALREADY travels with them. Entombing on the way out
            // would leave one copy in the totem and another in the vault: a free duplicate per hop. So on a hop we
            // touch nothing and let the vault carry the single copy; only a REAL quit entombs.
            //
            // Otherwise: fired before the player is removed from the level and before their data is saved, so the
            // totem is built into the still-loaded world (and persists) and the saved inventory no longer holds the
            // balls (no duplication). There is no loot grave on a logout, so this is always a ball-only totem.
            if (!DragonBallHopGuard.isMidTransfer(player))
            {
                DragonBallTotem.entomb(player, DragonBallTotem.extract(player));
            }
        }
        // tidy the refusal-cooldown map so it does not grow across sessions.
        if (event.getEntity() != null)
        {
            lastRefuseMessage.remove(event.getEntity().getUUID());
        }
    }

    // HIGH, deliberately: ahead of the shard layer's own ServerStoppingEvent handler (NORMAL), which writes every
    // online player to the cross-server vault. On a stop the order is the reverse of a quit: that vault write runs
    // first, and only afterwards does the server disconnect everyone and fire the logout above. So without this the
    // vault was written WITH the balls and then the logout entombed them as well, and every clean stop or scheduled
    // restart left one copy in the totem and a second one restored from the vault on the next login.
    //
    // A stop is a quit for every player on it, so the balls go where a quit sends them (a totem), and they go there
    // BEFORE the vault write, exactly as on a normal quit. The logout that follows then finds nothing left to move.
    // A player marked mid shard hop is skipped here for the same reason the logout skips them: their vault copy was
    // already written by the hand-off and carries the one set.
    //
    // Only with the shard network. Without it there is no vault and the logout pass alone already produces exactly
    // one copy (the player's own save runs after it), so that path is left exactly as it was.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onServerStopping(ServerStoppingEvent event)
    {
        if (!ShardSync.active() || event.getServer() == null)
        {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers())
        {
            if (!DragonBallHopGuard.isMidTransfer(player))
            {
                DragonBallTotem.entomb(player, DragonBallTotem.extract(player));
            }
        }
    }

}
