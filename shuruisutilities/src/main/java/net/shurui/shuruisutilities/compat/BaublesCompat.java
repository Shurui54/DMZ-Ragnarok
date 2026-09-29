package net.shurui.shuruisutilities.compat;

import net.shurui.shuruisutilities.util.events.ServerEventHandler;
import net.shurui.shuruisutilities.util.events.player.SUPlayerEvent.InventoryGroupChange;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

// swaps a player's accessory inventory along with the rest when their inventory group changes.
// named for Baubles (no 1.20.1 release); retargeted onto Curios, which exposes the equipped-curios handler as
// the same IItemHandlerModifiable Baubles used.
public class BaublesCompat extends ServerEventHandler
{
    public BaublesCompat()
    {
        if (ModList.get().isLoaded("curios"))
        {
            LoggingHandler.sulog.info("Curios compatibility enabled.");
            register();
        }
    }

    @SubscribeEvent
    public void updateInventory(InventoryGroupChange e)
    {
        IItemHandlerModifiable inventory = CuriosApi.getCuriosInventory(e.getEntity())
                .map(ICuriosItemHandler::getEquippedCurios).orElse(null);
        e.swapInventory("curios", inventory);
    }
}
