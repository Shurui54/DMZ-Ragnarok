package net.shurui.shuruisutilities.compat.sophisticatedbackpacks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Ejects any dragon ball found inside a Sophisticated Backpacks storage the moment its GUI is opened, handing it back
 * to the player. This is the "handle a pre-existing ball sanely" half of the backpack rule: the containment mixin
 * ({@code core.mixin.inventory.MixinSlot} predates it and never covered SB slots, and
 * {@code compat.mixin.MixinSophisticatedInventoryHandler} only stops NEW balls going in), so a ball put into a
 * backpack before this fix could still be sitting there. Rather than surgically editing the per-server contents store
 * (the delicate, duplication-prone path the vault transfer warns about at length), we wait for the player to OPEN the
 * backpack and move the ball out through the mod's own extract path into their inventory, dropping at their feet only
 * if there is no room. A move within one server's live objects: no NBT surgery, no duplication, and no race with the
 * shard vault, because a GUI open always happens well after the vault has delivered contents on arrival.
 *
 * <h2>Why open, not login</h2>
 *
 * <p>At login the vault may not yet have written a hopped backpack's contents into this server's store, so a login
 * sweep could miss the ball or, worse, be overwritten by a later vault apply that still carries it. Open is the first
 * moment the live, authoritative {@code InventoryHandler} certainly holds the real contents.
 *
 * <h2>Reflection, not a compile dependency</h2>
 *
 * <p>Follows {@link BackpackStorageAccess}'s established choice: Sophisticated Core / Backpacks are not on the compile
 * classpath, so the menu's {@code getStorageWrapper()} and the wrapper's {@code getInventoryHandler()} are resolved
 * reflectively and cached once. The returned handler is a Forge {@code ItemStackHandler} ({@link IItemHandler}), a
 * type this project already compiles against, so the actual slot scan and extraction need no reflection at all. Any
 * resolution failure leaves the integration inert. Registered only when {@code sophisticatedcore} is loaded, so the
 * menu class is never classloaded on a server without it.
 */
public final class BackpackDragonBallEject
{
    private BackpackDragonBallEject()
    {
    }

    private static final String CORE_MODID = "sophisticatedcore";
    private static final String MENU_CLASS = "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase";

    private static Class<?> menuClass;
    private static Method getStorageWrapper;   // StorageContainerMenuBase#getStorageWrapper() -> IStorageWrapper
    private static Method getInventoryHandler;  // IStorageWrapper#getInventoryHandler() -> InventoryHandler (IItemHandler)
    private static boolean resolved;

    /** Register the open-listener when Sophisticated Core is present and its GUI/API still has the shape we drive. */
    public static void init()
    {
        if (!ModList.get().isLoaded(CORE_MODID))
        {
            return;
        }
        try
        {
            menuClass = Class.forName(MENU_CLASS);
            getStorageWrapper = menuClass.getMethod("getStorageWrapper");
            Class<?> wrapperIface = Class.forName("net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper");
            getInventoryHandler = wrapperIface.getMethod("getInventoryHandler");
            resolved = true;
        }
        catch (Throwable t)
        {
            resolved = false;
            LoggingHandler.sulog.warn("[dragonball] Sophisticated Backpacks is present but its storage GUI did not "
                    + "resolve; pre-existing dragon balls will not be auto-ejected on open. Cause: {}", t.toString());
            return;
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new BackpackDragonBallEject());
    }

    @SubscribeEvent
    public void onOpen(PlayerContainerEvent.Open event)
    {
        if (!resolved || !(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        AbstractContainerMenu menu = event.getContainer();
        if (menu == null || !menuClass.isInstance(menu))
        {
            return;
        }
        try
        {
            Object wrapper = getStorageWrapper.invoke(menu);
            if (wrapper == null)
            {
                return;
            }
            Object handlerObj = getInventoryHandler.invoke(wrapper);
            if (!(handlerObj instanceof IItemHandler handler))
            {
                return;
            }
            List<ItemStack> ejected = new ArrayList<>();
            int slots = handler.getSlots();
            for (int slot = 0; slot < slots; slot++)
            {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (!DragonBallSets.isDragonBall(inSlot))
                {
                    continue;
                }
                // Extraction is never blocked (only insertion is), so this pulls the ball straight out.
                ItemStack taken = handler.extractItem(slot, inSlot.getCount(), false);
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
                    // No room: drop at the player rather than delete. A ball is never lost.
                    player.drop(ball, false);
                }
            }
            // Push the emptied backpack view to the client so the just-opened screen does not show the removed ball.
            menu.broadcastChanges();
            LoggingHandler.sulog.info("[dragonball] Ejected {} dragon ball stack(s) from a backpack opened by {}.",
                    ejected.size(), player.getGameProfile().getName());
        }
        catch (Throwable t)
        {
            // Never let a compat hiccup break opening a backpack.
            LoggingHandler.sulog.warn("[dragonball] Could not eject dragon balls from a backpack: {}", t.toString());
        }
    }
}
