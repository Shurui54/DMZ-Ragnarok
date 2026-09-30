package net.shurui.shuruisutilities.dragonballbag;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * A headless, server-side smoke test for the dragon ball / bag containment hooks. It never runs in production: it is
 * registered on the Forge bus only when {@code -Ddmzr.testDragonBallConfine=true} is passed, and it fires once at
 * {@link ServerStartedEvent}, before any player is on, exercising the real (mixin-woven) insertion paths against a live
 * dragon ball item pulled from the registry and the bag item.
 *
 * <p>What it proves at runtime, on the paths present in a plain dev run (DMZ is a hard dep so a real ball exists; AE2,
 * Functional Storage and Sophisticated Backpacks are NOT in the dev run, so those hooks are verified by bytecode reading
 * instead and reported as unverified-at-runtime):
 * <ul>
 *   <li>A ball and the bag are REFUSED by a Forge {@link ItemStackHandler} ({@code isItemValid} false, {@code insertItem}
 *       returns the whole stack), which is the base of most modded storage.</li>
 *   <li>A ball and the bag are REFUSED through an {@link InvWrapper} over a vanilla {@link SimpleContainer} (the
 *       capability route a pipe / import bus uses into a chest, barrel, Iron Chest, ...).</li>
 *   <li>A ball and the bag are REFUSED by {@link HopperBlockEntity#addItem} into a container (the hopper / dropper /
 *       hopper-minecart automation route, and the item-entity pickup path that delegates to it).</li>
 *   <li>A ball and the bag are REFUSED by {@link Slot#mayPlace} on a slot over a non-player container (the GUI route:
 *       chests, shulkers, ender chests, and most modded screens), and ALLOWED on a slot over the player
 *       {@link Inventory}.</li>
 *   <li>An ordinary item (dirt) is ACCEPTED everywhere, proving the hooks are surgical and do not break normal storage.</li>
 * </ul>
 * A failure is logged at ERROR with the specific case; a clean run logs a single PASS summary.
 */
public final class DragonBallConfineSelfTest
{
    private int failures = 0;

    public static void initIfRequested()
    {
        // A sysprop is convenient locally; an env var survives ForgeGradle's forked run JVM (which does not inherit
        // gradle -D properties), so the headless run harness sets DMZR_TEST_DBCONFINE=true.
        boolean on = Boolean.getBoolean("dmzr.testDragonBallConfine")
                || "true".equalsIgnoreCase(System.getenv("DMZR_TEST_DBCONFINE"));
        if (!on)
        {
            return;
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new DragonBallConfineSelfTest());
        LoggingHandler.sulog.info("[dragonball-selftest] Armed: will run at ServerStarted, then stop the server.");
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event)
    {
        try
        {
            run();
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[dragonball-selftest] Threw: {}", t.toString(), t);
        }
        finally
        {
            // This build is only registered in test mode, so shutting down here keeps the headless boot test from
            // becoming a long-lived server.
            LoggingHandler.sulog.info("[dragonball-selftest] Done; stopping the server.");
            event.getServer().halt(false);
        }
    }

    private void run()
    {
        ItemStack ball = sampleBall();
        ItemStack bag = new ItemStack(DragonBallBagItems.BAG.get());
        ItemStack dirt = new ItemStack(Items.DIRT);

        if (ball.isEmpty())
        {
            LoggingHandler.sulog.error("[dragonball-selftest] FAIL: could not find any registered dragon ball item; "
                    + "DMZ ball set may not have loaded. Aborting.");
            return;
        }
        LoggingHandler.sulog.info("[dragonball-selftest] Using ball item {} and bag item {}.",
                ForgeRegistries.ITEMS.getKey(ball.getItem()), ForgeRegistries.ITEMS.getKey(bag.getItem()));

        // 1) Forge ItemStackHandler (the base of most modded storage).
        expectHandlerRefuses("ItemStackHandler / ball", ball);
        expectHandlerRefuses("ItemStackHandler / bag", bag);
        expectHandlerAccepts("ItemStackHandler / dirt (control)", dirt);

        // 2) InvWrapper over a vanilla container (the capability route into a chest / barrel / Iron Chest).
        expectInvWrapperRefuses("InvWrapper(container) / ball", ball);
        expectInvWrapperRefuses("InvWrapper(container) / bag", bag);
        expectInvWrapperAccepts("InvWrapper(container) / dirt (control)", dirt);

        // 3) Hopper / dropper automation into a container.
        expectHopperRefuses("Hopper.addItem / ball", ball);
        expectHopperRefuses("Hopper.addItem / bag", bag);
        expectHopperAccepts("Hopper.addItem / dirt (control)", dirt);

        // 4) GUI Slot.mayPlace: refused into a non-player container, allowed into the player inventory.
        expectSlotOverContainerRefuses("Slot(container).mayPlace / ball", ball);
        expectSlotOverContainerRefuses("Slot(container).mayPlace / bag", bag);
        expectSlotOverContainerAccepts("Slot(container).mayPlace / dirt (control)", dirt);
        expectSlotOverInventoryAccepts("Slot(playerInventory).mayPlace / ball", ball);
        expectSlotOverInventoryAccepts("Slot(playerInventory).mayPlace / bag", bag);

        // 5) The bag's own storage accepts a ball (the one allowed non-inventory home for balls).
        expectBagStorageAcceptsBall(ball);

        if (failures == 0)
        {
            LoggingHandler.sulog.info("[dragonball-selftest] PASS: all runtime containment cases held. "
                    + "AE2 ME network, Functional Storage and Sophisticated Backpacks are not in the dev run and were "
                    + "verified by bytecode reading only (unverified at runtime here).");
        }
        else
        {
            LoggingHandler.sulog.error("[dragonball-selftest] {} case(s) FAILED. See the FAIL lines above.", failures);
        }
    }

