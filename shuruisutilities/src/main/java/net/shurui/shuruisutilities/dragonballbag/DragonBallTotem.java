package net.shurui.shuruisutilities.dragonballbag;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import net.shurui.shuruisutilities.compat.curios.DragonBallBagCurios;
import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.grave.GraveManager;

/**
 * Puts a player's dragon balls into a grave totem instead of scattering them on the floor, on death and on logout
 * alike, and answers the two questions the rest of the suite asks about such a totem.
 *
 * <p>The old rule was "a dead or departing player never keeps a ball", enforced by dropping every ball as a ground
 * entity. That held the invariant but made a set the first passer-by's property, and a dropped ball is on vanilla's
 * despawn timer. A totem keeps the same invariant (the balls leave the player) while leaving them somewhere their
 * owner, and only somebody who walks to the spot, can collect them.
 *
 * <h2>Which totem the balls land in</h2>
 *
 * <p>On death they go into the SAME grave as the rest of the loot when the player is getting one, folded in by
 * {@code GraveEventHandler} before it places the grave. Below the partial-keep level threshold no loot grave is
 * created at all, so {@link #entomb} builds a ball-only totem instead. Either way exactly one totem stands at the
 * death spot, and the balls are never handed back to the respawning player.
 *
 * <p>On logout there is no loot grave by definition, so it is always a ball-only totem.
 *
 * <h2>Ball totems do not expire</h2>
 *
 * <p>The grave sweep discards an expired grave's contents. That is fine for loot, which the owner chose to risk,
 * but it would let a seven-ball set be destroyed by a ten-minute timer with nobody present to stop it. Any grave
 * still holding a ball is therefore skipped by the sweep ({@link #holdsDragonBall}), whether it is a ball-only
 * totem or an ordinary death grave that happens to contain one. Emptying it removes it through the grave code's
 * own is-empty path, so a skipped totem is not immortal, only untimed.
 *
 * <p>Making a totem's balls visible to the radar is a separate concern and lives in
 * {@code compat.dmz.RadarTotems}, because it has to run where the whole radar packet is assembled rather than here.
 *
 * <h2>No item loss</h2>
 *
 * <p>{@link #extract} clears a slot only once the stack is safely copied into the returned list, and {@link #entomb}
 * tries the totem, then the ground, then the player's own inventory. A ball can be in the wrong place after a
 * failure, never gone.
 */
public final class DragonBallTotem
{
    private DragonBallTotem()
    {
    }

    /**
     * Pull every dragon ball out of the player's main inventory and equipped bag and return them. The source slots
     * are cleared, so the caller now OWNS these stacks and must place them somewhere ({@link #entomb} does).
     * Never throws and never returns null; on any internal failure the balls it could not take simply stay put.
     */
    public static List<ItemStack> extract(ServerPlayer player)
    {
        List<ItemStack> balls = new ArrayList<>();
        if (player == null)
        {
            return balls;
        }
        try
        {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); ++i)
            {
                ItemStack stack = inv.getItem(i);
                if (!DragonBallSets.isDragonBall(stack))
                {
                    continue;
                }
                // copy first, clear second: the stack is in the list before it leaves the slot.
                balls.add(stack.copy());
                inv.setItem(i, ItemStack.EMPTY);
            }

            ItemStack bag = DragonBallBagCurios.findEquipped(player);
            if (!bag.isEmpty())
            {
                ItemStackHandler handler = DragonBallBagInventory.read(bag);
                boolean changed = false;
                for (int i = 0; i < handler.getSlots(); ++i)
                {
                    ItemStack stack = handler.getStackInSlot(i);
                    if (!DragonBallSets.isDragonBall(stack))
                    {
                        continue;
                    }
                    balls.add(stack.copy());
                    handler.setStackInSlot(i, ItemStack.EMPTY);
                    changed = true;
                }
                if (changed)
                {
                    DragonBallBagInventory.write(bag, handler);
                    DragonBallBagCurios.persist(player, bag);
                }
            }
        }
        catch (Throwable t)
        {
            // never let this interrupt death or logout processing; whatever was collected is still returned and
            // will be placed by the caller, and anything not reached stays with the player.
        }
        return balls;
    }

    /**
     * Place these balls in a totem at the player's position. Does nothing on an empty list.
     *
     * <p>Fallback order is deliberate: totem, then the ground (the behaviour this replaced), then back into the
     * player's inventory. The last one breaks the never-keep-a-ball rule, but only in the case where the world
     * refused both other homes, and a kept ball is recoverable where a voided one is not.
     */
    public static void entomb(ServerPlayer player, List<ItemStack> balls)
    {
        if (player == null || balls == null || balls.isEmpty())
        {
            return;
        }
        if (player.level() instanceof ServerLevel level)
        {
            try
            {
                GraveManager.createGrave(level, player.blockPosition(), player.getGameProfile(), balls, 0);
                return;
            }
            catch (Throwable t)
            {
                // fall through to the ground
            }
        }
        for (ItemStack stack : balls)
        {
            if (stack == null || stack.isEmpty())
            {
                continue;
            }
            boolean dropped = false;
            try
            {
                ItemEntity entity = player.drop(stack, true, false);
                dropped = entity != null;
            }
            catch (Throwable ignored)
            {
                // handled below
            }
            if (!dropped)
            {
                try
                {
                    player.getInventory().add(stack);
                }
                catch (Throwable ignored)
                {
                    // out of options; the stack is lost only if the inventory itself is unusable
                }
            }
        }
    }

    /** True when this container holds at least one dragon ball of any set. Used to exempt a grave from expiry. */
    public static boolean holdsDragonBall(Container container)
    {
        if (container == null)
        {
            return false;
        }
        for (int i = 0; i < container.getContainerSize(); ++i)
        {
            if (DragonBallSets.isDragonBall(container.getItem(i)))
            {
                return true;
            }
        }
        return false;
    }
}
