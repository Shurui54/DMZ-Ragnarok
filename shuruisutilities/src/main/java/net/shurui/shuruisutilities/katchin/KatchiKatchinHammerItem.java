package net.shurui.shuruisutilities.katchin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.Level;

/**
 * Katchi katchin hammer. Extends {@link KatchinHammerItem} so it inherits the 3x3 area-break behaviour, and rolls
 * its colour when smithed (see {@link KatchiKatchinTool}).
 */
public class KatchiKatchinHammerItem extends KatchinHammerItem implements KatchiKatchinTool
{
    public KatchiKatchinHammerItem(Tier tier, int attackDamage, float attackSpeed, Item.Properties properties)
    {
        super(tier, attackDamage, attackSpeed, properties);
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player)
    {
        super.onCraftedBy(stack, level, player);
        KatchiKatchinTool.rollColourOnCraft(stack, level);
    }
}