    private static ItemStack sampleBall()
    {
        for (Item item : ForgeRegistries.ITEMS)
        {
            ItemStack s = new ItemStack(item);
            if (DragonBallSets.isDragonBall(s))
            {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    private void expectHandlerRefuses(String name, ItemStack stack)
    {
        ItemStackHandler h = new ItemStackHandler(9);
        boolean valid = h.isItemValid(0, stack);
        ItemStack left = h.insertItem(0, stack.copy(), false);
        boolean refused = !valid && left.getCount() == stack.getCount() && h.getStackInSlot(0).isEmpty();
        record(name, refused);
    }

    private void expectHandlerAccepts(String name, ItemStack stack)
    {
        ItemStackHandler h = new ItemStackHandler(9);
        ItemStack left = h.insertItem(0, stack.copy(), false);
        record(name, left.isEmpty() && !h.getStackInSlot(0).isEmpty());
    }

    private void expectInvWrapperRefuses(String name, ItemStack stack)
    {
        InvWrapper w = new InvWrapper(new SimpleContainer(9));
        ItemStack left = w.insertItem(0, stack.copy(), false);
        record(name, left.getCount() == stack.getCount());
    }

    private void expectInvWrapperAccepts(String name, ItemStack stack)
    {
        InvWrapper w = new InvWrapper(new SimpleContainer(9));
        ItemStack left = w.insertItem(0, stack.copy(), false);
        record(name, left.isEmpty());
    }

    private void expectHopperRefuses(String name, ItemStack stack)
    {
        ItemStack left = HopperBlockEntity.addItem(new SimpleContainer(1), new SimpleContainer(1), stack.copy(), null);
        record(name, left.getCount() == stack.getCount());
    }

    private void expectHopperAccepts(String name, ItemStack stack)
    {
        ItemStack left = HopperBlockEntity.addItem(new SimpleContainer(1), new SimpleContainer(1), stack.copy(), null);
        record(name, left.isEmpty());
    }

    private void expectSlotOverContainerRefuses(String name, ItemStack stack)
    {
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);
        record(name, !slot.mayPlace(stack));
    }

    private void expectSlotOverContainerAccepts(String name, ItemStack stack)
    {
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);
        record(name, slot.mayPlace(stack));
    }

    private void expectSlotOverInventoryAccepts(String name, ItemStack stack)
    {
        Slot slot = new Slot(new Inventory(null), 0, 0, 0);
        record(name, slot.mayPlace(stack));
    }

    private void expectBagStorageAcceptsBall(ItemStack ball)
    {
        try
        {
            ItemStack bag = new ItemStack(DragonBallBagItems.BAG.get());
            ItemStackHandler handler = DragonBallBagInventory.read(bag);
            ItemStack left = handler.insertItem(0, ball.copy(), false);
            record("DragonBallBagStorage accepts a ball", left.isEmpty() && !handler.getStackInSlot(0).isEmpty());
        }
        catch (Throwable t)
        {
            record("DragonBallBagStorage accepts a ball (threw: " + t + ")", false);
        }
    }

    private void record(String name, boolean ok)
    {
        if (ok)
        {
            LoggingHandler.sulog.info("[dragonball-selftest] ok   {}", name);
        }
        else
        {
            failures++;
            LoggingHandler.sulog.error("[dragonball-selftest] FAIL {}", name);
        }
    }
}
