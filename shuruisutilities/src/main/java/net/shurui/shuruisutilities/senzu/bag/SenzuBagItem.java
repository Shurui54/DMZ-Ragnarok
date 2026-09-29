package net.shurui.shuruisutilities.senzu.bag;

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
 * The senzu bean bag: a Curios-equippable pouch that holds up to six stacks of beans (see
 * {@link SenzuBagInventory}). Its inventory lives in the item's own NBT so it persists on the stack and survives
 * relog. When equipped it is the bag the two bean-bag keybinds act on: one opens its GUI to fill it, the other pulls
 * a single bean out of it. This is the placeholder {@code senzubag} content item given real behaviour (same id,
 * texture, model, lang and creative-tab slot), so it is not registered a second time anywhere else.
 *
 * <p>Like SU's hoverbike chip and the dragon ball bag this implements {@link ICurioItem} and auto-equips from a
 * right-click: {@link #canEquipFromUse} lets Curios' own RightClickItem handler slot it, and {@link #use} is a
 * server-side fallback that equips into an empty bag slot if that listener ever fires late or out of order.
 */
public class SenzuBagItem extends Item implements ICurioItem
{
    private static final String SLOT = "senzu_bag"; // curios slot, registered by datapack

    public SenzuBagItem(Item.Properties props)
    {
        super(props);
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack)
    {
        return true;
    }

    // server-side fallback equip into an empty "senzu_bag" slot, robust regardless of listener order. Curios' own
    // RightClickItem handler normally does this before use() runs; we only act when the slot is still empty so we
    // never duplicate the bag.
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
