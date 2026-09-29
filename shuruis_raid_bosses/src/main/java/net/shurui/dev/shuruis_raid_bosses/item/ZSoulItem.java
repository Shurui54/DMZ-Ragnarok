package net.shurui.dev.shuruis_raid_bosses.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager;
import net.shurui.dev.shuruis_raid_bosses.registry.ModItems;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * A Z-Soul (Potara): a Curios charm that, worn in the {@code z_souls} slot, raises one DMZ stat past the
 * global cap up to this tier's ceiling. A {@code null} {@link #stat} is the rainbow soul (every channel).
 * Stat maths and persistence live in {@link ZSoulManager}; this item only carries stat + tier and pokes
 * the manager on equip/unequip.
 */
public class ZSoulItem extends Item implements ICurioItem {
    /** {@code null} for the rainbow soul (all channels). */
    public final ZStat stat;
    public final ZSoulTier tier;

    public ZSoulItem(Properties props, ZStat stat, ZSoulTier tier) {
        super(props);
        this.stat = stat;
        this.tier = tier;
    }

    public boolean isRainbow() {
        return stat == null;
    }

    @Override
    public boolean canEquip(SlotContext slotContext, ItemStack stack) {
        // Z-Souls only ever fit in our dedicated slot.
        return ModItems.ZSOUL_SLOT.equals(slotContext.identifier());
    }

    @Override
    public void onEquip(SlotContext slotContext, ItemStack prevStack, ItemStack stack) {
        if (slotContext.entity() instanceof ServerPlayer sp) ZSoulManager.refresh(sp);
    }

    @Override
    public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
        if (slotContext.entity() instanceof ServerPlayer sp) ZSoulManager.refresh(sp);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return tier == ZSoulTier.PRISMATIC; // prismatic souls carry an enchant sheen
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        Component scope = isRainbow()
                ? Component.translatable("item.dmz_ragnarok.z_soul.scope_all")
                : Component.translatable("stat.dmz_ragnarok.raid." + stat.itemNameOrDefense());
        Component tierName = Component.translatable("tier.dmz_ragnarok.raid." + tier.id);
        tip.add(Component.translatable("item.dmz_ragnarok.z_soul.raises", scope).withStyle(ChatFormatting.AQUA));
        tip.add(Component.translatable("item.dmz_ragnarok.z_soul.tier", tierName).withStyle(tier.colour));
        tip.add(Component.translatable("item.dmz_ragnarok.z_soul.wear").withStyle(ChatFormatting.DARK_GRAY));
        tip.add(Component.literal("/rg raid zsoul invest " + (isRainbow() ? "all" : stat.name().toLowerCase()) + " <points>")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
