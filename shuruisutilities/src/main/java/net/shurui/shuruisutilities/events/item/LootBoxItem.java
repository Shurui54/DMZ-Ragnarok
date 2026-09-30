package net.shurui.shuruisutilities.events.item;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import net.shurui.dev.sdu.api.key.EventHooks;

/**
 * A Halloween inventory loot box: right click opens it, consuming ONE and granting one random reward from a
 * pre-configured weighted list (items, cosmetics, event tokens, commands), with an optional broadcast.
 *
 * <p>The item, its texture, tooltip and the open GESTURE are CORE (public on every server, hidden keyless by
 * {@code PrivateItems} because events are a private feature). The LOOT ROLLING, reward config, cosmetic grants and
 * announcements are PRIVATE and live in the Ragnarok Key, reached through {@link EventHooks#openLootBox} (inert
 * default false). So a keyless server's box does nothing (and the item is hidden there anyway), and only the key
 * decides and delivers a reward. Two registered boxes share this class, told apart by {@link #boxType}:
 * {@code halloween_box} and {@code pumpkin_bag}, each with its OWN configured reward list and announcement.
 */
public class LootBoxItem extends Item
{
    /** The config key this box rolls against (matches a configured loot box's boxType). NEVER a colon. */
    private final String boxType;

    public LootBoxItem(Properties properties, String boxType)
    {
        super(properties);
        this.boxType = boxType == null ? "" : boxType;
    }

    public String boxType()
    {
        return boxType;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)
    {
        String key = getDescriptionId() + ".desc";
        Component desc = Component.translatable(key);
        if (!desc.getString().equals(key))
            tooltip.add(desc);
        super.appendHoverText(stack, level, tooltip, flag);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer))
            // Client just reports success so the swing plays; the server decides and delivers.
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());

        // The key rolls, grants and announces. It returns true only when a configured box was found and rolled, so
        // we consume exactly one then and nothing otherwise (keyless / unconfigured leaves the stack untouched).
        boolean opened = EventHooks.get().openLootBox(serverPlayer, boxType);
        if (!opened)
            return InteractionResultHolder.pass(stack);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F,
                1.0F + level.getRandom().nextFloat() * 0.2F);
        if (!player.getAbilities().instabuild)
            stack.shrink(1);
        return InteractionResultHolder.consume(stack);
    }
}
