package net.shurui.dev.ragnarok.visualtest;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.compat.curios.SenzuBagCurios;
import net.shurui.shuruisutilities.senzu.bag.SenzuBagInventory;

import org.slf4j.Logger;

import top.theillusivec4.curios.api.CuriosApi;

/**
 * Server-thread helpers for the {@code senzu} visual test scenario. This is the only harness class that touches the
 * senzu bean bag on the server: it fills, empties and removes the bag through the same code the game uses
 * ({@link SenzuBagInventory} for the bag NBT, Curios for the equip), so the client sees exactly what a real bag looks
 * like once Curios re-syncs it.
 *
 * <p>Curios and DragonMineZ are hard dependencies, so naming their classes here needs no {@code ModList} guard. The
 * whole package is dev-and-client-only and never reaches a shipped jar.
 */
final class SenzuSetup
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The Curios slot the bag equips into (see {@code SenzuBagCurios}). */
    private static final String SLOT = "senzu_bag";

    /** The bag and the three bean types the scenario uses. */
    static final String BAG_ID = "dmz_ragnarok:senzubag";
    static final String BEAN_SENZU = "dmz_ragnarok:bean_senzu";
    static final String BEAN_HP = "dmz_ragnarok:bean_hp";
    static final String BEAN_KI = "dmz_ragnarok:bean_ki";

    private SenzuSetup() {}

    /** Equip a bag holding three distinct bean types: 2x senzu, 1x hp, 1x ki. Returns false if a piece is missing. */
    static boolean giveAndEquipFullBag(ServerPlayer sp)
    {
        ItemStack bag = stackOf(BAG_ID, 1);
        Item senzu = itemOf(BEAN_SENZU);
        Item hp = itemOf(BEAN_HP);
        Item ki = itemOf(BEAN_KI);
        if (bag.isEmpty() || senzu == null || hp == null || ki == null)
        {
            LOGGER.warn("[VisualTest] senzu: a bag or bean item is missing; bag={}, senzu={}, hp={}, ki={}",
                    !bag.isEmpty(), senzu != null, hp != null, ki != null);
            return false;
        }
        ItemStackHandler handler = SenzuBagInventory.read(bag);
        handler.setStackInSlot(0, new ItemStack(senzu, 2));
        handler.setStackInSlot(1, new ItemStack(hp, 1));
        handler.setStackInSlot(2, new ItemStack(ki, 1));
        SenzuBagInventory.write(bag, handler);
        return equip(sp, bag);
    }

    /** Equip a bag with no beans in it (for the greyed, still-visible state). */
    static boolean equipEmptyBag(ServerPlayer sp)
    {
        ItemStack bag = stackOf(BAG_ID, 1);
        if (bag.isEmpty())
        {
            return false;
        }
        SenzuBagInventory.write(bag, new ItemStackHandler(SenzuBagInventory.SIZE));
        return equip(sp, bag);
    }

    /** Remove any equipped bag (for the absent-feature state). */
    static void removeBag(ServerPlayer sp)
    {
        try
        {
            CuriosApi.getCuriosInventory(sp).ifPresent(h -> h.setEquippedCurio(SLOT, 0, ItemStack.EMPTY));
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] senzu: could not clear the bag slot.", t);
        }
    }

    /** Beans of the given registry id currently in the equipped bag, summed across slots. */
    static int beansInBag(ServerPlayer sp, String beanId)
    {
        ItemStack bag = SenzuBagCurios.findEquipped(sp);
        if (bag.isEmpty())
        {
            return 0;
        }
        Item wanted = itemOf(beanId);
        if (wanted == null)
        {
            return 0;
        }
        ItemStackHandler handler = SenzuBagInventory.read(bag);
        int total = 0;
        for (int i = 0; i < handler.getSlots(); ++i)
        {
            ItemStack s = handler.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == wanted)
            {
                total += s.getCount();
            }
        }
        return total;
    }

    /** Beans of the given registry id currently in the player's main inventory. */
    static int beansInInventory(ServerPlayer sp, String beanId)
    {
        Item wanted = itemOf(beanId);
        if (wanted == null)
        {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < sp.getInventory().getContainerSize(); ++i)
        {
            ItemStack s = sp.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == wanted)
            {
                total += s.getCount();
            }
        }
        return total;
    }

    private static boolean equip(ServerPlayer sp, ItemStack bag)
    {
        try
        {
            CuriosApi.getCuriosInventory(sp).ifPresent(h -> h.setEquippedCurio(SLOT, 0, bag));
            return !SenzuBagCurios.findEquipped(sp).isEmpty();
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] senzu: could not equip the bag (Curios slot missing?).", t);
            return false;
        }
    }

    private static ItemStack stackOf(String id, int count)
    {
        Item item = itemOf(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    private static Item itemOf(String id)
    {
        try
        {
            return ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
