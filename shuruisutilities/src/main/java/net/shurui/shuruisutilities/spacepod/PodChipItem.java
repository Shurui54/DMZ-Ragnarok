package net.shurui.shuruisutilities.spacepod;

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

import net.shurui.shuruisutilities.core.config.PublicContent;

// curios chip that deploys DMZ's own SpacePodEntity. it shares the "hoverbike" curios slot and the hoverbike
// toggle keybind, so exactly one vehicle (bike OR pod) can ever be worn/deployed at a time. this is a clone of
// HoverbikeItem with the variant dropped: the pod has no cosmetic variants.
// canEquipFromUse=true makes right-click auto-equip into the slot (Curios' RightClickItem does it + cancels);
// use() is a server-side fallback in case that event fires late or Curios reorders.
// gated on the pod's own public feature (space-pod) plus its Content.SpacePod operator switch, NOT on the Hoverbikes
// module any more: the pod went public on 2026-08-30 while hoverbikes stayed key-only, so the two were split. This
// must agree with the toggle packet's pod branch (spacePodAllowed) or the chip would equip but never summon.
public class PodChipItem extends Item implements ICurioItem
{
    static final String SLOT = "hoverbike"; // shared curios slot, registered by the hoverbike datapack

    public PodChipItem(Item.Properties props)
    {
        super(props);
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack)
    {
        return true;
    }

    // server-side fallback equip into an empty "hoverbike" slot, so it's robust regardless of listener order.
    // Curios' RightClickItem normally handles the auto-equip before this runs. never spawns a world pod.
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        if (!PublicContent.allows(PublicContent.FEATURE_SPACE_POD))
            return InteractionResultHolder.pass(stack);

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
