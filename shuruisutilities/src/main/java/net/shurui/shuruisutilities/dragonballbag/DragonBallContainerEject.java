package net.shurui.shuruisutilities.dragonballbag;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.grave.GraveManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The container-agnostic backstop for the dragon ball whitelist. The insertion hooks (the GUI {@code Slot.mayPlace}
 * mixin, the {@code ItemStackHandler} / {@code InvWrapper} / hopper mixins) refuse a ball entering almost every
 * container, but the modpack has inventories the suite cannot enumerate, and some use bespoke slot and inventory types
 * that no single insertion hook reaches (a mod with a custom {@code Container} and custom GUI slots, for example). This
 * handler needs to know NOTHING about the container type: whenever a player opens ANY menu, it walks the menu's slots
 * and hands back any dragon ball sitting in a slot that is not one of the two allowed homes. So a ball that slipped into
 * a container nobody anticipated is returned to its owner the first time that container is opened, and it can never be
 * kept there.
 *
 * <h2>The whitelist, applied per slot (mirrors {@code MixinSlot})</h2>
 * <ul>
 *   <li>The player's own {@link Inventory} (the inventory rows every menu shows): left alone. A ball is meant to live
 *       here.</li>
 *   <li>The dragon ball bag ({@link DragonBallBagSlot}): left alone. The other allowed home.</li>
 *   <li>A grave / totem ({@link GraveManager#isGraveContainer}): left alone. The suite fills these with balls on death
 *       and logout on purpose, and they are collected through the grave GUI, not swept out here.</li>
 *   <li>Anything else: the ball is extracted and given to the opener, dropped at their feet only if there is no room.
 *       A move within one server's live objects, never a copy, so nothing is duplicated.</li>
 * </ul>
 *
 * <h2>Why on open, and why this is safe</h2>
 *
 * <p>Open is a rare, player-driven event, so there is no per-tick or per-container world scan and no chunk loading:
 * exactly the discipline the SavedData-freeze and watchdog-hang incidents demand. It never touches balls in the player
 * inventory or the bag, so it cannot fight the shard vault, the death snapshot, character-slot swaps or tournament
 * swaps, all of which operate on the player's own inventory. Extraction is never blocked by the containment hooks (only
 * insertion is), so pulling a stray ball out always succeeds. {@link DragonBallSets#isDragonBall} fails to "not a ball"
 * on any DMZ read error, turning this into a no-op rather than a hazard.
 *
 * <p>This generalises the earlier Sophisticated-Backpacks-only eject to every container. The backpack-specific eject is
 * kept alongside it because it reaches the backpack's full storage handler (including any slots a menu does not surface)
 * through the mod's own API; the two are idempotent, so a ball is simply returned by whichever runs first.
 */
public final class DragonBallContainerEject
{
    private DragonBallContainerEject()
    {
    }

    /** Register the open-listener on the Forge event bus. */
    public static void init()
    {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new DragonBallContainerEject());
    }

    @SubscribeEvent
    public void onOpen(PlayerContainerEvent.Open event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        AbstractContainerMenu menu = event.getContainer();
        if (menu == null || menu == player.inventoryMenu)
        {
            return; // never the player's own always-open inventory menu
        }
        ServerLevel level = player.serverLevel();
        try
        {
            List<ItemStack> ejected = new ArrayList<>();
            for (Slot slot : menu.slots)
            {
                if (slot == null)
                {
                    continue;
                }
                ItemStack inSlot = slot.getItem();
                if (!DragonBallSets.isDragonBall(inSlot))
                {
                    continue;
                }
                if (su$isAllowedHome(level, slot))
                {
                    continue; // player inventory, the bag, or a grave / totem: a ball belongs here
                }
                ItemStack taken = slot.remove(inSlot.getCount());
                if (!taken.isEmpty())
                {
                    ejected.add(taken);
                }
            }
            if (ejected.isEmpty())
            {
                return;
            }
            for (ItemStack ball : ejected)
            {
                player.getInventory().add(ball); // mutates ball down to whatever did not fit
                if (!ball.isEmpty())
                {
                    player.drop(ball, false); // no room: drop at the player rather than delete. A ball is never lost.
                }
            }
            menu.broadcastChanges();
            LoggingHandler.sulog.info("[dragonball] Returned {} stray dragon ball stack(s) to {} from an opened "
                    + "container that is not an allowed home.", ejected.size(), player.getGameProfile().getName());
        }
        catch (Throwable t)
        {
            // never let a backstop break opening a container.
            LoggingHandler.sulog.warn("[dragonball] Could not return stray dragon balls from an opened container: {}",
                    t.toString());
        }
    }

    // A slot is an allowed home for a ball when it is a player inventory slot, the dragon ball bag, or a grave totem.
    private static boolean su$isAllowedHome(ServerLevel level, Slot slot)
    {
        if (slot instanceof DragonBallBagSlot)
        {
            return true;
        }
        Container container = slot.container;
        if (container instanceof Inventory)
        {
            return true;
        }
        return GraveManager.isGraveContainer(level, container);
    }
}
