package net.shurui.shuruisutilities.dragonballbag;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * The dragon ball bag: a Curios-equippable pouch that holds up to a full seven-star set (see
 * {@link DragonBallBagInventory}). Its inventory lives in the item's own NBT so it persists on the stack. When
 * equipped it becomes the destination for picked-up dragon balls (see the pickup handler); the bag's own inventory
 * screen is opened with the bag keybind.
 *
 * <p>Like SU's hoverbike chip this implements {@link ICurioItem} and auto-equips from a right-click:
 * {@link #canEquipFromUse} lets Curios' own RightClickItem handler slot it, and {@link #use} is a server-side
 * fallback that equips into an empty bag slot if that listener ever fires late or out of order.
 */
public class DragonBallBagItem extends Item implements ICurioItem
{
    private static final String SLOT = "dragonball_bag"; // curios slot, registered by datapack

    public DragonBallBagItem(Item.Properties props)
    {
        super(props);
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack)
    {
        return true;
    }

    // server-side fallback equip into an empty "dragonball_bag" slot, robust regardless of listener order. Curios'
    // own RightClickItem handler normally does this before use() runs; we only act when the slot is still empty so
    // we never duplicate the bag.
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide)
        {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!(player instanceof ServerPlayer serverPlayer))
        {
            return InteractionResultHolder.pass(stack);
        }

        boolean slotEmpty = CuriosApi.getCuriosInventory(serverPlayer)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY).isEmpty())
                .orElse(false);
        if (!slotEmpty)
        {
            return InteractionResultHolder.pass(stack);
        }

        ItemStack bag = stack.copy();
        bag.setCount(1);
        CuriosApi.getCuriosInventory(serverPlayer).ifPresent(h -> h.setEquippedCurio(SLOT, 0, bag));
        if (!player.getAbilities().instabuild)
        {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
