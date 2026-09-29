package net.shurui.shuruisutilities.util.events.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.items.IItemHandlerModifiable;

// all posted on the SU internal EventBus, none cancellable
public class SUPlayerEvent extends PlayerEvent
{

    public SUPlayerEvent(Player player)
    {
        super(player);
    }

    // player has no PlayerInfo yet; hook for modules that need extra setup
    public static class NoPlayerInfoEvent extends SUPlayerEvent
    {

        public NoPlayerInfoEvent(Player player)
        {
            super(player);
        }

    }

    // AFK system flagged the player. fired by the commands module.
    public static class PlayerAFKEvent extends SUPlayerEvent
    {
        public final boolean afk;

        public PlayerAFKEvent(Player player, boolean afk)
        {
            super(player);
            this.afk = afk;
        }
    }

    public static class ClientHandshakeEstablished extends SUPlayerEvent
    {
        public ClientHandshakeEstablished(Player player)
        {
            super(player);
        }
    }

    // inventory group changed; for custom inventory support
    public static class InventoryGroupChange extends SUPlayerEvent
    {
        String newInvGroupName;
        Map<String, List<ItemStack>> newInvGroup;

        public InventoryGroupChange(Player player, String newInvGroupName,
                Map<String, List<ItemStack>> newInvGroup)
        {
            super(player);
            this.newInvGroup = newInvGroup;
            this.newInvGroupName = newInvGroupName;
        }

        public IItemHandlerModifiable swapInventory(String modname, IItemHandlerModifiable toSwap)
        {
            List<ItemStack> oldItems = new ArrayList<>();
            List<ItemStack> newItems = newInvGroup.getOrDefault(modname, new ArrayList<>());
            for (int slotIdx = 0; slotIdx < toSwap.getSlots(); slotIdx++)
            {
                oldItems.add(toSwap.getStackInSlot(slotIdx));
                if (newItems != null && slotIdx < newItems.size())
                {
                    toSwap.setStackInSlot(slotIdx, newItems.get(slotIdx));
                }
                else
                {
                    toSwap.setStackInSlot(slotIdx, ItemStack.EMPTY);
                }
            }
            newInvGroup.put(modname, oldItems);
            return toSwap;
        }
    }
}
