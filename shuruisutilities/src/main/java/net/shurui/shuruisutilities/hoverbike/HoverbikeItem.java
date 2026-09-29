package net.shurui.shuruisutilities.hoverbike;

import net.minecraft.stats.Stats;
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


// curios chip for a fixed-variant HoverbikeEntity. never placed as a world entity from the item: it's equipped
// into the "hoverbike" curios slot and deployed/recalled from there via the toggle keybind.
// canEquipFromUse=true makes right-click auto-equip into the slot (Curios' RightClickItem does it + cancels);
// use() is a server-side fallback in case that event fires late or Curios reorders.
public class HoverbikeItem extends Item implements ICurioItem
{
    private static final String SLOT = "hoverbike"; // curios slot, registered by datapack

    private final int variant;

    public HoverbikeItem(int variant, Item.Properties props)
    {
        super(props);
        this.variant = variant;
    }

    public int getVariant()
    {
        return this.variant;
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack)
    {
        return true;
    }

    // server-side fallback equip into an empty "hoverbike" slot, so it's robust regardless of listener order.
    // Curios' RightClickItem normally handles the auto-equip before this runs. never spawns a world bike.
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        // Hoverbikes are public and, since batch M removed the operator switchboard, always on.
        if (level.isClientSide)
            return InteractionResultHolder.sidedSuccess(stack, true);

        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResultHolder.pass(stack);

        // only if the slot is empty (Curios' handler already equipped it in the common case)
        boolean slotEmpty = CuriosApi.getCuriosInventory(serverPlayer)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY).isEmpty())
                .orElse(false);
        if (!slotEmpty)
            return InteractionResultHolder.pass(stack);

        ItemStack chip = stack.copy();
        chip.setCount(1);
        CuriosApi.getCuriosInventory(serverPlayer).ifPresent(h -> h.setEquippedCurio(SLOT, 0, chip));
        if (!player.getAbilities().instabuild)
            stack.shrink(1);

        player.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
