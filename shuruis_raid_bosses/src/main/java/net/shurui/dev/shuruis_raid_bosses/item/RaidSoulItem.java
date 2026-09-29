package net.shurui.dev.shuruis_raid_bosses.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.util.TextUtil;

import java.util.List;

/**
 * The "Raid Soul": one item whose NBT names the raid it launches. All souls share a texture; name and lore
 * change with the encoded raid. Right-clicking a raid NPC holding one starts that raid immediately
 * ({@code ForgeEventHandler#onEntityInteract}). Minted from a {@link RaidBossDef} via {@link #create}.
 */
public class RaidSoulItem extends Item {
    public static final String TAG_ID = "SrbRaid";
    public static final String TAG_NAME = "SrbRaidName";

    public RaidSoulItem(Properties props) {
        super(props);
    }

    /** The raid id this stack launches, or null if the stack is a blank soul / not a soul. */
    public static String raidOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof RaidSoulItem)) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_ID)) return null;
        String id = tag.getString(TAG_ID);
        return id.isBlank() ? null : id;
    }

    /** Mint a soul stack that launches the given raid, with a coloured display name. */
    public static ItemStack create(Item soul, RaidBossDef def) {
        ItemStack stack = new ItemStack(soul);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_ID, def.id);
        tag.putString(TAG_NAME, def.name == null ? def.id : def.name);
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_NAME)) {
            return Component.translatable("item.dmz_ragnarok.raid_soul.named", TextUtil.color(tag.getString(TAG_NAME)));
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level level, List<Component> tip, TooltipFlag flag) {
        String id = raidOf(stack);
        if (id != null) {
            tip.add(Component.translatable("item.dmz_ragnarok.raid_soul.tip_raid", id).withStyle(ChatFormatting.DARK_GRAY));
            tip.add(Component.translatable("item.dmz_ragnarok.raid_soul.tip_use").withStyle(ChatFormatting.GRAY));
        } else {
            tip.add(Component.translatable("item.dmz_ragnarok.raid_soul.tip_blank").withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true; // souls always shimmer
    }
}
