package net.shurui.shuruisutilities.timemachine;

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

/**
 * Curios chip that deploys the SU-owned {@link TimeMachineEntity} from the shared "hoverbike" curios slot, driven by
 * the same toggle keybind as the hoverbike, the space pod chip and the nimbus chip (see
 * {@link net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle}). A clone of {@code PodChipItem}: the item is
 * equipped into the slot on right-click and never spawns a world entity itself; deploy/recall are all in
 * {@link TimeMachineDeploy}.
 *
 * <p>Gating follows the space pod exactly (see the report / {@link PublicContent#FEATURE_TIME_MACHINE}): the public
 * {@code time-machine} feature plus its own {@code Content.TimeMachine} operator switch. This MUST agree with the
 * toggle packet's time-machine branch or the chip would equip but never summon.
 */
public class TimeMachineChipItem extends Item implements ICurioItem
{
    static final String SLOT = "hoverbike"; // shared curios slot, registered by the hoverbike datapack

    public TimeMachineChipItem(Item.Properties props)
    {
        super(props);
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack)
    {
        return true;
    }

    // server-side fallback equip into an empty "hoverbike" slot; Curios' RightClickItem normally does it first.
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        if (!TimeMachineDeploy.allowed())
            return InteractionResultHolder.pass(stack);

        if (level.isClientSide)
            return InteractionResultHolder.sidedSuccess(stack, true);

        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResultHolder.pass(stack);

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
